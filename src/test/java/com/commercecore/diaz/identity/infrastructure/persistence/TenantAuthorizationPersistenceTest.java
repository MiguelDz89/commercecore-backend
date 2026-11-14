package com.commercecore.diaz.identity.infrastructure.persistence;

import com.commercecore.diaz.identity.application.AuthorizationEvaluationException;
import com.commercecore.diaz.identity.application.CurrentActor;
import com.commercecore.diaz.identity.application.TenantAuthorizationService;
import com.commercecore.diaz.identity.application.TenantPermissionDecision;
import com.commercecore.diaz.identity.application.port.TenantPermissionLookup;
import com.commercecore.diaz.identity.domain.ActorId;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.inject.Inject;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class TenantAuthorizationPersistenceTest {
    private static final String ISSUER = "https://issuer.example/realm";
    @Inject DataSource dataSource;
    @Inject TenantPermissionLookup lookup;

    @Test
    @TestTransaction
    void permissionsAreScopedToTheRequestedTenant() throws SQLException {
        Fixture f = fixture();
        UUID membershipA = membership(f.user, f.tenantA, "ACTIVE");
        UUID membershipB = membership(f.user, f.tenantB, "ACTIVE");
        UUID roleA = role(f.tenantA, "ROLE_A");
        UUID roleB = role(f.tenantB, "ROLE_B");
        assign(f.tenantA, membershipA, roleA, "TENANT_READ");
        assign(f.tenantB, membershipB, roleB, "TENANT_MANAGE");
        var authorization = service(Optional.of(f.actor), false);

        assertEquals(TenantPermissionDecision.ALLOW, authorization.hasPermission(f.tenantA, "TENANT_READ"));
        assertEquals(TenantPermissionDecision.DENY, authorization.hasPermission(f.tenantA, "TENANT_MANAGE"));
        assertEquals(TenantPermissionDecision.ALLOW, authorization.hasPermission(f.tenantB, "TENANT_MANAGE"));
        assertEquals(TenantPermissionDecision.DENY, authorization.hasPermission(f.tenantB, "TENANT_READ"));
        assertEquals(TenantPermissionDecision.DENY, authorization.hasPermission(f.tenantA, "USER_MANAGE"));
        assertEquals(TenantPermissionDecision.DENY, service(Optional.of(new ActorId("https://other.example", f.actor.subject())), false)
                .hasPermission(f.tenantA, "TENANT_READ"));
        assertEquals(TenantPermissionDecision.DENY, service(Optional.of(new ActorId(ISSUER, "missing-" + UUID.randomUUID())), false)
                .hasPermission(f.tenantA, "TENANT_READ"));
        assertEquals(TenantPermissionDecision.DENY, service(Optional.empty(), false).hasPermission(f.tenantA, "TENANT_READ"));
    }

    @Test
    @TestTransaction
    void inactiveAccountsAndMembershipsNeverGrantPermissions() throws SQLException {
        Fixture f = fixture();
        UUID membershipA = membership(f.user, f.tenantA, "ACTIVE");
        UUID roleA = role(f.tenantA, "ROLE_A");
        assign(f.tenantA, membershipA, roleA, "TENANT_READ");
        var authorization = service(Optional.of(f.actor), false);
        assertEquals(TenantPermissionDecision.ALLOW, authorization.hasPermission(f.tenantA, "TENANT_READ"));
        assertEquals(TenantPermissionDecision.DENY, authorization.hasPermission(f.tenantB, "TENANT_READ"));
        exec("UPDATE user_account SET status = ? WHERE user_id = ?", "DISABLED", f.user);
        assertEquals(TenantPermissionDecision.DENY, authorization.hasPermission(f.tenantA, "TENANT_READ"));
        exec("UPDATE user_account SET status = ? WHERE user_id = ?", "ACTIVE", f.user);
        for (String status : new String[]{"INVITED", "SUSPENDED"}) {
            exec("UPDATE tenant_membership SET status = ? WHERE membership_id = ?", status, membershipA);
            assertEquals(TenantPermissionDecision.DENY, authorization.hasPermission(f.tenantA, "TENANT_READ"));
        }
        assertEquals(TenantPermissionDecision.DENY, service(Optional.of(f.actor), true)
                .hasPermission(f.tenantB, "TENANT_READ"), "Global authority must not grant tenant permissions");
    }

    @Test
    @TestTransaction
    void revokedPermissionIsDeniedOnNextEvaluation() throws SQLException {
        Fixture f = fixture();
        UUID membershipA = membership(f.user, f.tenantA, "ACTIVE");
        UUID roleA = role(f.tenantA, "ROLE_A");
        assign(f.tenantA, membershipA, roleA, "TENANT_READ");
        var authorization = service(Optional.of(f.actor), false);
        assertEquals(TenantPermissionDecision.ALLOW, authorization.hasPermission(f.tenantA, "TENANT_READ"));
        exec("DELETE FROM role_permission WHERE tenant_id = ? AND role_id = ? AND permission_code = ?",
                f.tenantA, roleA, "TENANT_READ");
        assertEquals(TenantPermissionDecision.DENY, authorization.hasPermission(f.tenantA, "TENANT_READ"));
    }

    @Test
    void committedRevocationIsVisibleInLaterTransactions() {
        Granted grant = QuarkusTransaction.requiringNew().call(() -> {
            Fixture fixture = fixture();
            UUID membership = membership(fixture.user, fixture.tenantA, "ACTIVE");
            UUID role = role(fixture.tenantA, "ROLE_A");
            assign(fixture.tenantA, membership, role, "TENANT_READ");
            return new Granted(fixture, role);
        });
        var authorization = service(Optional.of(grant.fixture.actor), false);
        assertEquals(TenantPermissionDecision.ALLOW, QuarkusTransaction.requiringNew()
                .call(() -> authorization.hasPermission(grant.fixture.tenantA, "TENANT_READ")));
        QuarkusTransaction.requiringNew().call(() -> {
            exec("DELETE FROM role_permission WHERE tenant_id = ? AND role_id = ? AND permission_code = ?",
                    grant.fixture.tenantA, grant.role, "TENANT_READ");
            return null;
        });
        assertEquals(TenantPermissionDecision.DENY, QuarkusTransaction.requiringNew()
                .call(() -> authorization.hasPermission(grant.fixture.tenantA, "TENANT_READ")));
    }

    @Test
    @TestTransaction
    void databaseFailurePropagatesAsOperationalError() throws SQLException {
        Fixture f = fixture();
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            assertThrows(SQLException.class, () -> statement.execute("SELECT 1 / 0"));
        }
        AuthorizationEvaluationException error = assertThrows(AuthorizationEvaluationException.class,
                () -> service(Optional.of(f.actor), false).hasPermission(f.tenantA, "TENANT_READ"));
        SQLException sql = assertInstanceOf(SQLException.class, error.getCause());
        assertEquals("25P02", sql.getSQLState());
    }

    @Test
    @TestTransaction
    void compositeForeignKeyRejectsRoleFromAnotherTenant() throws SQLException {
        Fixture f = fixture();
        UUID membershipA = membership(f.user, f.tenantA, "ACTIVE");
        UUID roleB = role(f.tenantB, "ROLE_B");
        SQLException error = assertThrows(SQLException.class, () -> exec(
                "INSERT INTO membership_role (tenant_id, membership_id, role_id) VALUES (?, ?, ?)",
                f.tenantA, membershipA, roleB));
        assertEquals("23503", error.getSQLState());
    }

    @Test
    @TestTransaction
    void compositeForeignKeyRejectsPermissionAssignmentAcrossTenants() throws SQLException {
        Fixture f = fixture();
        UUID roleB = role(f.tenantB, "ROLE_B");
        SQLException error = assertThrows(SQLException.class, () -> exec(
                "INSERT INTO role_permission (tenant_id, role_id, permission_code) VALUES (?, ?, ?)",
                f.tenantA, roleB, "TENANT_READ"));
        assertEquals("23503", error.getSQLState());
    }

    private TenantAuthorizationService service(Optional<ActorId> id, boolean globalRole) {
        CurrentActor actor = new CurrentActor() {
            public Optional<ActorId> id() { return id; }
            public boolean hasPlatformSuperAdminRole() { return globalRole; }
        };
        return new TenantAuthorizationService(actor, lookup);
    }

    private Fixture fixture() throws SQLException {
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();
        UUID user = UUID.randomUUID();
        ActorId actor = new ActorId(ISSUER, "subject-" + UUID.randomUUID());
        exec("INSERT INTO tenant (tenant_id, slug, legal_name, display_name) VALUES (?, ?, ?, ?)", tenantA, "a-" + tenantA, "A", "A");
        exec("INSERT INTO tenant (tenant_id, slug, legal_name, display_name) VALUES (?, ?, ?, ?)", tenantB, "b-" + tenantB, "B", "B");
        exec("INSERT INTO user_account (user_id, oidc_issuer, oidc_subject) VALUES (?, ?, ?)", user, actor.issuer(), actor.subject());
        return new Fixture(tenantA, tenantB, user, actor);
    }

    private UUID membership(UUID user, UUID tenant, String status) throws SQLException {
        UUID id = UUID.randomUUID();
        exec("INSERT INTO tenant_membership (membership_id, tenant_id, user_id, status) VALUES (?, ?, ?, ?)", id, tenant, user, status);
        return id;
    }

    private UUID role(UUID tenant, String code) throws SQLException {
        UUID id = UUID.randomUUID();
        exec("INSERT INTO app_role (role_id, tenant_id, code, name) VALUES (?, ?, ?, ?)", id, tenant, code, code);
        return id;
    }

    private void assign(UUID tenant, UUID membership, UUID role, String permission) throws SQLException {
        exec("INSERT INTO membership_role (tenant_id, membership_id, role_id) VALUES (?, ?, ?)", tenant, membership, role);
        exec("INSERT INTO role_permission (tenant_id, role_id, permission_code) VALUES (?, ?, ?)", tenant, role, permission);
    }

    private void exec(String sql, Object... parameters) throws SQLException {
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) statement.setObject(i + 1, parameters[i]);
            statement.executeUpdate();
        }
    }

    private record Fixture(UUID tenantA, UUID tenantB, UUID user, ActorId actor) { }
    private record Granted(Fixture fixture, UUID role) { }
}
