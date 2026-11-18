package com.commercecore.diaz.tenant.application;

import com.commercecore.diaz.tenant.application.port.TenantRepository;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class TenantStatusQueryService implements TenantStatusQuery, TenantCommercialSettingsQuery {
    private final TenantRepository tenants;

    public TenantStatusQueryService(TenantRepository tenants) {
        this.tenants = tenants;
    }

    @Override
    public Optional<Settings> commercialSettings(UUID tenantId) {
        return tenants.findByTenantId(Objects.requireNonNull(tenantId, "tenantId"))
                .map(tenant -> new Settings(tenant.currencyCode(), tenant.timeZone(), tenant.fractionalQuantitiesEnabled()));
    }

    @Override
    public Result statusOf(UUID tenantId) {
        return tenants.findCurrentStatus(Objects.requireNonNull(tenantId, "tenantId"))
                .map(status -> switch (status) {
                    case ACTIVE -> Result.ACTIVE;
                    case SUSPENDED -> Result.SUSPENDED;
                    case CLOSED -> Result.CLOSED;
                })
                .orElse(Result.NOT_FOUND);
    }
}
