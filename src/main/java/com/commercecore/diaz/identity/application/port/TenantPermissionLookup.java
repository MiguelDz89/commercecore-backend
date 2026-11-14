package com.commercecore.diaz.identity.application.port;

import com.commercecore.diaz.identity.domain.ActorId;
import java.util.UUID;

/** Internal port: callers provide an actor obtained from a trusted authentication context. */
public interface TenantPermissionLookup {
    boolean hasPermission(ActorId actor, UUID tenantId, String permissionCode);
}
