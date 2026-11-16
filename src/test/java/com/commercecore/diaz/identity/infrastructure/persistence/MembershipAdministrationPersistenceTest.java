package com.commercecore.diaz.identity.infrastructure.persistence;

import com.commercecore.diaz.audit.application.TenantAuditRecorder;
import com.commercecore.diaz.identity.application.*;
import com.commercecore.diaz.identity.application.port.MembershipStore;
import com.commercecore.diaz.identity.application.port.TenantAdministrationLock;
import com.commercecore.diaz.identity.application.port.TenantPermissionLookup;
import com.commercecore.diaz.identity.application.port.TenantRoleStore;
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
    @Inject TenantRoleStore roles;
    @Inject TenantRepository tenantRepository;
    @Inject EntityManager entityManager;

    record Fixture(UUID tenant, UUID admin, UUID adminMembership, UUID user, ActorId actor, ActorId invited) {}

    @Test @TestTransaction
    void pagedRoleLoadingUsesOneQueryRegardlessOfPageSize() {
        Fixture f=fixture();
        for(int i=0;i<4;i++) {
            UUID role=UUID.randomUUID();
            execute("INSERT INTO app_role(role_id,tenant_id,code,name) VALUES (?,?,?,?)",role,f.tenant(),"batch-"+i,"Batch");
            execute("INSERT INTO role_permission(tenant_id,role_id,permission_code) VALUES (?,?,'PRODUCT_READ')",f.tenant(),role);
        }
        var count=new java.util.concurrent.atomic.AtomicInteger();
        DataSource monitored=(DataSource)java.lang.reflect.Proxy.newProxyInstance(DataSource.class.getClassLoader(),new Class<?>[]{DataSource.class},
            (proxy,method,args) -> {
                try {
                    Object value=method.invoke(db,args);
                    if(method.getName().equals("getConnection")) {
                        var connection=(java.sql.Connection)value;
                        return java.lang.reflect.Proxy.newProxyInstance(java.sql.Connection.class.getClassLoader(),new Class<?>[]{java.sql.Connection.class},
                            (cp,cm,ca) -> { try { if(cm.getName().equals("prepareStatement")) count.incrementAndGet(); return cm.invoke(connection,ca); }
                                catch(java.lang.reflect.InvocationTargetException ex) { throw ex.getCause(); } });
                    }
                    return value;
                } catch(java.lang.reflect.InvocationTargetException ex) { throw ex.getCause(); }
            });
        var jdbc=new JdbcTenantRoleStore(monitored);
        var first=jdbc.listPage(f.tenant(),0,1); assertEquals(1,first.size()); assertEquals(1,count.get());
        count.set(0);
        var page=jdbc.listPage(f.tenant(),0,4); assertEquals(4,page.size()); assertEquals(1,count.get());
        assertTrue(page.stream().allMatch(role -> role.permissions().contains("PRODUCT_READ")));
        assertEquals(first.get(0),page.get(0));
    }

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
        UUID reader=UUID.randomUUID();
        execute("INSERT INTO app_role(role_id,tenant_id,code,name) VALUES (?,?,'reader','Reader')",reader,f.tenant());
        execute("INSERT INTO role_permission(tenant_id,role_id,permission_code) VALUES (?,?,'PRODUCT_READ')",f.tenant(),reader);
        execute("INSERT INTO membership_role(tenant_id,membership_id,role_id) VALUES (?,?,?)", f.tenant(), invited.id(), reader);
        assertEquals(TenantPermissionDecision.ALLOW, authorization(f.invited()).hasPermission(f.tenant(), "PRODUCT_READ"));
        assertEquals(Membership.Status.SUSPENDED, admin.suspend(f.tenant(), invited.id()).status());
        assertEquals(TenantPermissionDecision.DENY, authorization(f.invited()).hasPermission(f.tenant(), "PRODUCT_READ"));
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
        execute("INSERT INTO membership_role(tenant_id,membership_id,role_id) SELECT ?,?,role_id FROM app_role WHERE tenant_id=? AND code='TENANT_ADMIN'", f.tenant(), member.id(), f.tenant());
        assertThrows(SecurityException.class, () -> service(f.invited(), audit, tenants).accept(f.tenant(), member.id()));
        execute("UPDATE tenant_membership SET status='ACTIVE' WHERE membership_id=?", member.id());
        assertThrows(SecurityException.class, () -> admin.suspend(f.tenant(), member.id()));
    }

    @Test @TestTransaction
    void ordinaryRoleOperationsRespectScopesAndAudit() {
        Fixture f=fixture(); UUID role=UUID.randomUUID();
        execute("INSERT INTO app_role(role_id,tenant_id,code,name) VALUES (?,?,'reader','Reader')",role,f.tenant());
        execute("INSERT INTO role_permission(tenant_id,role_id,permission_code) VALUES (?,?,'PRODUCT_READ')",f.tenant(),role);
        UUID member=service(f.actor(),audit,tenants).invite(f.tenant(),f.user()).id();
        var admin=roleService(f.actor(),false);
        assertThrows(SecurityException.class, () -> admin.assign(f.tenant(),member,role));
        UUID scope=UUID.randomUUID();
        execute("INSERT INTO app_role(role_id,tenant_id,code,name) VALUES (?,?,'scope','Scope')",scope,f.tenant());
        execute("INSERT INTO role_permission(tenant_id,role_id,permission_code) VALUES (?,?,'PRODUCT_READ')",f.tenant(),scope);
        execute("INSERT INTO membership_role(tenant_id,membership_id,role_id) VALUES (?,?,?)",f.tenant(),f.adminMembership(),scope);
        admin.assign(f.tenant(),member,role);
        assertThrows(MembershipConflictException.class, () -> admin.assign(f.tenant(),member,role));
        assertEquals(1,admin.membershipRoles(f.tenant(),member).size());
        assertThrows(SecurityException.class, () -> admin.changePermissions(f.tenant(),role,java.util.Set.of("USER_MANAGE")));
        assertThrows(SecurityException.class, () -> admin.changePermissions(f.tenant(),role,java.util.Set.of("PRODUCT_UPDATE")));
        assertThrows(SecurityException.class, () -> admin.changePermissions(f.tenant(),role,java.util.Set.of("DOES_NOT_EXIST")));
        admin.revoke(f.tenant(),member,role);
        assertThrows(MembershipConflictException.class, () -> admin.revoke(f.tenant(),member,role));
        admin.changePermissions(f.tenant(),role,java.util.Set.of());
        assertEquals(3,count("SELECT count(*) FROM audit_event WHERE tenant_id=? AND action IN ('ROLE_ASSIGNED','ROLE_REVOKED','ROLE_PERMISSIONS_CHANGED')",f.tenant()));
        assertThrows(SecurityException.class, () -> admin.assign(f.tenant(),f.adminMembership(),role));
        execute("INSERT INTO membership_role(tenant_id,membership_id,role_id) VALUES (?,?,?) ON CONFLICT DO NOTHING",f.tenant(),f.adminMembership(),role);
        assertThrows(SecurityException.class, () -> admin.changePermissions(f.tenant(),role,java.util.Set.of("PRODUCT_READ")));
        UUID other=createTenant("ACTIVE");
        assertThrows(SecurityException.class, () -> admin.assign(other,member,role));
    }

    @Test @TestTransaction
    void platformAloneCanDesignateAndCannotRemoveLastActiveAdmin() {
        Fixture f=fixture(); UUID role=createProtectedRole(f.tenant());
        execute("INSERT INTO membership_role(tenant_id,membership_id,role_id) VALUES (?,?,?) ON CONFLICT DO NOTHING",f.tenant(),f.adminMembership(),role);
        UUID member=UUID.randomUUID();
        execute("INSERT INTO tenant_membership(membership_id,tenant_id,user_id,status) VALUES (?,?,?,'ACTIVE')",member,f.tenant(),f.user());
        assertThrows(SecurityException.class, () -> roleService(f.actor(),false).designateAdmin(f.tenant(),member));
        var platform=roleService(f.actor(),true);
        assertThrows(MembershipConflictException.class, () -> platform.removeAdmin(f.tenant(),f.adminMembership()));
        platform.designateAdmin(f.tenant(),member);
        platform.removeAdmin(f.tenant(),f.adminMembership());
        assertThrows(MembershipConflictException.class, () -> platform.removeAdmin(f.tenant(),member));
    }

    @Test @TestTransaction
    void delegationCannotUsePermissionFromAnInconsistentRole() {
        Fixture f=fixture(); UUID inconsistent=UUID.randomUUID(), target=UUID.randomUUID(), member=UUID.randomUUID();
        execute("INSERT INTO app_role(role_id,tenant_id,code,name) VALUES (?,?,'legacy','Legacy'),(?,?,'reader','Reader')",inconsistent,f.tenant(),target,f.tenant());
        execute("INSERT INTO role_permission(tenant_id,role_id,permission_code) VALUES (?,?,'USER_MANAGE'),(?,?,'PRODUCT_READ'),(?,?,'PRODUCT_READ')",f.tenant(),inconsistent,f.tenant(),inconsistent,f.tenant(),target);
        execute("INSERT INTO membership_role(tenant_id,membership_id,role_id) VALUES (?,?,?)",f.tenant(),f.adminMembership(),inconsistent);
        execute("INSERT INTO tenant_membership(membership_id,tenant_id,user_id,status) VALUES (?,?,?,'ACTIVE')",member,f.tenant(),f.user());
        assertThrows(AuthorizationIntegrityException.class, () -> roleService(f.actor(),false).assign(f.tenant(),member,target));
        assertEquals(0,count("SELECT count(*) FROM membership_role WHERE tenant_id=? AND membership_id=? AND role_id=?",f.tenant(),member,target));
        assertEquals(0,count("SELECT count(*) FROM audit_event WHERE tenant_id=?",f.tenant()));
    }

    @Test @TestTransaction
    void roleOperationsRejectCrossTenantAndInactiveTenantAndAccount() {
        Fixture f=fixture(); UUID tenantB=createTenant("ACTIVE"), roleB=UUID.randomUUID(), memberB=UUID.randomUUID();
        execute("INSERT INTO app_role(role_id,tenant_id,code,name) VALUES (?,?,'reader','Reader')",roleB,tenantB);
        execute("INSERT INTO tenant_membership(membership_id,tenant_id,user_id,status) VALUES (?,?,?,'ACTIVE')",memberB,tenantB,f.user());
        var admin=roleService(f.actor(),false);
        UUID member=service(f.actor(),audit,tenants).invite(f.tenant(),f.user()).id();
        assertThrows(SecurityException.class, () -> admin.assign(f.tenant(),member,roleB));
        assertThrows(SecurityException.class, () -> admin.assign(f.tenant(),memberB,roleB));
        assertThrows(SecurityException.class, () -> admin.roles(tenantB));
        UUID protectedRole=createProtectedRole(f.tenant());
        assertThrows(SecurityException.class, () -> admin.changePermissions(f.tenant(),protectedRole,java.util.Set.of()));
        var platform=roleService(f.actor(),true);
        assertThrows(MembershipConflictException.class, () -> platform.designateAdmin(f.tenant(),member));
        execute("UPDATE tenant_membership SET status='ACTIVE' WHERE membership_id=?",member);
        execute("UPDATE user_account SET status='DISABLED' WHERE user_id=?",f.user());
        assertThrows(MembershipConflictException.class, () -> platform.designateAdmin(f.tenant(),member));
        for (String status:java.util.List.of("SUSPENDED","CLOSED")) {
            execute("UPDATE tenant SET status=? WHERE tenant_id=?",status,f.tenant());
            assertThrows(SecurityException.class, () -> platform.designateAdmin(f.tenant(),member));
        }
        assertThrows(SecurityException.class, () -> platform.designateAdmin(UUID.randomUUID(),member));
    }

    private UUID createProtectedRole(UUID tenant) {
        try (var c=db.getConnection(); var s=c.prepareStatement("SELECT role_id FROM app_role WHERE tenant_id=? AND code='TENANT_ADMIN'")) {
            s.setObject(1,tenant);
            try (var r=s.executeQuery()) { if (r.next()) return r.getObject(1,UUID.class); }
        } catch (SQLException e) { throw new IllegalStateException(e); }
        UUID role=UUID.randomUUID();
        execute("INSERT INTO app_role(role_id,tenant_id,code,name) VALUES (?,?,'TENANT_ADMIN','Admin')",role,tenant);
        for (String permission : java.util.Set.of("TENANT_READ","TENANT_MANAGE","USER_MANAGE"))
            execute("INSERT INTO role_permission(tenant_id,role_id,permission_code) VALUES (?,?,?)",tenant,role,permission);
        return role;
    }

    private TenantRoleAdministrationService roleService(ActorId id, boolean platform) {
        CurrentActor current = new CurrentActor() {
            public Optional<ActorId> id() { return Optional.of(id); }
            public boolean hasPlatformSuperAdminRole() { return platform; }
        };
        return new TenantRoleAdministrationService(current,new TenantAuthorizationService(current,permissions),tenants,lock,store,roles,audit);
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
    void concurrentPlatformRemovalsCannotRemoveBothAdministrators() throws Exception {
        Fixture f = QuarkusTransaction.requiringNew().call(this::fixture);
        UUID other = UUID.randomUUID();
        QuarkusTransaction.requiringNew().run(() -> {
            UUID role = createProtectedRole(f.tenant());
            execute("INSERT INTO tenant_membership(membership_id,tenant_id,user_id,status) VALUES (?,?,?,'ACTIVE')",other,f.tenant(),f.user());
            execute("INSERT INTO membership_role(tenant_id,membership_id,role_id) VALUES (?,?,?) ON CONFLICT DO NOTHING",f.tenant(),f.adminMembership(),role);
            execute("INSERT INTO membership_role(tenant_id,membership_id,role_id) VALUES (?,?,?)",f.tenant(),other,role);
        });
        var start=new CountDownLatch(1); var locked=new CountDownLatch(1); var release=new CountDownLatch(1);
        var pool=Executors.newFixedThreadPool(3);
        try {
            var holder=pool.submit(() -> QuarkusTransaction.requiringNew().run(() -> {
                lock.acquireAll(java.util.List.of(f.tenant())); locked.countDown();
                try { if (!release.await(10,java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("timeout"); }
                catch(InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
            }));
            assertTrue(locked.await(2,java.util.concurrent.TimeUnit.SECONDS));
            java.util.function.Function<UUID,java.util.concurrent.Callable<Boolean>> remove = member -> () -> {
                start.await();
                try { QuarkusTransaction.requiringNew().run(() -> roleService(f.actor(),true).removeAdmin(f.tenant(),member)); return true; }
                catch(MembershipConflictException expected) { return false; }
            };
            var first=pool.submit(remove.apply(f.adminMembership())); var second=pool.submit(remove.apply(other));
            start.countDown();
            long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(2);
            while(count("SELECT count(*) FROM pg_locks WHERE locktype='advisory' AND NOT granted")<2 && System.nanoTime()<deadline) Thread.sleep(20);
            assertTrue(count("SELECT count(*) FROM pg_locks WHERE locktype='advisory' AND NOT granted")>=2);
            release.countDown(); holder.get(); assertNotEquals(first.get(),second.get());
            assertEquals(1,QuarkusTransaction.requiringNew().call(() -> roles.activeAdministrators(f.tenant())));
            assertEquals(1,QuarkusTransaction.requiringNew().call(() -> count("SELECT count(*) FROM audit_event WHERE tenant_id=? AND action='ROLE_REVOKED'",f.tenant())));
        } finally { release.countDown(); pool.shutdownNow(); }
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

    @Test
    void roleAssignmentRechecksRevokedAuthorizationAfterLockWait() throws Exception {
        Fixture f=QuarkusTransaction.requiringNew().call(this::fixture); UUID role=UUID.randomUUID(), member=UUID.randomUUID();
        QuarkusTransaction.requiringNew().run(() -> {
            execute("INSERT INTO app_role(role_id,tenant_id,code,name) VALUES (?,?,'reader','Reader')",role,f.tenant());
            execute("INSERT INTO tenant_membership(membership_id,tenant_id,user_id,status) VALUES (?,?,?,'ACTIVE')",member,f.tenant(),f.user());
        });
        var locked=new CountDownLatch(1); var release=new CountDownLatch(1); var pool=Executors.newFixedThreadPool(2);
        try {
            var revoke=pool.submit(() -> QuarkusTransaction.requiringNew().run(() -> {
                lock.acquireAll(java.util.List.of(f.tenant()));
                execute("DELETE FROM membership_role WHERE tenant_id=? AND membership_id=?",f.tenant(),f.adminMembership());
                locked.countDown();
                try { if (!release.await(10,java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("timeout"); }
                catch(InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
            }));
            assertTrue(locked.await(2,java.util.concurrent.TimeUnit.SECONDS));
            var assign=pool.submit(() -> assertThrows(SecurityException.class, () -> QuarkusTransaction.requiringNew().run(
                    () -> roleService(f.actor(),false).assign(f.tenant(),member,role))));
            long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(2);
            while(count("SELECT count(*) FROM pg_locks WHERE locktype='advisory' AND NOT granted")==0 && System.nanoTime()<deadline) Thread.sleep(20);
            assertTrue(count("SELECT count(*) FROM pg_locks WHERE locktype='advisory' AND NOT granted")>0);
            release.countDown(); revoke.get(); assign.get();
            assertEquals(0,QuarkusTransaction.requiringNew().call(() -> count("SELECT count(*) FROM membership_role WHERE tenant_id=? AND membership_id=? AND role_id=?",f.tenant(),member,role)));
        } finally { release.countDown(); pool.shutdownNow(); }
    }

    @Test
    void roleAuthorizationDatabaseFailureFailsClosedAndRollsBack() {
        Fixture f=QuarkusTransaction.requiringNew().call(this::fixture);
        assertThrows(RuntimeException.class, () -> QuarkusTransaction.requiringNew().run(() -> {
            execute("ALTER TABLE role_permission RENAME TO role_permission_unavailable");
            roleService(f.actor(),false).roles(f.tenant());
        }));
        assertEquals(1,QuarkusTransaction.requiringNew().call(() -> count(
                "SELECT count(*) FROM role_permission WHERE tenant_id=? AND permission_code='USER_MANAGE'",f.tenant())));
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
        execute("INSERT INTO app_role(role_id,tenant_id,code,name) VALUES (?,?,'TENANT_ADMIN','Admin')", role, tenant);
        for (String permission : java.util.Set.of("TENANT_READ","TENANT_MANAGE","USER_MANAGE"))
            execute("INSERT INTO role_permission(tenant_id,role_id,permission_code) VALUES (?,?,?)", tenant, role, permission);
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
