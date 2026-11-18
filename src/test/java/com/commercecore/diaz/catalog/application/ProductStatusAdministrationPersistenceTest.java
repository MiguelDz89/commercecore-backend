package com.commercecore.diaz.catalog.application;

import static com.commercecore.diaz.catalog.domain.Product.Status.ACTIVE;
import static com.commercecore.diaz.catalog.domain.Product.Status.ARCHIVED;
import static com.commercecore.diaz.catalog.domain.Product.Status.DRAFT;
import static com.commercecore.diaz.catalog.domain.Product.Status.INACTIVE;
import static com.commercecore.diaz.catalog.domain.Product.Status.PENDING_RECEIPT;
import static org.junit.jupiter.api.Assertions.*;

import com.commercecore.diaz.catalog.domain.Product;
import com.commercecore.diaz.catalog.application.ProductStatusConflictException.Code;
import com.commercecore.diaz.catalog.application.ProductStatusConflictException.Conflict;
import com.commercecore.diaz.identity.application.CurrentActor;
import com.commercecore.diaz.identity.domain.ActorId;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

@QuarkusTest
@TestProfile(ProductStatusAdministrationPersistenceTest.Profile.class)
class ProductStatusAdministrationPersistenceTest {
    private static final String PERMISSION = "PRODUCT_STATUS_UPDATE";
    private static final String ISSUER = "https://test.example/catalog";
    private static final OffsetDateTime SEED_TIMESTAMP = OffsetDateTime.parse("2000-01-01T00:00:00Z");

    @Inject DataSource dataSource;
    @Inject ProductAdministration administration;
    @Inject FixtureActor fixtureActor;

    @Test
    void permitsFirstActivationWithoutReasonAndRejectsDraftAsDestination() throws SQLException {
        var fixture = fixture(PERMISSION);
        UUID product = product(fixture, "SIMPLE", null, DRAFT, false, false);

        UUID correlation = administration.changeStatus(fixture.tenant(), change(ACTIVE, null, item(product, 0)));
        assertState(fixture, product, ACTIVE, 1, false, false);
        assertEquals(1, count("""
                SELECT count(*) FROM audit_event WHERE tenant_id=? AND entity_id=? AND correlation_id=?
                  AND entity_type='PRODUCT' AND action='PRODUCT_STATUS_CHANGED'
                  AND before_data->>'status'='DRAFT' AND after_data->>'status'='ACTIVE'
                  AND after_data->>'reason' IS NULL
                """, fixture.tenant(), product, correlation.toString()));

        assertThrows(ProductConflictException.class,
                () -> administration.changeStatus(fixture.tenant(), change(DRAFT, null, item(product, 1))));
        assertState(fixture, product, ACTIVE, 1, false, false);
        assertEquals(1, auditCount(fixture));
    }

    @Test
    void inactivatesPublishedProductAndRetainsItsHistoryOnReactivation() throws SQLException {
        var fixture = fixture(PERMISSION);
        UUID group = product(fixture, "VARIABLE", null, ACTIVE, true, true);
        UUID variant = product(fixture, "VARIANT", group, ACTIVE, true, true);

        UUID correlation = administration.changeStatus(fixture.tenant(), change(INACTIVE, "Temporada", item(group, 0)));
        assertState(fixture, group, INACTIVE, 1, false, true);
        assertState(fixture, variant, ACTIVE, 0, true, true);
        assertEquals(1, count("""
                SELECT count(*) FROM audit_event WHERE tenant_id=? AND entity_id=? AND actor_user_id=?
                  AND correlation_id=? AND action='PRODUCT_STATUS_CHANGED' AND occurred_at>?
                  AND before_data @> '{"status":"ACTIVE","version":0,"published":true,"everPublished":true}'
                  AND after_data @> '{"status":"INACTIVE","version":1,"published":false,"everPublished":true,"reason":"Temporada"}'
                """, fixture.tenant(), group, fixture.actor(), correlation.toString(), SEED_TIMESTAMP));
        assertEquals(1, count("""
                SELECT count(*) FROM product WHERE tenant_id=? AND product_id=? AND updated_by=? AND updated_at>?
                """, fixture.tenant(), group, fixture.actor(), SEED_TIMESTAMP));

        administration.changeStatus(fixture.tenant(), change(ACTIVE, null, item(group, 1)));
        assertState(fixture, group, ACTIVE, 2, false, true);
        assertState(fixture, variant, ACTIVE, 0, true, true);
    }

    @Test
    void rejectsStaleAndForbiddenTransitionsBeforeWritingAnyItem() throws SQLException {
        var fixture = fixture(PERMISSION);
        UUID draft = product(fixture, "SIMPLE", null, DRAFT, false, false);
        UUID active = product(fixture, "SIMPLE", null, ACTIVE, true, true);

        var invalid = assertThrows(ProductStatusConflictException.class,
                () -> administration.changeStatus(fixture.tenant(),
                        change(ACTIVE, null, item(draft, 0), item(active, 0))));
        assertEquals(List.of(new Conflict(active, Code.INVALID_TRANSITION)), invalid.conflicts());
        var stale = assertThrows(ProductStatusConflictException.class,
                () -> administration.changeStatus(fixture.tenant(),
                        change(PENDING_RECEIPT, null, item(draft, 0), item(active, 1))));
        assertEquals(List.of(new Conflict(active, Code.VERSION_CONFLICT)), stale.conflicts());
        assertState(fixture, draft, DRAFT, 0, false, false);
        assertState(fixture, active, ACTIVE, 0, true, true);
        assertEquals(0, auditCount(fixture));
    }

    @Test
    void archivesAnExplicitBatchWithSharedAuditCorrelationAndPreservedCommerceData() throws SQLException {
        var fixture = fixture(PERMISSION);
        UUID group = product(fixture, "VARIABLE", null, ACTIVE, true, true);
        UUID pending = product(fixture, "VARIANT", group, PENDING_RECEIPT, false, false);
        UUID alreadyArchived = product(fixture, "VARIANT", group, ARCHIVED, false, true);
        UUID composite = product(fixture, "COMPOSITE", null, DRAFT, false, false);
        execute("""
                UPDATE product SET sale_price=14.25, sale_price_configured=true, cost_price=8.91, currency_code='USD'
                WHERE tenant_id=? AND product_id=?
                """, fixture.tenant(), pending);
        execute("""
                INSERT INTO product_price_tier (tenant_id,product_id,minimum_quantity,unit_price) VALUES (?,?,1.250,12.10)
                """, fixture.tenant(), pending);

        var blocked = assertThrows(ProductStatusConflictException.class,
                () -> administration.changeStatus(fixture.tenant(),
                        change(ARCHIVED, "Fin de vida", item(group, 0), item(composite, 0))));
        assertEquals(List.of(new Conflict(group, Code.UNARCHIVED_VARIANT, pending)), blocked.conflicts());
        assertState(fixture, group, ACTIVE, 0, true, true);
        assertState(fixture, composite, DRAFT, 0, false, false);
        assertEquals(0, auditCount(fixture));

        UUID correlation = administration.changeStatus(fixture.tenant(),
                change(ARCHIVED, "Fin de vida", item(pending, 0), item(composite, 0), item(group, 0)));
        assertState(fixture, group, ARCHIVED, 1, false, true);
        assertState(fixture, pending, ARCHIVED, 1, false, false);
        assertState(fixture, alreadyArchived, ARCHIVED, 0, false, true);
        assertState(fixture, composite, ARCHIVED, 1, false, false);
        assertEquals(3, count("""
                SELECT count(DISTINCT entity_id) FROM audit_event WHERE tenant_id=? AND correlation_id=?
                  AND actor_user_id=? AND action='PRODUCT_STATUS_CHANGED' AND after_data->>'reason'='Fin de vida'
                """, fixture.tenant(), correlation.toString(), fixture.actor()));
        assertEquals(3, auditCount(fixture));
        assertEquals(1, count("""
                SELECT count(*) FROM product p JOIN product_price_tier tier
                  ON tier.tenant_id=p.tenant_id AND tier.product_id=p.product_id
                WHERE p.tenant_id=? AND p.product_id=? AND p.parent_id=? AND p.sku=?
                  AND p.variant_attributes @> '{"color":"negro"}' AND p.sale_price_configured
                  AND p.sale_price=14.25 AND p.cost_price=8.91 AND p.currency_code='USD'
                  AND tier.minimum_quantity=1.250 AND tier.unit_price=12.10
                """, fixture.tenant(), pending, group, pending.toString()));
        assertThrows(ProductConflictException.class,
                () -> administration.changeStatus(fixture.tenant(), change(ACTIVE, null, item(pending, 1))));
        assertState(fixture, pending, ARCHIVED, 1, false, false);
        assertEquals(3, auditCount(fixture));
    }

    @Test
    void deniesForeignAndUnknownProductsWithTheSameResponse() throws SQLException {
        var local = fixture(PERMISSION);
        UUID own = product(local, "SIMPLE", null, DRAFT, false, false);
        UUID otherTenant = tenant();
        UUID foreignCategory = category(otherTenant);
        var other = new Fixture(otherTenant, foreignCategory, local.actor(), local.membership());
        UUID foreign = product(other, "SIMPLE", null, DRAFT, false, false);

        var denied = assertThrows(SecurityException.class,
                () -> administration.changeStatus(local.tenant(), change(ACTIVE, null, item(own, 0), item(foreign, 0))));
        var unknown = assertThrows(SecurityException.class,
                () -> administration.changeStatus(local.tenant(), change(ACTIVE, null, item(UUID.randomUUID(), 0))));
        assertEquals(unknown.getMessage(), denied.getMessage());
        assertFalse(denied.getMessage().contains(foreign.toString()));
        assertState(local, own, DRAFT, 0, false, false);
        assertState(other, foreign, DRAFT, 0, false, false);
        assertEquals(0, auditCount(local));
        assertEquals(0, auditCount(other));
    }

    @Test
    void deniesAnActorWithThePlatformRoleAndNoStatusPermission() throws SQLException {
        var fixture = fixture("PRODUCT_UPDATE");
        UUID product = product(fixture, "SIMPLE", null, DRAFT, false, false);
        assertDenied(fixture, product);
    }

    @Test
    void deniesSuspendedTenantsAndInactiveMembershipsOrAccounts() throws SQLException {
        var fixture = fixture(PERMISSION);
        UUID product = product(fixture, "SIMPLE", null, DRAFT, false, false);
        execute("UPDATE tenant SET status='SUSPENDED' WHERE tenant_id=?", fixture.tenant());
        assertDenied(fixture, product);

        execute("UPDATE tenant SET status='ACTIVE' WHERE tenant_id=?", fixture.tenant());
        execute("UPDATE tenant_membership SET status='SUSPENDED' WHERE tenant_id=? AND membership_id=?",
                fixture.tenant(), fixture.membership());
        assertDenied(fixture, product);

        execute("UPDATE tenant_membership SET status='ACTIVE' WHERE tenant_id=? AND membership_id=?",
                fixture.tenant(), fixture.membership());
        execute("UPDATE user_account SET status='DISABLED' WHERE user_id=?", fixture.actor());
        assertDenied(fixture, product);
    }

    @Test
    void rollsBackTheWholeBatchIfTheSecondAuditInsertFails() throws SQLException {
        var fixture = fixture(PERMISSION);
        UUID first = product(fixture, "SIMPLE", null, ACTIVE, true, true);
        UUID second = product(fixture, "SIMPLE", null, ACTIVE, true, true);
        UUID last = first.compareTo(second) > 0 ? first : second;
        String constraint = "reject_status_audit_" + last.toString().replace("-", "");
        execute("ALTER TABLE audit_event ADD CONSTRAINT " + constraint
                + " CHECK (entity_id <> '" + last + "' OR action <> 'PRODUCT_STATUS_CHANGED')");
        try {
            var error = assertThrows(IllegalStateException.class,
                    () -> administration.changeStatus(fixture.tenant(),
                            change(INACTIVE, "Prueba de rollback", item(first, 0), item(second, 0))));
            var sqlError = assertInstanceOf(SQLException.class, error.getCause());
            assertEquals("23514", sqlError.getSQLState());
            assertTrue(sqlError.getMessage().contains(constraint));
            assertState(fixture, first, ACTIVE, 0, true, true);
            assertState(fixture, second, ACTIVE, 0, true, true);
            assertEquals(0, auditCount(fixture));
        } finally {
            execute("ALTER TABLE audit_event DROP CONSTRAINT " + constraint);
        }
    }

    private void assertDenied(Fixture fixture, UUID product) throws SQLException {
        assertThrows(SecurityException.class,
                () -> administration.changeStatus(fixture.tenant(), change(ACTIVE, null, item(product, 0))));
        assertState(fixture, product, DRAFT, 0, false, false);
        assertEquals(0, auditCount(fixture));
    }

    private void assertState(Fixture fixture, UUID product, Product.Status state, long version, boolean published,
                             boolean everPublished) throws SQLException {
        assertEquals(1, count("""
                SELECT count(*) FROM product WHERE tenant_id=? AND product_id=? AND status=? AND version=?
                  AND published=? AND ever_published=?
                """, fixture.tenant(), product, state.name(), version, published, everPublished));
    }

    private int auditCount(Fixture fixture) throws SQLException {
        return count("SELECT count(*) FROM audit_event WHERE tenant_id=? AND action='PRODUCT_STATUS_CHANGED'", fixture.tenant());
    }

    private Fixture fixture(String permission) throws SQLException {
        UUID tenant = tenant();
        UUID category = category(tenant);
        UUID actor = UUID.randomUUID();
        UUID membership = UUID.randomUUID();
        UUID role = UUID.randomUUID();
        var actorId = new ActorId(ISSUER, actor.toString());
        execute("INSERT INTO user_account (user_id,oidc_issuer,oidc_subject) VALUES (?,?,?)",
                actor, actorId.issuer(), actorId.subject());
        execute("INSERT INTO tenant_membership (tenant_id,membership_id,user_id) VALUES (?,?,?)", tenant, membership, actor);
        execute("INSERT INTO app_role (tenant_id,role_id,code,name) VALUES (?,?,'CATALOG_TEST','Catalog test')", tenant, role);
        execute("INSERT INTO role_permission (tenant_id,role_id,permission_code) VALUES (?,?,?)", tenant, role, permission);
        execute("INSERT INTO membership_role (tenant_id,membership_id,role_id) VALUES (?,?,?)", tenant, membership, role);
        fixtureActor.identifyAs(actorId);
        return new Fixture(tenant, category, actor, membership);
    }

    private UUID tenant() throws SQLException {
        UUID id = UUID.randomUUID();
        execute("INSERT INTO tenant (tenant_id,slug,legal_name,display_name) VALUES (?,?,'Catalog test','Catalog test')",
                id, "catalog-" + id);
        return id;
    }

    private UUID category(UUID tenant) throws SQLException {
        UUID id = UUID.randomUUID();
        execute("INSERT INTO category (tenant_id,category_id,code,name) VALUES (?,?,'TEST','Catalog test')", tenant, id);
        return id;
    }

    private UUID product(Fixture fixture, String type, UUID parent, Product.Status state, boolean published,
                         boolean everPublished) throws SQLException {
        UUID id = UUID.randomUUID();
        String attributes = parent == null ? null : "{\"color\":\"negro\",\"size\":\"" + id + "\"}";
        execute("""
                INSERT INTO product (tenant_id,category_id,product_id,sku,name,structure_type,parent_id,
                                     variant_attributes,status,published,ever_published,updated_at)
                VALUES (?,?,?,?,'Product test',?,?,CAST(? AS jsonb),?,?,?,?)
                """, fixture.tenant(), fixture.category(), id, "VARIABLE".equals(type) ? null : id.toString(), type, parent,
                attributes, state.name(), published, everPublished, SEED_TIMESTAMP);
        return id;
    }

    private void execute(String sql, Object... parameters) throws SQLException {
        try (var connection = dataSource.getConnection(); var statement = prepare(connection, sql, parameters)) {
            statement.executeUpdate();
        }
    }

    private int count(String sql, Object... parameters) throws SQLException {
        try (var connection = dataSource.getConnection(); var statement = prepare(connection, sql, parameters);
                var result = statement.executeQuery()) {
            assertTrue(result.next());
            return result.getInt(1);
        }
    }

    private static PreparedStatement prepare(Connection connection, String sql, Object... parameters) throws SQLException {
        var statement = connection.prepareStatement(sql);
        for (int index = 0; index < parameters.length; index++) statement.setObject(index + 1, parameters[index]);
        return statement;
    }

    private static ProductStatusChange.Item item(UUID id, long version) {
        return new ProductStatusChange.Item(id, version);
    }

    private static ProductStatusChange change(Product.Status target, String reason, ProductStatusChange.Item... items) {
        return new ProductStatusChange(target, reason, List.of(items));
    }

    private record Fixture(UUID tenant, UUID category, UUID actor, UUID membership) { }

    public static class Profile implements QuarkusTestProfile {
        @Override
        public Set<Class<?>> getEnabledAlternatives() {
            return Set.of(FixtureActor.class);
        }
    }

    @Alternative
    @ApplicationScoped
    static class FixtureActor implements CurrentActor {
        private ActorId actorId;

        void identifyAs(ActorId actorId) { this.actorId = actorId; }
        @Override public Optional<ActorId> id() { return Optional.ofNullable(actorId); }
        @Override public boolean hasPlatformSuperAdminRole() { return true; }
    }
}
