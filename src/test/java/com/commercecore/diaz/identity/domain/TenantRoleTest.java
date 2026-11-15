package com.commercecore.diaz.identity.domain;

import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TenantRoleTest {
    @Test
    void protectedRoleRequiresExactlyApprovedPermissions() {
        TenantRole admin = new TenantRole(UUID.randomUUID(), UUID.randomUUID(), "TENANT_ADMIN",
                Set.of("TENANT_READ", "TENANT_MANAGE", "USER_MANAGE"));
        assertTrue(admin.isProtected());
        assertThrows(UnsupportedOperationException.class, () -> admin.permissions().add("PRODUCT_READ"));
        assertThrows(IllegalArgumentException.class, () -> new TenantRole(UUID.randomUUID(), UUID.randomUUID(),
                "TENANT_ADMIN", Set.of("TENANT_READ")));
        assertThrows(IllegalArgumentException.class, () -> new TenantRole(UUID.randomUUID(), UUID.randomUUID(),
                "tenant_admin", TenantRole.tenantAdminPermissions()));
        assertFalse(new TenantRole(UUID.randomUUID(), UUID.randomUUID(), "STAFF", Set.of("PRODUCT_READ")).isProtected());
    }
}
