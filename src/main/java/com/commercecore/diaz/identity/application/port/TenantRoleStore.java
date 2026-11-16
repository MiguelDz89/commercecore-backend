package com.commercecore.diaz.identity.application.port;

import com.commercecore.diaz.identity.domain.TenantRole;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Persistence port internal to Identity; all role and membership access is tenant scoped. */
public interface TenantRoleStore {
    Optional<TenantRole> find(UUID tenantId, UUID roleId);
    Optional<TenantRole> findAdmin(UUID tenantId);
    List<TenantRole> list(UUID tenantId);
    List<TenantRole> listPage(UUID tenantId, int offset, int limit);
    List<TenantRole> listMembershipRoles(UUID tenantId, UUID membershipId);
    List<TenantRole> listMembershipRolesPage(UUID tenantId, UUID membershipId, int offset, int limit);
    boolean roleAssignedToUser(UUID tenantId, UUID roleId, UUID userId);
    boolean roleAssignedToAdmin(UUID tenantId, UUID roleId);
    boolean hasAssignment(UUID tenantId, UUID membershipId, UUID roleId);
    boolean permissionsExist(Set<String> permissions);
    long activeAdministrators(UUID tenantId);
    boolean assign(UUID tenantId, UUID membershipId, UUID roleId);
    boolean revoke(UUID tenantId, UUID membershipId, UUID roleId);
    void replacePermissions(UUID tenantId, UUID roleId, Set<String> permissions);
}
