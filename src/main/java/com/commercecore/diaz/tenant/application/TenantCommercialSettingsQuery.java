package com.commercecore.diaz.tenant.application;

import java.util.Optional;
import java.util.UUID;

/** Tenant-owned settings used by commercial operations. */
public interface TenantCommercialSettingsQuery {
    record Settings(String currency, String timeZone, boolean fractionalQuantitiesEnabled) { }

    Optional<Settings> commercialSettings(UUID tenantId);
}
