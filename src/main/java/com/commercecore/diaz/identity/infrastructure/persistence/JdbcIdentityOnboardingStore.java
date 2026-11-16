package com.commercecore.diaz.identity.infrastructure.persistence;

import com.commercecore.diaz.identity.application.port.IdentityOnboardingStore;
import com.commercecore.diaz.identity.domain.ActorId;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import java.sql.SQLException;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;

@ApplicationScoped
@Transactional(Transactional.TxType.MANDATORY)
public class JdbcIdentityOnboardingStore implements IdentityOnboardingStore {
    private final DataSource source;
    public JdbcIdentityOnboardingStore(DataSource source) { this.source = source; }

    @Override public UUID lockAndVerifyAccounts(ActorId actor, UUID target) {
        try (var c=source.getConnection()) {
            UUID actorId;
            try (var s=c.prepareStatement("SELECT user_id FROM user_account WHERE oidc_issuer=? AND oidc_subject=?")) {
                s.setString(1,actor.issuer()); s.setString(2,actor.subject());
                try(var r=s.executeQuery()) { if(!r.next()) throw denied(); actorId=r.getObject(1,UUID.class); }
            }
            try (var s=c.prepareStatement("SELECT user_id FROM user_account WHERE user_id IN (?,?) ORDER BY user_id FOR UPDATE")) {
                s.setObject(1,actorId); s.setObject(2,target);
                try(var r=s.executeQuery()) { while(r.next()) { /* acquire in database UUID order */ } }
            }
            verify(c,actor,actorId,target);
            return actorId;
        } catch(SQLException e) { throw failure(e); }
    }
    @Override public void verifyLockedAccounts(ActorId actor, UUID actorId, UUID target) {
        try(var c=source.getConnection()) { verify(c,actor,actorId,target); }
        catch(SQLException e) { throw failure(e); }
    }
    private void verify(java.sql.Connection c, ActorId actor, UUID actorId, UUID target) throws SQLException {
        try(var s=c.prepareStatement("""
                SELECT user_id,oidc_issuer,oidc_subject FROM user_account
                WHERE user_id IN (?,?) AND status='ACTIVE'
                  AND btrim(oidc_issuer)<>'' AND btrim(oidc_subject)<>''
                """)) {
            s.setObject(1,actorId); s.setObject(2,target); boolean foundActor=false,foundTarget=false;
            try(var r=s.executeQuery()) {
                while(r.next()) {
                    UUID id=r.getObject(1,UUID.class);
                    if(id.equals(actorId) && actor.issuer().equals(r.getString(2)) && actor.subject().equals(r.getString(3))) foundActor=true;
                    if(id.equals(target) && !r.getString(2).isBlank() && !r.getString(3).isBlank()) foundTarget=true;
                }
            }
            if(!foundActor || !foundTarget) throw denied();
        }
    }
    @Override public UUID createMembership(UUID tenant, UUID account) {
        UUID id=UUID.randomUUID();
        update("INSERT INTO tenant_membership(membership_id,tenant_id,user_id,status) VALUES (?,?,?,'ACTIVE')",id,tenant,account);
        return id;
    }
    @Override public UUID createRole(UUID tenant, String code, Set<String> permissions) {
        UUID id=UUID.randomUUID();
        update("INSERT INTO app_role(role_id,tenant_id,code,name) VALUES (?,?,?,?)",id,tenant,code,code);
        for(String permission:permissions.stream().sorted().toList())
            update("INSERT INTO role_permission(tenant_id,role_id,permission_code) VALUES (?,?,?)",tenant,id,permission);
        return id;
    }
    @Override public void assignRole(UUID tenant, UUID membership, UUID role) {
        update("INSERT INTO membership_role(tenant_id,membership_id,role_id) VALUES (?,?,?)",tenant,membership,role);
    }
    private void update(String sql, Object... args) {
        try(var c=source.getConnection(); var s=c.prepareStatement(sql)) {
            for(int i=0;i<args.length;i++) s.setObject(i+1,args[i]);
            s.executeUpdate();
        } catch(SQLException e) { throw failure(e); }
    }
    private static SecurityException denied() { return new SecurityException("Eligible account unavailable"); }
    private static IllegalStateException failure(SQLException e) { return new IllegalStateException("Identity onboarding persistence failed",e); }
}
