package com.commercecore.diaz.audit.application;

import java.util.Objects;
import java.util.UUID;
import java.util.Set;

/** Minimal event for a tenant-bound administrative action; no token or secret fields. */
public record TenantAuditEvent(UUID tenantId, UUID actorUserId, EntityType entityType, UUID entityId,
                               Action action, UUID relatedEntityId, UUID correlationId,
                               Set<String> previousPermissions, Set<String> newPermissions) {
    public enum EntityType { TENANT, MEMBERSHIP, ROLE }
    public enum Action { MEMBERSHIP_INVITED, MEMBERSHIP_ACTIVATED, MEMBERSHIP_SUSPENDED,
        MEMBERSHIP_REACTIVATED, ROLE_ASSIGNED, ROLE_REVOKED, ROLE_PERMISSIONS_CHANGED,
        TENANT_CREATED, MEMBERSHIP_BOOTSTRAPPED, ROLE_CREATED }

    public TenantAuditEvent {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(actorUserId, "actorUserId");
        Objects.requireNonNull(entityType, "entityType");
        Objects.requireNonNull(entityId, "entityId");
        Objects.requireNonNull(action, "action");
        if ((previousPermissions == null) != (newPermissions == null))
            throw new IllegalArgumentException("Both permission snapshots are required");
        if (previousPermissions != null) {
            previousPermissions = Set.copyOf(previousPermissions);
            newPermissions = Set.copyOf(newPermissions);
        }
    }

    public TenantAuditEvent(UUID tenantId, UUID actorUserId, EntityType entityType, UUID entityId,
                            Action action, UUID relatedEntityId, UUID correlationId) {
        this(tenantId, actorUserId, entityType, entityId, action, relatedEntityId, correlationId, null, null);
    }
}
