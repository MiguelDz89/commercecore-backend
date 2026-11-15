package com.commercecore.diaz.tenant.application.port;

import com.commercecore.diaz.tenant.domain.Tenant;
import com.commercecore.diaz.tenant.domain.TenantStatus;
import java.util.Optional;
import java.util.UUID;

public interface TenantRepository {
    void create(Tenant tenant);
    Optional<Tenant> findByTenantId(UUID tenantId);
    Optional<TenantStatus> findCurrentStatus(UUID tenantId);
    Optional<Tenant> findBySlug(String slug);
}
