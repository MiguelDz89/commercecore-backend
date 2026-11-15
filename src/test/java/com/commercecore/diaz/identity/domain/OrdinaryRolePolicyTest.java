package com.commercecore.diaz.identity.domain;

import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OrdinaryRolePolicyTest {
    private TenantRole role(String code, Set<String> permissions) {
        return new TenantRole(UUID.randomUUID(), UUID.randomUUID(), code, permissions);
    }

    @Test
    void assignmentRequiresAllPermissionsAndDeniesAdministrativeDelegation() {
        var ordinary = role("SELLER", Set.of("PRODUCT_READ"));
        assertDoesNotThrow(() -> OrdinaryRolePolicy.checkAssignment(ordinary, Set.of("PRODUCT_READ", "USER_MANAGE")));
        assertThrows(SecurityException.class, () -> OrdinaryRolePolicy.checkAssignment(ordinary, Set.of("USER_MANAGE")));
        var excessive = role("LEGACY", Set.of("TENANT_MANAGE"));
        assertThrows(SecurityException.class, () -> OrdinaryRolePolicy.checkAssignment(excessive, Set.of("TENANT_MANAGE")));
        var protectedRole = role(TenantRole.TENANT_ADMIN, TenantRole.tenantAdminPermissions());
        assertThrows(SecurityException.class, () -> OrdinaryRolePolicy.checkAssignment(protectedRole, TenantRole.tenantAdminPermissions()));
    }

    @Test
    void changesCheckOldAndNewPermissionsAndSelfAssignment() {
        var ordinary = role("SELLER", Set.of("PRODUCT_READ"));
        assertDoesNotThrow(() -> OrdinaryRolePolicy.checkPermissionChange(ordinary, Set.of("PRODUCT_UPDATE"),
                Set.of("PRODUCT_READ", "PRODUCT_UPDATE"), false));
        assertThrows(SecurityException.class, () -> OrdinaryRolePolicy.checkPermissionChange(ordinary, Set.of(), Set.of(), false));
        assertThrows(SecurityException.class, () -> OrdinaryRolePolicy.checkPermissionChange(ordinary, Set.of("USER_MANAGE"),
                Set.of("PRODUCT_READ", "USER_MANAGE"), false));
        assertThrows(SecurityException.class, () -> OrdinaryRolePolicy.checkPermissionChange(ordinary, Set.of(),
                Set.of("PRODUCT_READ"), true));
    }
}
