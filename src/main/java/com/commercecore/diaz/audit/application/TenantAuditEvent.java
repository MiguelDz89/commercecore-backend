package com.commercecore.diaz.audit.application;

import java.util.Objects;
import java.util.UUID;

/** Minimal event for a tenant-bound administrative action; no token or secret fields. */
public record TenantAuditEvent(UUID tenantId, UUID actorUserId, EntityType entityType, UUID entityId,
                               Action action, UUID relatedEntityId, UUID correlationId) {
    public enum EntityType { TENANT, MEMBERSHIP, ROLE }
    public enum Action { MEMBERSHIP_INVITED, MEMBERSHIP_ACTIVATED, MEMBERSHIP_SUSPENDED,
        MEMBERSHIP_REACTIVATED, ROLE_ASSIGNED, ROLE_REVOKED, ROLE_PERMISSIONS_CHANGED }

    public TenantAuditEvent {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(actorUserId, "actorUserId");
        Objects.requireNonNull(entityType, "entityType");
        Objects.requireNonNull(entityId, "entityId");
        Objects.requireNonNull(action, "action");
    }
}
