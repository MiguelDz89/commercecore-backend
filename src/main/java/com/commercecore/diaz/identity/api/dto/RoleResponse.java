package com.commercecore.diaz.identity.api.dto;

import com.commercecore.diaz.identity.domain.TenantRole;
import java.util.Set;
import java.util.UUID;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

@Schema(example="{\"roleId\":\"00000000-0000-4000-8000-000000000001\",\"code\":\"CATALOG_VIEWER\",\"permissions\":[\"PRODUCT_READ\"]}")
public record RoleResponse(UUID roleId, String code, Set<String> permissions) {
    public RoleResponse { permissions=Set.copyOf(permissions); }
    public static RoleResponse from(TenantRole role) { return new RoleResponse(role.roleId(),role.code(),role.permissions()); }
}
