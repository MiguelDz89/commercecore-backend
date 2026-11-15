package com.commercecore.diaz.identity.infrastructure.persistence;

import com.commercecore.diaz.identity.application.TenantAdministrationLockException;
import com.commercecore.diaz.identity.application.port.TenantAdministrationLock;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class TenantAdministrationLockTest {
    @Inject TenantAdministrationLock lock;
    @Inject DataSource dataSource;

    @Test
    void concurrentLastAdminRemovalSimulationLeavesOneActive() throws Exception {
        Setup setup = QuarkusTransaction.requiringNew().call(this::setup);
        var executor = Executors.newFixedThreadPool(2);
        var start = new CountDownLatch(1);
        try {
            var first = executor.submit(() -> removeIfAnotherAdminExists(setup, setup.firstMembership, start));
            var second = executor.submit(() -> removeIfAnotherAdminExists(setup, setup.secondMembership, start));
            start.countDown();
            boolean a = first.get(15, TimeUnit.SECONDS);
            boolean b = second.get(15, TimeUnit.SECONDS);
            assertNotEquals(a, b, "Only one simulated removal may commit");
            assertEquals(1, QuarkusTransaction.requiringNew().call(() -> adminCount(setup.tenant)));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void lockTimeoutRollsBackCallerTransaction() throws Exception {
        UUID tenant = UUID.randomUUID();
        String subject = "lock-test-" + UUID.randomUUID();
        var executor = Executors.newSingleThreadExecutor();
        var held = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try {
            var owner = executor.submit(() -> QuarkusTransaction.requiringNew().run(() -> {
                lock.acquireAll(List.of(tenant));
                held.countDown();
                await(release);
            }));
            assertTrue(held.await(10, TimeUnit.SECONDS));
            TenantAdministrationLockException failure = assertThrows(TenantAdministrationLockException.class,
                    () -> QuarkusTransaction.requiringNew().run(() -> {
                        try {
                            exec("INSERT INTO user_account (oidc_issuer, oidc_subject) VALUES (?, ?)", "https://lock.example", subject);
                        } catch (SQLException e) { throw new IllegalStateException(e); }
                        lock.acquireAll(List.of(tenant));
                    }));
            assertEquals(TenantAdministrationLockException.Reason.TIMEOUT, failure.reason());
            release.countDown();
            owner.get(10, TimeUnit.SECONDS);
            assertEquals(0, QuarkusTransaction.requiringNew().call(() -> count(
                    "SELECT count(*) FROM user_account WHERE oidc_issuer = ? AND oidc_subject = ?", "https://lock.example", subject)));
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void multipleTenantsAreLockedInStableOrder() throws Exception {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        var executor = Executors.newFixedThreadPool(2);
        var start = new CountDownLatch(1);
        try {
            var first = executor.submit(() -> { await(start); QuarkusTransaction.requiringNew().run(() -> lock.acquireAll(List.of(a, b))); });
            var second = executor.submit(() -> { await(start); QuarkusTransaction.requiringNew().run(() -> lock.acquireAll(List.of(b, a))); });
            start.countDown();
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void deadlockIsReportedAndTheVictimTransactionRollsBack() throws Exception {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        String subjectA = "deadlock-" + UUID.randomUUID();
        String subjectB = "deadlock-" + UUID.randomUUID();
        var bothHoldFirstLock = new CountDownLatch(2);
        var executor = Executors.newFixedThreadPool(2);
        try {
            // Deliberately violate the one-call ordering protocol to exercise PostgreSQL's deadlock handling.
            var first = executor.submit(() -> deadlockParticipant(a, b, subjectA, bothHoldFirstLock));
            var second = executor.submit(() -> deadlockParticipant(b, a, subjectB, bothHoldFirstLock));
            String firstResult = first.get(15, TimeUnit.SECONDS);
            String secondResult = second.get(15, TimeUnit.SECONDS);
            var results = List.of(firstResult, secondResult);
            assertTrue(results.contains("DEADLOCK"));
            assertTrue(results.contains("ACQUIRED"));
            assertEquals(firstResult.equals("DEADLOCK") ? 0 : 1, QuarkusTransaction.requiringNew().call(() -> count(
                    "SELECT count(*) FROM user_account WHERE oidc_issuer = ? AND oidc_subject = ?", "https://lock.example", subjectA)));
            assertEquals(secondResult.equals("DEADLOCK") ? 0 : 1, QuarkusTransaction.requiringNew().call(() -> count(
                    "SELECT count(*) FROM user_account WHERE oidc_issuer = ? AND oidc_subject = ?", "https://lock.example", subjectB)));
        } finally {
            executor.shutdownNow();
        }
    }

    private String deadlockParticipant(UUID first, UUID second, String subject, CountDownLatch bothHoldFirstLock) {
        try {
            return QuarkusTransaction.requiringNew().call(() -> {
                lock.acquireAll(List.of(first));
                exec("INSERT INTO user_account (oidc_issuer, oidc_subject) VALUES (?, ?)", "https://lock.example", subject);
                bothHoldFirstLock.countDown();
                await(bothHoldFirstLock);
                lock.acquireAll(List.of(second));
                return "ACQUIRED";
            });
        } catch (TenantAdministrationLockException e) {
            return e.reason().name();
        }
    }

    private boolean removeIfAnotherAdminExists(Setup setup, UUID membership, CountDownLatch start) {
        await(start);
        return QuarkusTransaction.requiringNew().call(() -> {
            lock.acquireAll(List.of(setup.tenant));
            if (adminCount(setup.tenant) <= 1) return false;
            exec("DELETE FROM membership_role WHERE tenant_id = ? AND membership_id = ? AND role_id = ?",
                    setup.tenant, membership, setup.role);
            return true;
        });
    }

    private Setup setup() throws SQLException {
        UUID tenant = UUID.randomUUID();
        UUID role = UUID.randomUUID();
        UUID userA = UUID.randomUUID();
        UUID userB = UUID.randomUUID();
        UUID membershipA = UUID.randomUUID();
        UUID membershipB = UUID.randomUUID();
        exec("INSERT INTO tenant (tenant_id, slug, legal_name, display_name) VALUES (?, ?, 'Legal', 'Display')", tenant, "lock-" + tenant);
        exec("INSERT INTO app_role (tenant_id, role_id, code, name) VALUES (?, ?, 'TENANT_ADMIN', 'Admin')", tenant, role);
        for (String permission : List.of("TENANT_READ", "TENANT_MANAGE", "USER_MANAGE")) {
            exec("INSERT INTO role_permission (tenant_id, role_id, permission_code) VALUES (?, ?, ?)", tenant, role, permission);
        }
        for (UUID user : List.of(userA, userB)) {
            exec("INSERT INTO user_account (user_id, oidc_issuer, oidc_subject) VALUES (?, 'https://lock.example', ?)", user, user.toString());
        }
        exec("INSERT INTO tenant_membership (tenant_id, membership_id, user_id, status) VALUES (?, ?, ?, 'ACTIVE')", tenant, membershipA, userA);
        exec("INSERT INTO tenant_membership (tenant_id, membership_id, user_id, status) VALUES (?, ?, ?, 'ACTIVE')", tenant, membershipB, userB);
        exec("INSERT INTO membership_role (tenant_id, membership_id, role_id) VALUES (?, ?, ?)", tenant, membershipA, role);
        exec("INSERT INTO membership_role (tenant_id, membership_id, role_id) VALUES (?, ?, ?)", tenant, membershipB, role);
        return new Setup(tenant, role, membershipA, membershipB);
    }

    private int adminCount(UUID tenant) throws SQLException {
        return count("""
                SELECT count(DISTINCT m.membership_id) FROM tenant_membership m
                JOIN user_account u ON u.user_id = m.user_id
                JOIN membership_role mr ON mr.tenant_id = m.tenant_id AND mr.membership_id = m.membership_id
                JOIN app_role r ON r.tenant_id = mr.tenant_id AND r.role_id = mr.role_id
                WHERE m.tenant_id = ? AND m.status = 'ACTIVE' AND u.status = 'ACTIVE' AND r.code = 'TENANT_ADMIN'
                """, tenant);
    }

    private int count(String sql, Object... values) throws SQLException {
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++) statement.setObject(i + 1, values[i]);
            try (var rows = statement.executeQuery()) { rows.next(); return rows.getInt(1); }
        }
    }

    private void exec(String sql, Object... values) throws SQLException {
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++) statement.setObject(i + 1, values[i]);
            statement.executeUpdate();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Test synchronization timed out");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private record Setup(UUID tenant, UUID role, UUID firstMembership, UUID secondMembership) { }
}
