package com.commercecore.diaz.identity.application;

import java.util.UUID;

/** Evaluates permissions for the authenticated actor, never for a client-supplied identity. */
public interface TenantAuthorization {
    TenantPermissionDecision hasPermission(UUID tenantId, String permissionCode);
}
