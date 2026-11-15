package com.commercecore.diaz.identity.application.port;

import java.util.Collection;
import java.util.UUID;

/** Acquire all tenant locks once, before reading or changing administration state. */
public interface TenantAdministrationLock {
    void acquireAll(Collection<UUID> tenantIds);
}
