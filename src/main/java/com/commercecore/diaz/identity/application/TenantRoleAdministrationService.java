package com.commercecore.diaz.identity.application;

import com.commercecore.diaz.audit.application.TenantAuditEvent;
import com.commercecore.diaz.audit.application.TenantAuditRecorder;
import com.commercecore.diaz.identity.application.port.MembershipStore;
import com.commercecore.diaz.identity.application.port.TenantAdministrationLock;
import com.commercecore.diaz.identity.application.port.TenantRoleStore;
import com.commercecore.diaz.identity.domain.Membership;
import com.commercecore.diaz.identity.domain.OrdinaryRolePolicy;
import com.commercecore.diaz.identity.domain.TenantRole;
import com.commercecore.diaz.tenant.application.TenantStatusQuery;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.Objects;

@ApplicationScoped
public class TenantRoleAdministrationService implements TenantRoleAdministration {
    private final CurrentActor actor;
    private final TenantAuthorization authorization;
    private final TenantStatusQuery tenantStatus;
    private final TenantAdministrationLock lock;
    private final MembershipStore memberships;
    private final TenantRoleStore roles;
    private final TenantAuditRecorder audit;

    public TenantRoleAdministrationService(CurrentActor actor, TenantAuthorization authorization, TenantStatusQuery tenantStatus,
            TenantAdministrationLock lock, MembershipStore memberships, TenantRoleStore roles, TenantAuditRecorder audit) {
        this.actor=actor; this.authorization=authorization; this.tenantStatus=tenantStatus; this.lock=lock;
        this.memberships=memberships; this.roles=roles; this.audit=audit;
    }
    @Override @Transactional public List<TenantRole> roles(UUID tenant) {
        prepare(tenant, false, false); return roles.list(tenant);
    }
    @Override @Transactional public Set<String> permissions(UUID tenant, UUID role) {
        prepare(tenant, false, false); return requireRole(tenant, role).permissions();
    }
    @Override @Transactional public List<TenantRole> membershipRoles(UUID tenant, UUID member) {
        prepare(tenant, false, false); requireMember(tenant,member); return roles.listMembershipRoles(tenant,member);
    }
    @Override @Transactional public void assign(UUID tenant, UUID member, UUID role) {
        ordinaryAssignment(tenant,member,role,true);
    }
    @Override @Transactional public void revoke(UUID tenant, UUID member, UUID role) {
        ordinaryAssignment(tenant,member,role,false);
    }
    private void ordinaryAssignment(UUID tenant, UUID memberId, UUID roleId, boolean assign) {
        UUID actorId=prepare(tenant,false,true);
        Membership member=requireMember(tenant,memberId); TenantRole role=requireRole(tenant,roleId);
        if (member.userId().equals(actorId) || memberships.isTenantAdmin(tenant,memberId)) throw denied();
        OrdinaryRolePolicy.checkAssignment(role,effective(tenant,role.permissions()));
        boolean changed=assign ? roles.assign(tenant,memberId,roleId) : roles.revoke(tenant,memberId,roleId);
        if(!changed) throw new MembershipConflictException("Role assignment conflict");
        audit.record(new TenantAuditEvent(tenant,actorId,TenantAuditEvent.EntityType.MEMBERSHIP,memberId,
                assign ? TenantAuditEvent.Action.ROLE_ASSIGNED : TenantAuditEvent.Action.ROLE_REVOKED,roleId,null));
    }
    @Override @Transactional public void changePermissions(UUID tenant, UUID roleId, Set<String> permissions) {
        UUID actorId=prepare(tenant,false,true); TenantRole role=requireRole(tenant,roleId);
        Set<String> proposed=Set.copyOf(Objects.requireNonNull(permissions,"permissions"));
        if (roles.roleAssignedToAdmin(tenant, roleId)) throw denied();
        var scope=new HashSet<>(role.permissions()); scope.addAll(proposed);
        OrdinaryRolePolicy.checkPermissionChange(role,proposed,effective(tenant,scope),roles.roleAssignedToUser(tenant,roleId,actorId));
        if(!roles.permissionsExist(proposed)) throw new MembershipConflictException("Unknown permission");
        roles.replacePermissions(tenant,roleId,proposed);
        audit.record(new TenantAuditEvent(tenant,actorId,TenantAuditEvent.EntityType.ROLE,roleId,
                TenantAuditEvent.Action.ROLE_PERMISSIONS_CHANGED,null,null,role.permissions(),proposed));
    }
    @Override @Transactional public void designateAdmin(UUID tenant, UUID memberId) { platformAssignment(tenant,memberId,true); }
    @Override @Transactional public void removeAdmin(UUID tenant, UUID memberId) { platformAssignment(tenant,memberId,false); }
    private void platformAssignment(UUID tenant, UUID memberId, boolean assign) {
        UUID actorId=prepare(tenant,true,true); Membership member=requireMember(tenant,memberId);
        TenantRole role=roles.findAdmin(tenant).orElseThrow(() -> new MembershipConflictException("Protected role unavailable"));
        if (assign && (member.status()!=Membership.Status.ACTIVE || !memberships.isActiveAccount(member.userId())))
            throw new MembershipConflictException("Active account and membership required");
        if (!assign && roles.hasAssignment(tenant,memberId,role.roleId()) && member.status()==Membership.Status.ACTIVE
                && memberships.isActiveAccount(member.userId()) && roles.activeAdministrators(tenant)<=1)
            throw new MembershipConflictException("Cannot remove last active administrator");
        boolean changed=assign ? roles.assign(tenant,memberId,role.roleId()) : roles.revoke(tenant,memberId,role.roleId());
        if (!changed) throw new MembershipConflictException("Role assignment conflict");
        audit.record(new TenantAuditEvent(tenant,actorId,TenantAuditEvent.EntityType.MEMBERSHIP,memberId,
                assign ? TenantAuditEvent.Action.ROLE_ASSIGNED : TenantAuditEvent.Action.ROLE_REVOKED,role.roleId(),null));
    }
    private UUID prepare(UUID tenant, boolean platform, boolean write) {
        Objects.requireNonNull(tenant,"tenantId"); if(write) lock.acquireAll(List.of(tenant));
        var id=actor.id().orElseThrow(TenantRoleAdministrationService::denied);
        if(platform ? !actor.hasPlatformSuperAdminRole() : authorization.hasPermission(tenant,"USER_MANAGE")!=TenantPermissionDecision.ALLOW)
            throw denied();
        if(tenantStatus.statusOf(tenant)!=TenantStatusQuery.Result.ACTIVE) throw denied();
        return memberships.activeAccount(id).orElseThrow(TenantRoleAdministrationService::denied);
    }
    private Set<String> effective(UUID tenant, Set<String> required) {
        var result=new HashSet<String>();
        for(String permission:required) if(authorization.hasPermission(tenant,permission)==TenantPermissionDecision.ALLOW) result.add(permission);
        return result;
    }
    private TenantRole requireRole(UUID tenant, UUID role) {
        return roles.find(tenant,Objects.requireNonNull(role,"roleId")).orElseThrow(TenantRoleAdministrationService::denied);
    }
    private Membership requireMember(UUID tenant, UUID member) {
        return memberships.find(tenant,Objects.requireNonNull(member,"membershipId")).orElseThrow(TenantRoleAdministrationService::denied);
    }
    private static SecurityException denied() { return new SecurityException("Operation denied"); }
}
