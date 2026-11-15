package com.commercecore.diaz.identity.domain;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** A tenant role and its explicit permissions, independent of persistence. */
public record TenantRole(UUID tenantId, UUID roleId, String code, Set<String> permissions) {
    public static final String TENANT_ADMIN = "TENANT_ADMIN";
    private static final Set<String> ADMIN_PERMISSIONS = Set.of("TENANT_READ", "TENANT_MANAGE", "USER_MANAGE");

    public TenantRole {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(roleId, "roleId");
        if (code == null || code.isBlank() || code.length() > 80 || !code.equals(code.trim())) {
            throw new IllegalArgumentException("Invalid role code");
        }
        if (TENANT_ADMIN.equalsIgnoreCase(code) && !TENANT_ADMIN.equals(code)) {
            throw new IllegalArgumentException("Reserved role code");
        }
        permissions = Set.copyOf(Objects.requireNonNull(permissions, "permissions"));
        if (permissions.stream().anyMatch(p -> p.isBlank() || p.length() > 100)) {
            throw new IllegalArgumentException("Invalid permission code");
        }
        if (TENANT_ADMIN.equals(code) && !permissions.equals(ADMIN_PERMISSIONS)) {
            throw new IllegalArgumentException("TENANT_ADMIN requires its approved permissions");
        }
    }

    public boolean isProtected() {
        return TENANT_ADMIN.equals(code);
    }

    public static Set<String> tenantAdminPermissions() {
        return ADMIN_PERMISSIONS;
    }
}
