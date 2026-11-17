package com.commercecore.diaz.audit.application;
import java.util.Objects;
import java.util.UUID;
/** Typed snapshots contain only category business fields. */
public record CategoryAuditEvent(UUID tenantId, UUID actorAccountId, UUID categoryId, Action action,
        Snapshot before, Snapshot after) {
    public enum Action { CREATED, UPDATED, DEACTIVATED, REACTIVATED }
    public record Snapshot(String code, String name, String description, UUID parentCategoryId, String status) { }
    public CategoryAuditEvent {
        Objects.requireNonNull(tenantId); Objects.requireNonNull(actorAccountId); Objects.requireNonNull(categoryId);
        Objects.requireNonNull(action); Objects.requireNonNull(after);
    }
}
