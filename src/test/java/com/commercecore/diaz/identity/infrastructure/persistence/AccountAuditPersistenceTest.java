package com.commercecore.diaz.identity.infrastructure.persistence;

import com.commercecore.diaz.audit.application.TenantAuditEvent;
import com.commercecore.diaz.audit.application.TenantAuditRecorder;
import com.commercecore.diaz.identity.application.AccountProvisioningService;
import com.commercecore.diaz.identity.application.CurrentActor;
import com.commercecore.diaz.identity.application.port.LocalAccountStore;
import com.commercecore.diaz.identity.domain.ActorId;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.commercecore.diaz.audit.application.TenantAuditEvent.Action.ROLE_ASSIGNED;
import static com.commercecore.diaz.audit.application.TenantAuditEvent.EntityType.MEMBERSHIP;

@QuarkusTest
class AccountAuditPersistenceTest {
    @Inject DataSource dataSource;
    @Inject LocalAccountStore accounts;
    @Inject TenantAuditRecorder audit;

    @Test
    @TestTransaction
    void provisionIsIdempotentDoesNotAssignMembershipAndDoesNotReactivateDisabledAccount() throws SQLException {
        ActorId actor = actor();
        var service = service(actor);
        UUID id = service.provisionCurrentActor();
        assertEquals(id, service.provisionCurrentActor());
        assertEquals(0, count("SELECT count(*) FROM tenant_membership WHERE user_id = ?", id));
        assertEquals(0, count("SELECT count(*) FROM membership_role mr JOIN tenant_membership m ON m.membership_id = mr.membership_id WHERE m.user_id = ?", id));
        exec("UPDATE user_account SET status = 'DISABLED' WHERE user_id = ?", id);
        assertEquals(id, service.provisionCurrentActor());
        assertEquals(1, count("SELECT count(*) FROM user_account WHERE user_id = ? AND status = 'DISABLED'", id));
    }

    @Test
    void concurrentProvisioningReturnsOneAccount() throws Exception {
        ActorId actor = actor();
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(4);
        try {
            var futures = new ArrayList<java.util.concurrent.Future<UUID>>();
            for (int i = 0; i < 4; i++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    return QuarkusTransaction.requiringNew().call(() -> service(actor).provisionCurrentActor());
                }));
            }
            start.countDown();
            UUID id = futures.get(0).get();
            for (var future : futures) assertEquals(id, future.get());
            assertEquals(1, QuarkusTransaction.requiringNew().call(() -> count(
                    "SELECT count(*) FROM user_account WHERE oidc_issuer = ? AND oidc_subject = ?", actor.issuer(), actor.subject())));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @TestTransaction
    void auditStoresActorTenantAndRelatedEntityInCallerTransaction() throws SQLException {
        UUID tenant = createTenant();
        UUID actorId = service(actor()).provisionCurrentActor();
        UUID entity = UUID.randomUUID();
        UUID related = UUID.randomUUID();
        UUID correlation = UUID.randomUUID();
        audit.record(new TenantAuditEvent(tenant, actorId, MEMBERSHIP, entity, ROLE_ASSIGNED, related, correlation));
        assertEquals(1, count("""
                SELECT count(*) FROM audit_event WHERE tenant_id = ? AND actor_user_id = ? AND entity_id = ?
                  AND action = 'ROLE_ASSIGNED' AND after_data ->> 'relatedEntityId' = ? AND correlation_id = ?
                """, tenant, actorId, entity, related.toString(), correlation.toString()));
    }

    @Test
    @TestTransaction
    void auditStoresPermissionSnapshotsInTheSameTransaction() throws SQLException {
        UUID tenant = createTenant();
        UUID actorId = service(actor()).provisionCurrentActor();
        UUID role = UUID.randomUUID();
        audit.record(new TenantAuditEvent(tenant, actorId, TenantAuditEvent.EntityType.ROLE, role,
                TenantAuditEvent.Action.ROLE_PERMISSIONS_CHANGED, null, null,
                java.util.Set.of("PRODUCT_READ"), java.util.Set.of("PRODUCT_UPDATE")));
        assertEquals(1, count("""
                SELECT count(*) FROM audit_event WHERE tenant_id=? AND entity_id=?
                  AND before_data->'permissions' @> '["PRODUCT_READ"]'::jsonb
                  AND after_data->'permissions' @> '["PRODUCT_UPDATE"]'::jsonb
                """, tenant, role));
    }

    @Test
    void successfulAuditAndAccountRollBackTogetherWhenCallerFails() {
        UUID tenant = QuarkusTransaction.requiringNew().call(this::createTenant);
        ActorId actor = actor();
        UUID entity = UUID.randomUUID();
        assertThrows(IllegalStateException.class, () -> QuarkusTransaction.requiringNew().run(() -> {
            UUID id = service(actor).provisionCurrentActor();
            audit.record(new TenantAuditEvent(tenant, id, MEMBERSHIP, entity, ROLE_ASSIGNED, null, null));
            throw new IllegalStateException("simulate failure after audit");
        }));
        assertEquals(0, QuarkusTransaction.requiringNew().call(() -> count(
                "SELECT count(*) FROM user_account WHERE oidc_issuer = ? AND oidc_subject = ?", actor.issuer(), actor.subject())));
        assertEquals(0, QuarkusTransaction.requiringNew().call(() -> count(
                "SELECT count(*) FROM audit_event WHERE tenant_id = ? AND entity_id = ?", tenant, entity)));
    }

    @Test
    void auditFailureRollsBackCallerAndRequiresTransaction() {
        UUID tenant = QuarkusTransaction.requiringNew().call(this::createTenant);
        ActorId actor = actor();
        assertThrows(RuntimeException.class, () -> audit.record(new TenantAuditEvent(
                tenant, UUID.randomUUID(), MEMBERSHIP, UUID.randomUUID(), ROLE_ASSIGNED, null, null)));
        assertThrows(RuntimeException.class, () -> QuarkusTransaction.requiringNew().run(() -> {
            service(actor).provisionCurrentActor();
            audit.record(new TenantAuditEvent(tenant, UUID.randomUUID(), MEMBERSHIP, UUID.randomUUID(), ROLE_ASSIGNED, null, null));
        }));
        assertEquals(0, QuarkusTransaction.requiringNew().call(() -> count(
                "SELECT count(*) FROM user_account WHERE oidc_issuer = ? AND oidc_subject = ?", actor.issuer(), actor.subject())));
    }

    private AccountProvisioningService service(ActorId actor) {
        CurrentActor current = new CurrentActor() {
            public Optional<ActorId> id() { return Optional.of(actor); }
            public boolean hasPlatformSuperAdminRole() { return false; }
        };
        return new AccountProvisioningService(current, accounts);
    }

    private ActorId actor() { return new ActorId("https://issuer.example", "subject-" + UUID.randomUUID()); }

    private UUID createTenant() throws SQLException {
        UUID id = UUID.randomUUID();
        exec("INSERT INTO tenant (tenant_id, slug, legal_name, display_name) VALUES (?, ?, ?, ?)", id, "audit-" + id, "Legal", "Display");
        return id;
    }

    private void exec(String sql, Object... values) throws SQLException {
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++) statement.setObject(i + 1, values[i]);
            statement.executeUpdate();
        }
    }

    private int count(String sql, Object... values) throws SQLException {
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++) statement.setObject(i + 1, values[i]);
            try (var rows = statement.executeQuery()) { rows.next(); return rows.getInt(1); }
        }
    }
}
