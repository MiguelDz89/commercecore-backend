package com.commercecore.diaz.identity.application;

import org.junit.jupiter.api.Test;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class CommercialProfileTest {
    @Test void catalogIsClosedAndContainsNoAdministrativePermissions() {
        assertEquals(Set.of("PRODUCT_READ"), CommercialProfile.CATALOG_VIEWER.permissions());
        assertEquals(Set.of("PRODUCT_READ", "PRODUCT_CREATE", "PRODUCT_UPDATE", "PRODUCT_IMAGE_MANAGE", "PRODUCT_DISABLE", "CATEGORY_MANAGE"), CommercialProfile.CATALOG_MANAGER.permissions());
        assertEquals(Set.of("AUDIT_READ"), CommercialProfile.AUDIT_VIEWER.permissions());
        for (var profile : CommercialProfile.values()) {
            assertFalse(profile.permissions().contains("USER_MANAGE"));
            assertFalse(profile.permissions().contains("TENANT_MANAGE"));
            assertThrows(UnsupportedOperationException.class, () -> profile.permissions().add("USER_MANAGE"));
        }
        assertThrows(IllegalArgumentException.class, () -> CommercialProfile.valueOf("ALL_PERMISSIONS"));
    }
}
