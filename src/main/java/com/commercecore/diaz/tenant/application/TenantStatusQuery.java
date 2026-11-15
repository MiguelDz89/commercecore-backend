package com.commercecore.diaz.tenant.application;

import java.util.UUID;

/** Public tenant status lookup. Infrastructure failures are propagated. */
public interface TenantStatusQuery {
    enum Result { ACTIVE, SUSPENDED, CLOSED, NOT_FOUND }

    Result statusOf(UUID tenantId);
}
