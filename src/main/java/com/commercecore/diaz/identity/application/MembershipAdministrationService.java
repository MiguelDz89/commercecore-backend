package com.commercecore.diaz.identity.application;

import com.commercecore.diaz.audit.application.TenantAuditEvent;
import com.commercecore.diaz.audit.application.TenantAuditRecorder;
import com.commercecore.diaz.identity.application.port.MembershipStore;
import com.commercecore.diaz.identity.application.port.TenantAdministrationLock;
import com.commercecore.diaz.identity.domain.Membership;
import com.commercecore.diaz.tenant.application.TenantStatusQuery;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@ApplicationScoped
public class MembershipAdministrationService implements MembershipAdministration {
    private final CurrentActor actor;
    private final TenantAuthorization authorization;
    private final TenantStatusQuery tenantStatus;
    private final TenantAdministrationLock lock;
    private final MembershipStore store;
    private final TenantAuditRecorder audit;

    public MembershipAdministrationService(CurrentActor actor, TenantAuthorization authorization,
            TenantStatusQuery tenantStatus, TenantAdministrationLock lock, MembershipStore store,
            TenantAuditRecorder audit) {
        this.actor = actor; this.authorization = authorization; this.tenantStatus = tenantStatus;
        this.lock = lock; this.store = store; this.audit = audit;
    }

    @Override @Transactional
    public Membership invite(UUID tenantId, UUID userId) {
        UUID actorId = prepare(tenantId, true, true);
        Objects.requireNonNull(userId, "userId");
        if (!store.isActiveAccount(userId)) throw new MembershipConflictException("Account is unavailable");
        Membership member = store.invite(tenantId, userId)
                .orElseThrow(() -> new MembershipConflictException("Membership already exists"));
        record(member, actorId, TenantAuditEvent.Action.MEMBERSHIP_INVITED);
        return member;
    }

    @Override @Transactional
    public List<Membership> list(UUID tenantId, int offset, int limit) {
        if (offset < 0 || limit < 1 || limit > 100) throw new IllegalArgumentException("Invalid pagination");
        prepare(tenantId, true, false);
        return List.copyOf(store.list(tenantId, offset, limit));
    }

    @Override @Transactional
    public Membership accept(UUID tenantId, UUID membershipId) {
        return change(tenantId, membershipId, Membership.Status.INVITED, Membership.Status.ACTIVE, true,
                TenantAuditEvent.Action.MEMBERSHIP_ACTIVATED);
    }

    @Override @Transactional
    public Membership suspend(UUID tenantId, UUID membershipId) {
        return change(tenantId, membershipId, Membership.Status.ACTIVE, Membership.Status.SUSPENDED, false,
                TenantAuditEvent.Action.MEMBERSHIP_SUSPENDED);
    }

    @Override @Transactional
    public Membership reactivate(UUID tenantId, UUID membershipId) {
        return change(tenantId, membershipId, Membership.Status.SUSPENDED, Membership.Status.ACTIVE, false,
                TenantAuditEvent.Action.MEMBERSHIP_REACTIVATED);
    }

    private Membership change(UUID tenantId, UUID membershipId, Membership.Status expected,
            Membership.Status target, boolean acceptance, TenantAuditEvent.Action action) {
        UUID actorId = prepare(tenantId, !acceptance, true);
        Membership member = store.find(tenantId, Objects.requireNonNull(membershipId, "membershipId"))
                .orElseThrow(() -> new SecurityException("Membership unavailable"));
        if ((acceptance && !member.userId().equals(actorId)) || (!acceptance && member.userId().equals(actorId))
                || store.isTenantAdmin(tenantId, membershipId)) throw new SecurityException("Operation denied");
        if (!store.isActiveAccount(member.userId())) throw new MembershipConflictException("Account is unavailable");
        if (member.status() != expected) throw new MembershipConflictException("Invalid membership transition");
        Membership updated = store.transition(member, target);
        record(updated, actorId, action);
        return updated;
    }

    private UUID prepare(UUID tenantId, boolean admin, boolean modifying) {
        Objects.requireNonNull(tenantId, "tenantId");
        if (modifying) lock.acquireAll(List.of(tenantId));
        var identity = actor.id().orElseThrow(() -> new SecurityException("Operation denied"));
        if (admin && authorization.hasPermission(tenantId, "USER_MANAGE") != TenantPermissionDecision.ALLOW)
            throw new SecurityException("Operation denied");
        if (tenantStatus.statusOf(tenantId) != TenantStatusQuery.Result.ACTIVE)
            throw new SecurityException("Operation denied");
        return store.activeAccount(identity).orElseThrow(() -> new SecurityException("Operation denied"));
    }

    private void record(Membership member, UUID actorId, TenantAuditEvent.Action action) {
        audit.record(new TenantAuditEvent(member.tenantId(), actorId, TenantAuditEvent.EntityType.MEMBERSHIP,
                member.id(), action, member.userId(), null));
    }
}
