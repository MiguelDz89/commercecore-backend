package com.commercecore.diaz.tenant.infrastructure.persistence;

import com.commercecore.diaz.tenant.application.port.TenantRepository;
import com.commercecore.diaz.tenant.domain.Tenant;
import com.commercecore.diaz.tenant.domain.TenantStatus;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.sql.SQLException;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class TenantPersistenceTest {
    @Inject TenantRepository repository;
    @Inject TenantPanacheStore store;
    @Inject TenantPersistenceMapper mapper;
    @Inject DataSource dataSource;
    @Inject EntityManager entityManager;

    @Test
    @TestTransaction
    void createsAndFindsByIdAndSlugWithAllFields() {
        Tenant tenant = Tenant.create(slug(), "Empresa Legal", "Empresa", "PEN", "America/Lima");
        insertForPersistenceTest(tenant);
        assertEquals(tenant, repository.findByTenantId(tenant.tenantId()).orElseThrow());
        assertEquals(tenant, repository.findBySlug(tenant.slug()).orElseThrow());
        assertTrue(repository.findByTenantId(UUID.randomUUID()).isEmpty());
        assertTrue(repository.findBySlug(slug()).isEmpty());
    }

    @Test
    void mapsAllStatesWithoutLeakingJpaIntoDomain() {
        Tenant original = Tenant.create(slug(), "Legal", "Comercial", "USD", "UTC");
        for (TenantStatus status : TenantStatus.values()) {
            Tenant value = new Tenant(original.tenantId(), original.slug(), original.legalName(), original.displayName(),
                    original.currencyCode(), original.timeZone(), status, original.createdAt(), original.updatedAt());
            assertEquals(value, mapper.toDomain(mapper.toEntity(value)));
        }
    }

    @Test
    @TestTransaction
    void readsExistingDatabaseStatesAsEnum() {
        Tenant tenant = Tenant.create(slug(), "Legal", "Comercial", "PEN", "UTC");
        insertForPersistenceTest(tenant);
        for (TenantStatus status : TenantStatus.values()) {
            entityManager.createNativeQuery("UPDATE tenant SET status = ?1 WHERE tenant_id = ?2")
                    .setParameter(1, status.name()).setParameter(2, tenant.tenantId()).executeUpdate();
            entityManager.clear();
            assertEquals(status, repository.findByTenantId(tenant.tenantId()).orElseThrow().status());
        }
    }

    @Test
    @TestTransaction
    void databaseEnforcesUniqueSlug() {
        String slug = slug();
        insertForPersistenceTest(Tenant.create(slug, "Legal uno", "Uno", "PEN", "UTC"));
        RuntimeException failure = assertThrows(RuntimeException.class,
                () -> insertForPersistenceTest(Tenant.create(slug, "Legal dos", "Dos", "PEN", "UTC")));
        assertTrue(hasSqlState(failure, "23505"), "PostgreSQL should report unique_violation");
    }

    @Test
    @TestTransaction
    void databaseDefaultsAndFlywayMigrationsMatchSchema() throws Exception {
        UUID id;
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(
                     "INSERT INTO tenant (slug, legal_name, display_name) VALUES (?, ?, ?) RETURNING tenant_id, currency_code, time_zone, status, created_at, updated_at")) {
            statement.setString(1, slug());
            statement.setString(2, "Legal");
            statement.setString(3, "Nombre");
            try (var row = statement.executeQuery()) {
                assertTrue(row.next());
                id = row.getObject("tenant_id", UUID.class);
                assertEquals("PEN", row.getString("currency_code"));
                assertEquals("UTC", row.getString("time_zone"));
                assertEquals("ACTIVE", row.getString("status"));
                assertNotNull(row.getObject("created_at"));
                assertNotNull(row.getObject("updated_at"));
            }
            try (var query = connection.prepareStatement(
                    "SELECT count(*) FROM flyway_schema_history WHERE version IN ('1','2','3') AND success = true");
                 var rows = query.executeQuery()) {
                assertTrue(rows.next());
                assertEquals(3, rows.getInt(1));
            }
        }
        assertEquals(TenantStatus.ACTIVE, repository.findByTenantId(id).orElseThrow().status());
    }

    private void insertForPersistenceTest(Tenant tenant) {
        store.persistAndFlush(mapper.toEntity(tenant));
    }

    private static String slug() {
        return "test-" + UUID.randomUUID();
    }

    private static boolean hasSqlState(Throwable error, String state) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && state.equals(sql.getSQLState())) return true;
        }
        return false;
    }
}
