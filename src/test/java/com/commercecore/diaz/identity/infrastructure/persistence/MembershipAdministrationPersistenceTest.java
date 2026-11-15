package com.commercecore.diaz.identity.infrastructure.persistence;

import com.commercecore.diaz.audit.application.TenantAuditRecorder;
import com.commercecore.diaz.identity.application.*;
import com.commercecore.diaz.identity.application.port.MembershipStore;
import com.commercecore.diaz.identity.application.port.TenantAdministrationLock;
import com.commercecore.diaz.identity.application.port.TenantPermissionLookup;
import com.commercecore.diaz.identity.domain.ActorId;
import com.commercecore.diaz.identity.domain.Membership;
import com.commercecore.diaz.tenant.application.TenantStatusQuery;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import com.commercecore.diaz.tenant.application.port.TenantRepository;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class MembershipAdministrationPersistenceTest {
    @Inject DataSource db;
    @Inject MembershipStore store;
    @Inject TenantPermissionLookup permissions;
    @Inject TenantStatusQuery tenants;
    @Inject TenantAdministrationLock lock;
    @Inject TenantAuditRecorder audit;
    @Inject TenantRepository tenantRepository;
    @Inject EntityManager entityManager;

    record Fixture(UUID tenant, UUID admin, UUID adminMembership, UUID user, ActorId actor, ActorId invited) {}

    @Test @TestTransaction
    void invitationAcceptanceSuspensionAndReactivationAreAuditedAndIsolated() {
        Fixture f = fixture(); var admin = service(f.actor(), audit, tenants);
        var invited = admin.invite(f.tenant(), f.user());
        assertEquals(Membership.Status.INVITED, invited.status());
        assertEquals(0, count("SELECT count(*) FROM membership_role WHERE membership_id=?", invited.id()));
        assertThrows(MembershipConflictException.class, () -> admin.invite(f.tenant(), f.user()));
        assertThrows(SecurityException.class, () -> admin.accept(f.tenant(), invited.id()));
        var recipient = service(f.invited(), audit, tenants);
        assertEquals(Membership.Status.ACTIVE, recipient.accept(f.tenant(), invited.id()).status());
        assertThrows(MembershipConflictException.class, () -> recipient.accept(f.tenant(), invited.id()));
        assertThrows(SecurityException.class, () -> recipient.list(f.tenant(), 0, 10));
        execute("INSERT INTO membership_role(tenant_id,membership_id,role_id) SELECT ?,?,role_id FROM app_role WHERE tenant_id=? AND code='manager'", f.tenant(), invited.id(), f.tenant());
        assertEquals(TenantPermissionDecision.ALLOW, authorization(f.invited()).hasPermission(f.tenant(), "USER_MANAGE"));
        assertEquals(Membership.Status.SUSPENDED, admin.suspend(f.tenant(), invited.id()).status());
        assertEquals(TenantPermissionDecision.DENY, authorization(f.invited()).hasPermission(f.tenant(), "USER_MANAGE"));
        assertEquals(Membership.Status.ACTIVE, admin.reactivate(f.tenant(), invited.id()).status());
        assertEquals(4, count("SELECT count(*) FROM audit_event WHERE entity_id=?", invited.id()));
        assertEquals(1, admin.list(f.tenant(), 1, 1).size());
        assertThrows(SecurityException.class, () -> admin.suspend(f.tenant(), f.adminMembership()));
        UUID other = createTenant("ACTIVE");
        assertThrows(SecurityException.class, () -> admin.list(other, 0, 10));
        assertThrows(SecurityException.class, () -> admin.suspend(other, invited.id()));
    }

    @Test @TestTransaction
    void disabledMissingAndProtectedAccountsAreDenied() {
        Fixture f = fixture(); var admin = service(f.actor(), audit, tenants);
        assertThrows(MembershipConflictException.class, () -> admin.invite(f.tenant(), UUID.randomUUID()));
        execute("UPDATE user_account SET status='DISABLED' WHERE user_id=?", f.user());
        assertThrows(MembershipConflictException.class, () -> admin.invite(f.tenant(), f.user()));
        execute("UPDATE user_account SET status='ACTIVE' WHERE user_id=?", f.user());
        var member = admin.invite(f.tenant(), f.user());
        execute("INSERT INTO app_role(tenant_id,role_id,code,name) VALUES (?,?,'TENANT_ADMIN','Admin')", f.tenant(), UUID.randomUUID());
        execute("INSERT INTO membership_role(tenant_id,membership_id,role_id) SELECT ?,?,role_id FROM app_role WHERE tenant_id=? AND code='TENANT_ADMIN'", f.tenant(), member.id(), f.tenant());
        assertThrows(SecurityException.class, () -> service(f.invited(), audit, tenants).accept(f.tenant(), member.id()));
        execute("UPDATE tenant_membership SET status='ACTIVE' WHERE membership_id=?", member.id());
        assertThrows(SecurityException.class, () -> admin.suspend(f.tenant(), member.id()));
    }

    @Test @TestTransaction
    void tenantStatesAndFailuresDeny() {
        Fixture f = fixture(); var admin = service(f.actor(), audit, tenants);
        for (String state : new String[]{"SUSPENDED", "CLOSED"}) {
            execute("UPDATE tenant SET status=? WHERE tenant_id=?", state, f.tenant());
            assertThrows(SecurityException.class, () -> admin.invite(f.tenant(), f.user()));
        }
        assertThrows(SecurityException.class, () -> admin.invite(UUID.randomUUID(), f.user()));
        execute("UPDATE tenant SET status='ACTIVE' WHERE tenant_id=?", f.tenant());
        var broken = service(f.actor(), audit, id -> { throw new IllegalStateException("tenant unavailable"); });
        assertThrows(IllegalStateException.class, () -> broken.invite(f.tenant(), f.user()));
        assertEquals(0, count("SELECT count(*) FROM tenant_membership WHERE user_id=?", f.user()));
    }

    @Test @TestTransaction
    void tenantStatusIsReadAgainEvenWhenEntityIsAlreadyManaged() {
        Fixture f = fixture();
        assertEquals(com.commercecore.diaz.tenant.domain.TenantStatus.ACTIVE,
                tenantRepository.findByTenantId(f.tenant()).orElseThrow().status());
        execute("UPDATE tenant SET status='SUSPENDED' WHERE tenant_id=?", f.tenant());
        assertEquals(TenantStatusQuery.Result.SUSPENDED, tenants.statusOf(f.tenant()));
        assertThrows(SecurityException.class, () -> service(f.actor(), audit, tenants).invite(f.tenant(), f.user()));
        entityManager.clear();
    }

    @Test @TestTransaction
    void paginationIsStableBoundedAndTenantScoped() {
        Fixture f = fixture(); var admin = service(f.actor(), audit, tenants);
        UUID second = UUID.randomUUID();
        execute("INSERT INTO user_account(user_id,oidc_issuer,oidc_subject) VALUES (?,?,?)", second,
                "https://issuer.test", UUID.randomUUID().toString());
        var firstMember = admin.invite(f.tenant(), f.user());
        var secondMember = admin.invite(f.tenant(), second);
        UUID otherTenant = createTenant("ACTIVE");
        execute("INSERT INTO tenant_membership(tenant_id,user_id,status) VALUES (?,?,'ACTIVE')", otherTenant, f.user());
        execute("UPDATE tenant_membership SET created_at='2020-01-01T00:00:00Z' WHERE tenant_id=?", f.tenant());
        var all = admin.list(f.tenant(), 0, 100);
        assertEquals(3, all.size());
        assertEquals(all.get(0), admin.list(f.tenant(), 0, 1).get(0));
        assertEquals(all.get(1), admin.list(f.tenant(), 1, 1).get(0));
        assertEquals(all.get(2), admin.list(f.tenant(), 2, 1).get(0));
        assertTrue(all.stream().allMatch(member -> member.tenantId().equals(f.tenant())));
        assertTrue(all.stream().anyMatch(member -> member.id().equals(firstMember.id())));
        assertTrue(all.stream().anyMatch(member -> member.id().equals(secondMember.id())));
        assertThrows(IllegalArgumentException.class, () -> admin.list(f.tenant(), -1, 1));
        assertThrows(IllegalArgumentException.class, () -> admin.list(f.tenant(), 0, 101));
    }

    @Test
    void auditFailureRollsBackInvitation() {
        Fixture f = QuarkusTransaction.requiringNew().call(this::fixture);
        assertThrows(RuntimeException.class, () -> QuarkusTransaction.requiringNew().run(() ->
                service(f.actor(), event -> { throw new IllegalStateException("audit failure"); }, tenants).invite(f.tenant(), f.user())));
        assertEquals(0, QuarkusTransaction.requiringNew().call(() -> count("SELECT count(*) FROM tenant_membership WHERE user_id=?", f.user())));
    }

    @Test
    void concurrentInvitationsYieldOneMembershipAndOneAudit() throws Exception {
        Fixture f = QuarkusTransaction.requiringNew().call(this::fixture);
        var start = new CountDownLatch(1); var pool = Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Callable<Boolean> call = () -> {
                start.await();
                try { QuarkusTransaction.requiringNew().call(() -> service(f.actor(), audit, tenants).invite(f.tenant(), f.user())); return true; }
                catch (MembershipConflictException expected) { return false; }
            };
            var a = pool.submit(call); var b = pool.submit(call); start.countDown();
            assertNotEquals(a.get(), b.get());
            assertEquals(1, QuarkusTransaction.requiringNew().call(() -> count("SELECT count(*) FROM tenant_membership WHERE user_id=?", f.user())));
            assertEquals(1, QuarkusTransaction.requiringNew().call(() -> count("SELECT count(*) FROM audit_event WHERE tenant_id=? AND action='MEMBERSHIP_INVITED'", f.tenant())));
        } finally { pool.shutdownNow(); }
    }

    @Test
    void listingDoesNotWaitForExclusiveTenantAdministrationLock() throws Exception {
        Fixture f = QuarkusTransaction.requiringNew().call(this::fixture);
        var locked = new CountDownLatch(1); var release = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var writer = pool.submit(() -> QuarkusTransaction.requiringNew().run(() -> {
                lock.acquireAll(java.util.List.of(f.tenant()));
                locked.countDown();
                try { if (!release.await(10, java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("timeout"); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
            }));
            assertTrue(locked.await(2, java.util.concurrent.TimeUnit.SECONDS));
            var listing = pool.submit(() -> QuarkusTransaction.requiringNew().call(
                    () -> service(f.actor(), audit, tenants).list(f.tenant(), 0, 10)));
            assertEquals(1, listing.get(2, java.util.concurrent.TimeUnit.SECONDS).size());
            release.countDown(); writer.get();
        } finally { release.countDown(); pool.shutdownNow(); }
    }

    @Test
    void permissionIsRecheckedAfterWaitingForTenantLock() throws Exception {
        Fixture f = QuarkusTransaction.requiringNew().call(this::fixture);
        var locked = new CountDownLatch(1); var release = new CountDownLatch(1);
        var attempting = new CountDownLatch(1); var pool = Executors.newFixedThreadPool(2);
        try {
            var revoke = pool.submit(() -> QuarkusTransaction.requiringNew().run(() -> {
                lock.acquireAll(java.util.List.of(f.tenant()));
                execute("DELETE FROM membership_role WHERE tenant_id=? AND membership_id=?", f.tenant(), f.adminMembership());
                locked.countDown();
                try { if (!release.await(10, java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("timeout"); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
            }));
            assertTrue(locked.await(2, java.util.concurrent.TimeUnit.SECONDS));
            var invite = pool.submit(() -> {
                attempting.countDown();
                return assertThrows(SecurityException.class, () -> QuarkusTransaction.requiringNew().run(
                        () -> service(f.actor(), audit, tenants).invite(f.tenant(), f.user())));
            });
            assertTrue(attempting.await(2, java.util.concurrent.TimeUnit.SECONDS));
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2);
            while (count("SELECT count(*) FROM pg_locks WHERE locktype='advisory' AND NOT granted") == 0
                    && System.nanoTime() < deadline) Thread.sleep(20);
            assertTrue(count("SELECT count(*) FROM pg_locks WHERE locktype='advisory' AND NOT granted") > 0,
                    "invitation must wait for the tenant lock");
            release.countDown(); revoke.get(); invite.get();
            assertEquals(0, QuarkusTransaction.requiringNew().call(() -> count("SELECT count(*) FROM tenant_membership WHERE user_id=?", f.user())));
        } finally { release.countDown(); pool.shutdownNow(); }
    }

    private MembershipAdministrationService service(ActorId id, TenantAuditRecorder recorder, TenantStatusQuery query) {
        CurrentActor current = currentActor(id);
        return new MembershipAdministrationService(current, new TenantAuthorizationService(current, permissions), query, lock, store, recorder);
    }

    private TenantAuthorization authorization(ActorId id) {
        return new TenantAuthorizationService(currentActor(id), permissions);
    }

    private CurrentActor currentActor(ActorId id) {
        CurrentActor current = new CurrentActor() {
            public Optional<ActorId> id() { return Optional.of(id); }
            public boolean hasPlatformSuperAdminRole() { return false; }
        };
        return current;
    }

    private Fixture fixture() {
        UUID tenant = createTenant("ACTIVE"), admin = UUID.randomUUID(), user = UUID.randomUUID();
        UUID member = UUID.randomUUID(), role = UUID.randomUUID();
        ActorId a = new ActorId("https://issuer.test", UUID.randomUUID().toString());
        ActorId b = new ActorId("https://issuer.test", UUID.randomUUID().toString());
        execute("INSERT INTO user_account(user_id,oidc_issuer,oidc_subject) VALUES (?,?,?)", admin, a.issuer(), a.subject());
        execute("INSERT INTO user_account(user_id,oidc_issuer,oidc_subject) VALUES (?,?,?)", user, b.issuer(), b.subject());
        execute("INSERT INTO tenant_membership(membership_id,tenant_id,user_id,status) VALUES (?,?,?,'ACTIVE')", member, tenant, admin);
        execute("INSERT INTO app_role(role_id,tenant_id,code,name) VALUES (?,?,'manager','Manager')", role, tenant);
        execute("INSERT INTO role_permission(tenant_id,role_id,permission_code) VALUES (?,?,'USER_MANAGE')", tenant, role);
        execute("INSERT INTO membership_role(tenant_id,membership_id,role_id) VALUES (?,?,?)", tenant, member, role);
        return new Fixture(tenant, admin, member, user, a, b);
    }

    private UUID createTenant(String status) {
        UUID id = UUID.randomUUID();
        execute("INSERT INTO tenant(tenant_id,slug,legal_name,display_name,status) VALUES (?,?,?,?,?)", id, "test-" + id, "Test", "Test", status);
        return id;
    }
    private void execute(String sql, Object... args) {
        try (var c = db.getConnection(); var s = c.prepareStatement(sql)) {
            for (int i=0; i<args.length; i++) s.setObject(i+1,args[i]); s.executeUpdate();
        } catch (SQLException e) { throw new IllegalStateException(e); }
    }
    private int count(String sql, Object... args) {
        try (var c = db.getConnection(); var s = c.prepareStatement(sql)) {
            for (int i=0; i<args.length; i++) s.setObject(i+1,args[i]);
            try (var r = s.executeQuery()) { r.next(); return r.getInt(1); }
        } catch (SQLException e) { throw new IllegalStateException(e); }
    }
}
