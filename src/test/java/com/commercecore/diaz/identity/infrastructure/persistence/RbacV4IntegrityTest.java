package com.commercecore.diaz.identity.infrastructure.persistence;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class RbacV4IntegrityTest {
    @Inject DataSource db;

    @Test
    void protectedRoleMayBeCreatedInSeveralStatementsButInvalidCommitsFail() throws Exception {
        UUID tenant=tenant(), valid=UUID.randomUUID();
        try(var c=db.getConnection()) {
            c.setAutoCommit(false); role(c,tenant,valid,"TENANT_ADMIN");
            for(String permission:new String[]{"TENANT_READ","TENANT_MANAGE","USER_MANAGE"}) permission(c,tenant,valid,permission);
            c.commit();
        }
        assertEquals(3,count("SELECT count(*) FROM role_permission WHERE tenant_id=? AND role_id=?",tenant,valid));
        UUID missingTenant=tenant(), missing=UUID.randomUUID();
        try(var c=db.getConnection()) {
            c.setAutoCommit(false); role(c,missingTenant,missing,"TENANT_ADMIN"); permission(c,missingTenant,missing,"TENANT_READ");
            SQLException error=assertThrows(SQLException.class,c::commit); assertEquals("23514",error.getSQLState()); c.rollback();
        }
        assertEquals(0,count("SELECT count(*) FROM app_role WHERE role_id=?",missing));
        try(var c=db.getConnection()) {
            c.setAutoCommit(false); permission(c,tenant,valid,"PRODUCT_READ");
            SQLException error=assertThrows(SQLException.class,c::commit); assertEquals("23514",error.getSQLState()); c.rollback();
        }
        assertEquals(3,count("SELECT count(*) FROM role_permission WHERE tenant_id=? AND role_id=?",tenant,valid));
    }

    @Test
    void ordinaryAdministrativePermissionsAndProtectedRoleChangesAreRejected() throws Exception {
        UUID tenant=tenant(), admin=UUID.randomUUID(), ordinary=UUID.randomUUID();
        try(var c=db.getConnection()) {
            c.setAutoCommit(false); role(c,tenant,admin,"TENANT_ADMIN");
            for(String p:new String[]{"TENANT_READ","TENANT_MANAGE","USER_MANAGE"}) permission(c,tenant,admin,p);
            role(c,tenant,ordinary,"ordinary"); c.commit();
        }
        for(String p:new String[]{"USER_MANAGE","TENANT_MANAGE"}) try(var c=db.getConnection()) {
            c.setAutoCommit(false); permission(c,tenant,ordinary,p);
            assertEquals("23514",assertThrows(SQLException.class,c::commit).getSQLState()); c.rollback();
        }
        reject("UPDATE app_role SET code='other' WHERE role_id=?",admin);
        reject("UPDATE app_role SET name='Other' WHERE role_id=?",admin);
        reject("DELETE FROM app_role WHERE role_id=?",admin);
        reject("UPDATE app_role SET code='TENANT_ADMIN' WHERE role_id=?",ordinary);
        UUID otherTenant=tenant();
        try(var c=db.getConnection()) {
            assertEquals("23514",assertThrows(SQLException.class,() -> execute(c,"UPDATE app_role SET tenant_id=? WHERE role_id=?",otherTenant,admin)).getSQLState());
        }
        try(var c=db.getConnection()) {
            assertEquals("23514",assertThrows(SQLException.class,() -> role(c,tenant,UUID.randomUUID(),"tenant_admin")).getSQLState());
        }
        try(var c=db.getConnection()) {
            assertEquals("23514",assertThrows(SQLException.class,() -> role(c,tenant,UUID.randomUUID(),"reader\t")).getSQLState());
        }
        try(var c=db.getConnection(); var s=c.createStatement()) {
            assertEquals("23514",assertThrows(SQLException.class,() -> s.execute("TRUNCATE role_permission CASCADE")).getSQLState());
        }
        try(var c=db.getConnection(); var s=c.createStatement()) {
            assertEquals("23514",assertThrows(SQLException.class,() -> s.execute("DELETE FROM permission WHERE permission_code='USER_MANAGE'")).getSQLState());
            assertEquals("23514",assertThrows(SQLException.class,() -> s.execute("UPDATE permission SET permission_code='RENAMED' WHERE permission_code='TENANT_READ'")).getSQLState());
        }
    }

    @Test
    void deletingOrdinaryRoleCascadesItsPermissions() throws Exception {
        UUID tenant=tenant(), role=UUID.randomUUID();
        try(var c=db.getConnection()) {
            c.setAutoCommit(false); role(c,tenant,role,"ordinary"); permission(c,tenant,role,"PRODUCT_READ"); c.commit();
            execute(c,"DELETE FROM app_role WHERE tenant_id=? AND role_id=?",tenant,role);
        }
        assertEquals(0,count("SELECT count(*) FROM role_permission WHERE tenant_id=? AND role_id=?",tenant,role));
    }

    @Test
    void crossTenantMoveAndAuditRollBackOnIntegrityViolation() throws Exception {
        UUID a=tenant(), b=tenant(), admin=UUID.randomUUID(), ordinary=UUID.randomUUID(), event=UUID.randomUUID();
        try(var c=db.getConnection()) {
            c.setAutoCommit(false); role(c,a,admin,"TENANT_ADMIN"); role(c,b,ordinary,"ordinary");
            for(String p:new String[]{"TENANT_READ","TENANT_MANAGE","USER_MANAGE"}) permission(c,a,admin,p); c.commit();
        }
        try(var c=db.getConnection()) {
            c.setAutoCommit(false);
            execute(c,"INSERT INTO audit_event(audit_id,tenant_id,entity_type,entity_id,action) VALUES (?,?,'ROLE',?,'ROLE_PERMISSIONS_CHANGED')",event,a,admin);
            execute(c,"UPDATE role_permission SET tenant_id=?,role_id=? WHERE tenant_id=? AND role_id=? AND permission_code='USER_MANAGE'",b,ordinary,a,admin);
            assertEquals("23514",assertThrows(SQLException.class,c::commit).getSQLState()); c.rollback();
        }
        assertEquals(3,count("SELECT count(*) FROM role_permission WHERE tenant_id=? AND role_id=?",a,admin));
        assertEquals(0,count("SELECT count(*) FROM role_permission WHERE tenant_id=? AND role_id=?",b,ordinary));
        assertEquals(0,count("SELECT count(*) FROM audit_event WHERE audit_id=?",event));
    }

    @Test
    void unprivilegedRuntimeCanWritePermittedRowsButCannotBypassTriggers() throws Exception {
        UUID tenant=tenant(), ordinary=UUID.randomUUID();
        try(var c=db.getConnection()) {
            c.setAutoCommit(false); role(c,tenant,ordinary,"ordinary"); permission(c,tenant,ordinary,"PRODUCT_READ"); c.commit();
        }
        String runtime="v4_test_"+UUID.randomUUID().toString().replace("-","");
        try(var c=db.getConnection(); var s=c.createStatement()) {
            try {
                s.execute("CREATE ROLE "+runtime+" NOLOGIN");
                s.execute("GRANT USAGE ON SCHEMA public TO "+runtime);
                s.execute("GRANT SELECT ON app_role, role_permission TO "+runtime);
                s.execute("GRANT UPDATE ON role_permission TO "+runtime);
                s.execute("SET ROLE "+runtime);
                s.execute("UPDATE role_permission SET permission_code=permission_code WHERE tenant_id='"+tenant+"' AND role_id='"+ordinary+"'");
                assertEquals("42501",assertThrows(SQLException.class,() -> s.execute("TRUNCATE role_permission")).getSQLState());
                assertEquals("42501",assertThrows(SQLException.class,() -> s.execute("ALTER TABLE app_role DISABLE TRIGGER ALL")).getSQLState());
                s.execute("CREATE TEMP TABLE v4_fake_role_permission(tenant_id uuid,role_id uuid)");
                assertEquals("42501",assertThrows(SQLException.class,() -> s.execute("CREATE TRIGGER v4_fake BEFORE INSERT ON v4_fake_role_permission FOR EACH ROW EXECUTE FUNCTION public.rbac_lock_permission_role()")).getSQLState());
            } finally {
                s.execute("RESET ROLE");
                s.execute("DROP TABLE IF EXISTS pg_temp.v4_fake_role_permission");
                s.execute("REVOKE UPDATE ON role_permission FROM "+runtime);
                s.execute("REVOKE SELECT ON app_role, role_permission FROM "+runtime);
                s.execute("REVOKE USAGE ON SCHEMA public FROM "+runtime);
                s.execute("DROP ROLE IF EXISTS "+runtime);
            }
        }
    }

    @Test
    void staleRepeatableReadCannotCommitAfterConcurrentPermissionChange() throws Exception {
        UUID tenant=tenant(), admin=UUID.randomUUID();
        try(var c=db.getConnection()) {
            c.setAutoCommit(false); role(c,tenant,admin,"TENANT_ADMIN");
            for(String p:new String[]{"TENANT_READ","TENANT_MANAGE","USER_MANAGE"}) permission(c,tenant,admin,p); c.commit();
        }
        try(var first=db.getConnection(); var stale=db.getConnection()) {
            first.setAutoCommit(false); stale.setAutoCommit(false); stale.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            try(var s=stale.createStatement(); var r=s.executeQuery("SELECT count(*) FROM role_permission")) { assertTrue(r.next()); }
            execute(first,"DELETE FROM role_permission WHERE tenant_id=? AND role_id=? AND permission_code='TENANT_READ'",tenant,admin);
            permission(first,tenant,admin,"TENANT_READ"); first.commit();
            SQLException error=assertThrows(SQLException.class,() -> execute(stale,"DELETE FROM role_permission WHERE tenant_id=? AND role_id=? AND permission_code='USER_MANAGE'",tenant,admin));
            assertEquals("40001",error.getSQLState()); stale.rollback();
        }
        assertEquals(3,count("SELECT count(*) FROM role_permission WHERE tenant_id=? AND role_id=?",tenant,admin));
    }

    @Test
    void concurrentSqlWritersSerializeAndStaleWriterRollsBack() throws Exception {
        UUID tenant=tenant(), admin=UUID.randomUUID();
        try(var c=db.getConnection()) {
            c.setAutoCommit(false); role(c,tenant,admin,"TENANT_ADMIN");
            for(String p:new String[]{"TENANT_READ","TENANT_MANAGE","USER_MANAGE"}) permission(c,tenant,admin,p); c.commit();
        }
        var locked=new java.util.concurrent.CountDownLatch(1); var release=new java.util.concurrent.CountDownLatch(1);
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var first=pool.submit(() -> {
                try(var c=db.getConnection()) {
                    c.setAutoCommit(false);
                    execute(c,"UPDATE role_permission SET permission_code=permission_code WHERE tenant_id=? AND role_id=? AND permission_code='TENANT_READ'",tenant,admin);
                    locked.countDown();
                    if(!release.await(10,java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("timeout");
                    c.commit(); return true;
                }
            });
            assertTrue(locked.await(3,java.util.concurrent.TimeUnit.SECONDS));
            var second=pool.submit(() -> {
                try(var c=db.getConnection()) {
                    c.setAutoCommit(false); c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
                    try(var s=c.createStatement(); var rows=s.executeQuery("SELECT count(*) FROM role_permission")) { assertTrue(rows.next()); }
                    try {
                        execute(c,"UPDATE role_permission SET permission_code=permission_code WHERE tenant_id=? AND role_id=? AND permission_code='USER_MANAGE'",tenant,admin);
                        c.commit(); return "COMMITTED";
                    } catch(SQLException e) { c.rollback(); return e.getSQLState(); }
                }
            });
            long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
            while(count("SELECT count(*) FROM pg_locks WHERE NOT granted")==0 && System.nanoTime()<deadline) Thread.sleep(20);
            assertTrue(count("SELECT count(*) FROM pg_locks WHERE NOT granted")>0,"second SQL writer must wait");
            release.countDown(); assertTrue(first.get()); assertEquals("40001",second.get());
        } finally { release.countDown(); pool.shutdownNow(); }
        assertEquals(3,count("SELECT count(*) FROM role_permission WHERE tenant_id=? AND role_id=?",tenant,admin));
    }

    @Test
    void concurrentReadCommittedWriterCannotCommitAdditionalAdminPermission() throws Exception {
        UUID tenant=tenant(), admin=UUID.randomUUID();
        try(var c=db.getConnection()) {
            c.setAutoCommit(false); role(c,tenant,admin,"TENANT_ADMIN");
            for(String p:new String[]{"TENANT_READ","TENANT_MANAGE","USER_MANAGE"}) permission(c,tenant,admin,p); c.commit();
        }
        var locked=new java.util.concurrent.CountDownLatch(1); var release=new java.util.concurrent.CountDownLatch(1);
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var first=pool.submit(() -> {
                try(var c=db.getConnection()) {
                    c.setAutoCommit(false);
                    execute(c,"UPDATE role_permission SET permission_code=permission_code WHERE tenant_id=? AND role_id=? AND permission_code='TENANT_READ'",tenant,admin);
                    locked.countDown();
                    if(!release.await(10,java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("timeout");
                    c.commit(); return true;
                }
            });
            assertTrue(locked.await(3,java.util.concurrent.TimeUnit.SECONDS));
            var second=pool.submit(() -> {
                try(var c=db.getConnection()) {
                    c.setAutoCommit(false); c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
                    try { permission(c,tenant,admin,"PRODUCT_READ"); c.commit(); return "COMMITTED"; }
                    catch(SQLException e) { c.rollback(); return e.getSQLState(); }
                }
            });
            long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
            while(count("SELECT count(*) FROM pg_locks WHERE NOT granted")==0 && System.nanoTime()<deadline) Thread.sleep(20);
            assertTrue(count("SELECT count(*) FROM pg_locks WHERE NOT granted")>0);
            release.countDown(); assertTrue(first.get()); assertEquals("23514",second.get());
        } finally { release.countDown(); pool.shutdownNow(); }
        assertEquals(3,count("SELECT count(*) FROM role_permission WHERE tenant_id=? AND role_id=?",tenant,admin));
    }

    private UUID tenant() throws Exception {
        UUID id=UUID.randomUUID();
        try(var c=db.getConnection()) { execute(c,"INSERT INTO tenant(tenant_id,slug,legal_name,display_name) VALUES (?,?,?,?)",id,"v4-"+id,"Test","Test"); }
        return id;
    }
    private void role(Connection c,UUID tenant,UUID role,String code) throws SQLException {
        execute(c,"INSERT INTO app_role(tenant_id,role_id,code,name) VALUES (?,?,?,?)",tenant,role,code,code);
    }
    private void permission(Connection c,UUID tenant,UUID role,String permission) throws SQLException {
        execute(c,"INSERT INTO role_permission(tenant_id,role_id,permission_code) VALUES (?,?,?)",tenant,role,permission);
    }
    private void reject(String sql,UUID id) throws Exception {
        try(var c=db.getConnection()) { assertEquals("23514",assertThrows(SQLException.class,() -> execute(c,sql,id)).getSQLState()); }
    }
    private void execute(Connection c,String sql,Object... args) throws SQLException {
        try(var s=c.prepareStatement(sql)) { for(int i=0;i<args.length;i++) s.setObject(i+1,args[i]); s.executeUpdate(); }
    }
    private int count(String sql,Object... args) throws SQLException {
        try(var c=db.getConnection(); var s=c.prepareStatement(sql)) {
            for(int i=0;i<args.length;i++) s.setObject(i+1,args[i]);
            try(var r=s.executeQuery()) { r.next(); return r.getInt(1); }
        }
    }
}
