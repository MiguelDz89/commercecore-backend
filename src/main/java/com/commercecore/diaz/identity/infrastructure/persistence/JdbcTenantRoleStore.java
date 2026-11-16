package com.commercecore.diaz.identity.infrastructure.persistence;

import com.commercecore.diaz.identity.application.port.TenantRoleStore;
import com.commercecore.diaz.identity.application.AuthorizationIntegrityException;
import com.commercecore.diaz.identity.domain.TenantRole;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;

@ApplicationScoped
@Transactional(Transactional.TxType.MANDATORY)
public class JdbcTenantRoleStore implements TenantRoleStore {
    private final DataSource dataSource;
    public JdbcTenantRoleStore(DataSource dataSource) { this.dataSource = dataSource; }

    @Override public Optional<TenantRole> find(UUID tenant, UUID role) {
        return roles("SELECT role_id,code FROM app_role WHERE tenant_id=? AND role_id=?", tenant, role).stream().findFirst();
    }
    @Override public Optional<TenantRole> findAdmin(UUID tenant) {
        return roles("SELECT role_id,code FROM app_role WHERE tenant_id=? AND code='TENANT_ADMIN'", tenant).stream().findFirst();
    }
    @Override public List<TenantRole> list(UUID tenant) {
        return roles("SELECT role_id,code FROM app_role WHERE tenant_id=? ORDER BY code,role_id", tenant);
    }
    @Override public List<TenantRole> listPage(UUID tenant, int offset, int limit) {
        return roles("SELECT role_id,code FROM app_role WHERE tenant_id=? ORDER BY code,role_id LIMIT ? OFFSET ?",tenant,limit,offset);
    }
    @Override public List<TenantRole> listMembershipRolesPage(UUID tenant, UUID membership, int offset, int limit) {
        return roles("""
                SELECT r.role_id,r.code FROM app_role r JOIN membership_role mr
                ON r.tenant_id=mr.tenant_id AND r.role_id=mr.role_id
                WHERE r.tenant_id=? AND mr.membership_id=? ORDER BY r.code,r.role_id LIMIT ? OFFSET ?
                """,tenant,membership,limit,offset);
    }
    @Override public List<TenantRole> listMembershipRoles(UUID tenant, UUID membership) {
        return roles("""
                SELECT r.role_id,r.code FROM app_role r JOIN membership_role mr
                ON r.tenant_id=mr.tenant_id AND r.role_id=mr.role_id
                WHERE r.tenant_id=? AND mr.membership_id=? ORDER BY r.code,r.role_id
                """, tenant, membership);
    }
    private List<TenantRole> roles(String sql, UUID tenant, Object... args) {
        // The page is selected before joining permissions, preserving LIMIT/OFFSET semantics.
        String grouped = "SELECT page.role_id,page.code, "
                + "array_agg(p.permission_code ORDER BY p.permission_code) FILTER (WHERE p.permission_code IS NOT NULL) "
                + "FROM ("+sql+") page LEFT JOIN role_permission p ON p.tenant_id=? AND p.role_id=page.role_id "
                + "GROUP BY page.role_id,page.code ORDER BY page.code,page.role_id";
        try (var c=dataSource.getConnection(); var s=c.prepareStatement(grouped)) {
            s.setObject(1,tenant);
            for (int i=0;i<args.length;i++) s.setObject(i+2,args[i]);
            s.setObject(args.length+2,tenant);
            var result=new ArrayList<TenantRole>();
            try (var rows=s.executeQuery()) {
                while(rows.next()) {
                    var permissions=new HashSet<String>();
                    var array=rows.getArray(3);
                    if(array!=null) { try { for(Object value:(Object[])array.getArray()) permissions.add((String)value); }
                        finally { array.free(); } }
                    try { result.add(new TenantRole(tenant,rows.getObject(1,UUID.class),rows.getString(2),permissions)); }
                    catch(IllegalArgumentException e) { throw new AuthorizationIntegrityException("Inconsistent persisted tenant role"); }
                }
            }
            return List.copyOf(result);
        } catch(SQLException e) { throw failure(e); }
    }
    @Override public boolean roleAssignedToUser(UUID tenant, UUID role, UUID user) {
        return exists("""
                SELECT EXISTS (SELECT 1 FROM membership_role mr JOIN tenant_membership m
                ON m.tenant_id=mr.tenant_id AND m.membership_id=mr.membership_id
                WHERE mr.tenant_id=? AND mr.role_id=? AND m.user_id=?)
                """, tenant, role, user);
    }
    @Override public boolean hasAssignment(UUID tenant, UUID membership, UUID role) {
        return exists("SELECT EXISTS (SELECT 1 FROM membership_role WHERE tenant_id=? AND membership_id=? AND role_id=?)", tenant, membership, role);
    }
    @Override public boolean roleAssignedToAdmin(UUID tenant, UUID role) {
        return exists("""
                SELECT EXISTS (SELECT 1 FROM membership_role ordinary
                JOIN membership_role admin_assignment ON admin_assignment.tenant_id=ordinary.tenant_id AND admin_assignment.membership_id=ordinary.membership_id
                JOIN app_role admin ON admin.tenant_id=admin_assignment.tenant_id AND admin.role_id=admin_assignment.role_id
                WHERE ordinary.tenant_id=? AND ordinary.role_id=? AND admin.code='TENANT_ADMIN')
                """, tenant, role);
    }
    @Override public boolean permissionsExist(Set<String> permissions) {
        for (String permission : permissions) if (!exists("SELECT EXISTS (SELECT 1 FROM permission WHERE permission_code=?)", permission)) return false;
        return true;
    }
    @Override public long activeAdministrators(UUID tenant) {
        try (var c=dataSource.getConnection(); var s=c.prepareStatement("""
                SELECT count(*) FROM tenant_membership m JOIN user_account u ON u.user_id=m.user_id
                JOIN membership_role mr ON mr.tenant_id=m.tenant_id AND mr.membership_id=m.membership_id
                JOIN app_role r ON r.tenant_id=mr.tenant_id AND r.role_id=mr.role_id
                WHERE m.tenant_id=? AND m.status='ACTIVE' AND u.status='ACTIVE' AND r.code='TENANT_ADMIN'
                """)) {
            s.setObject(1,tenant); try(var r=s.executeQuery()) { r.next(); return r.getLong(1); }
        } catch(SQLException e) { throw failure(e); }
    }
    @Override public boolean assign(UUID tenant, UUID member, UUID role) {
        return update("INSERT INTO membership_role(tenant_id,membership_id,role_id) VALUES (?,?,?) ON CONFLICT DO NOTHING", tenant,member,role)==1;
    }
    @Override public boolean revoke(UUID tenant, UUID member, UUID role) {
        return update("DELETE FROM membership_role WHERE tenant_id=? AND membership_id=? AND role_id=?",tenant,member,role)==1;
    }
    @Override public void replacePermissions(UUID tenant, UUID role, Set<String> permissions) {
        update("DELETE FROM role_permission WHERE tenant_id=? AND role_id=?",tenant,role);
        for (String permission : permissions) update("INSERT INTO role_permission(tenant_id,role_id,permission_code) VALUES (?,?,?)",tenant,role,permission);
    }
    private boolean exists(String sql, Object... args) {
        try(var c=dataSource.getConnection(); var s=c.prepareStatement(sql)) {
            for(int i=0;i<args.length;i++) s.setObject(i+1,args[i]);
            try(var r=s.executeQuery()) { if(!r.next()) throw new SQLException("Missing result"); return r.getBoolean(1); }
        } catch(SQLException e) { throw failure(e); }
    }
    private int update(String sql, Object... args) {
        try(var c=dataSource.getConnection(); var s=c.prepareStatement(sql)) {
            for(int i=0;i<args.length;i++) s.setObject(i+1,args[i]); return s.executeUpdate();
        } catch(SQLException e) { throw failure(e); }
    }
    private static IllegalStateException failure(SQLException cause) { return new IllegalStateException("Role persistence failed",cause); }
}
