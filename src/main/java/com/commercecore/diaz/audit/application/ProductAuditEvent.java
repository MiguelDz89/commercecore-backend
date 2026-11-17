package com.commercecore.diaz.audit.application;
import java.util.Objects;
import java.util.UUID;
public record ProductAuditEvent(UUID tenantId,UUID actorAccountId,UUID productId,Action action,Snapshot before,Snapshot after) {
    public enum Action { CREATED, UPDATED }
    public record Snapshot(UUID categoryId,String sku,String name,String description,String status,long version) { }
    public ProductAuditEvent { Objects.requireNonNull(tenantId);Objects.requireNonNull(actorAccountId);Objects.requireNonNull(productId);Objects.requireNonNull(action);Objects.requireNonNull(after); }
}
