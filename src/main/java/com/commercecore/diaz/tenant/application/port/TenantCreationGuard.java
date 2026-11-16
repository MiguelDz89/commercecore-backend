package com.commercecore.diaz.tenant.application.port;

import java.util.UUID;

/** Internal port preventing a tenant insert outside the guarded onboarding flow. */
public interface TenantCreationGuard {
    void begin(UUID tenantId);
    void requireStarted(UUID tenantId);
}
