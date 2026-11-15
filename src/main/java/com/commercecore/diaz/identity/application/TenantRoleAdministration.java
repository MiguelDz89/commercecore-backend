package com.commercecore.diaz.identity.application;

import com.commercecore.diaz.identity.domain.TenantRole;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public interface TenantRoleAdministration {
    List<TenantRole> roles(UUID tenantId);
    Set<String> permissions(UUID tenantId, UUID roleId);
    List<TenantRole> membershipRoles(UUID tenantId, UUID membershipId);
    void assign(UUID tenantId, UUID membershipId, UUID roleId);
    void revoke(UUID tenantId, UUID membershipId, UUID roleId);
    void changePermissions(UUID tenantId, UUID roleId, Set<String> permissions);
    void designateAdmin(UUID tenantId, UUID membershipId);
    void removeAdmin(UUID tenantId, UUID membershipId);
}
