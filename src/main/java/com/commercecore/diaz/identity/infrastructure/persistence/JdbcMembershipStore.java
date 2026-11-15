package com.commercecore.diaz.identity.infrastructure.persistence;

import com.commercecore.diaz.identity.application.MembershipConflictException;
import com.commercecore.diaz.identity.application.port.MembershipStore;
import com.commercecore.diaz.identity.domain.ActorId;
import com.commercecore.diaz.identity.domain.Membership;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;

@ApplicationScoped
@Transactional(Transactional.TxType.MANDATORY)
public class JdbcMembershipStore implements MembershipStore {
    private final DataSource dataSource;
    public JdbcMembershipStore(DataSource dataSource) { this.dataSource = dataSource; }

    @Override public Optional<UUID> activeAccount(ActorId actor) {
        try (var c = dataSource.getConnection(); var s = c.prepareStatement(
                "SELECT user_id FROM user_account WHERE oidc_issuer=? AND oidc_subject=? AND status='ACTIVE'")) {
            s.setString(1, actor.issuer()); s.setString(2, actor.subject());
            try (var r = s.executeQuery()) { return r.next() ? Optional.of(r.getObject(1, UUID.class)) : Optional.empty(); }
        } catch (SQLException e) { throw failure(e); }
    }

    @Override public boolean isActiveAccount(UUID userId) {
        try (var c = dataSource.getConnection(); var s = c.prepareStatement(
                "SELECT EXISTS (SELECT 1 FROM user_account WHERE user_id=? AND status='ACTIVE')")) {
            s.setObject(1, userId);
            try (var r = s.executeQuery()) { r.next(); return r.getBoolean(1); }
        } catch (SQLException e) { throw failure(e); }
    }

    @Override public Optional<Membership> find(UUID tenantId, UUID membershipId) {
        try (var c = dataSource.getConnection(); var s = c.prepareStatement(
                "SELECT membership_id, tenant_id, user_id, status, created_at FROM tenant_membership WHERE tenant_id=? AND membership_id=?")) {
            s.setObject(1, tenantId); s.setObject(2, membershipId);
            try (var r = s.executeQuery()) { return r.next() ? Optional.of(member(r)) : Optional.empty(); }
        } catch (SQLException e) { throw failure(e); }
    }

    @Override public boolean isTenantAdmin(UUID tenantId, UUID membershipId) {
        try (var c = dataSource.getConnection(); var s = c.prepareStatement("""
                SELECT EXISTS (SELECT 1 FROM membership_role mr JOIN app_role r
                ON r.tenant_id=mr.tenant_id AND r.role_id=mr.role_id
                WHERE mr.tenant_id=? AND mr.membership_id=? AND r.code='TENANT_ADMIN')
                """)) {
            s.setObject(1, tenantId); s.setObject(2, membershipId);
            try (var r = s.executeQuery()) { r.next(); return r.getBoolean(1); }
        } catch (SQLException e) { throw failure(e); }
    }

    @Override public Optional<Membership> invite(UUID tenantId, UUID userId) {
        try (var c = dataSource.getConnection(); var s = c.prepareStatement("""
                INSERT INTO tenant_membership (tenant_id,user_id,status) VALUES (?,?,'INVITED')
                ON CONFLICT (tenant_id,user_id) DO NOTHING
                RETURNING membership_id, tenant_id, user_id, status, created_at
                """)) {
            s.setObject(1, tenantId); s.setObject(2, userId);
            try (var r = s.executeQuery()) { return r.next() ? Optional.of(member(r)) : Optional.empty(); }
        } catch (SQLException e) { throw failure(e); }
    }

    @Override public Membership transition(Membership member, Membership.Status target) {
        try (var c = dataSource.getConnection(); var s = c.prepareStatement("""
                UPDATE tenant_membership SET status=? WHERE tenant_id=? AND membership_id=? AND status=?
                RETURNING membership_id, tenant_id, user_id, status, created_at
                """)) {
            s.setString(1, target.name()); s.setObject(2, member.tenantId()); s.setObject(3, member.id());
            s.setString(4, member.status().name());
            try (var r = s.executeQuery()) {
                if (!r.next()) throw new MembershipConflictException("Membership changed concurrently");
                return member(r);
            }
        } catch (SQLException e) { throw failure(e); }
    }

    @Override public List<Membership> list(UUID tenantId, int offset, int limit) {
        try (var c = dataSource.getConnection(); var s = c.prepareStatement("""
                SELECT membership_id, tenant_id, user_id, status, created_at FROM tenant_membership
                WHERE tenant_id=? ORDER BY created_at, membership_id OFFSET ? LIMIT ?
                """)) {
            s.setObject(1, tenantId); s.setInt(2, offset); s.setInt(3, limit);
            try (var r = s.executeQuery()) {
                var members = new ArrayList<Membership>();
                while (r.next()) members.add(member(r));
                return members;
            }
        } catch (SQLException e) { throw failure(e); }
    }

    private static Membership member(ResultSet r) throws SQLException {
        return new Membership(r.getObject(1, UUID.class), r.getObject(2, UUID.class), r.getObject(3, UUID.class),
                Membership.Status.valueOf(r.getString(4)), r.getTimestamp(5).toInstant());
    }

    private static IllegalStateException failure(SQLException e) {
        return new IllegalStateException("Membership persistence failed", e);
    }
}
