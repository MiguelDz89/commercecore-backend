package com.commercecore.diaz.identity.application;

import java.util.Set;

/** Closed, approved initial commercial profiles. */
public enum CommercialProfile {
    CATALOG_VIEWER("PRODUCT_READ"),
    CATALOG_OPERATOR("PRODUCT_READ", "PRODUCT_CREATE", "PRODUCT_UPDATE", "PRODUCT_IMAGE_MANAGE"),
    CATALOG_MANAGER("PRODUCT_READ", "PRODUCT_CREATE", "PRODUCT_UPDATE", "PRODUCT_IMAGE_MANAGE", "PRODUCT_DISABLE", "CATEGORY_MANAGE"),
    PRICE_MANAGER("PRODUCT_READ", "PRODUCT_PRICE_UPDATE"),
    CATALOG_IMPORTER("PRODUCT_READ", "PRODUCT_IMPORT"),
    ASSEMBLY_MANAGER("PRODUCT_READ", "PRODUCT_BOM_MANAGE"),
    VEHICLE_COMPATIBILITY_MANAGER("PRODUCT_READ", "VEHICLE_COMPATIBILITY_MANAGE"),
    AUDIT_VIEWER("AUDIT_READ");

    private final Set<String> permissions;
    CommercialProfile(String... permissions) { this.permissions = Set.of(permissions); }
    public Set<String> permissions() { return permissions; }
}
