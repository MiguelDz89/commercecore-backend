package com.commercecore.diaz.audit.application;
import java.util.Objects;
import java.util.Map;
import java.util.UUID;
public record ProductAuditEvent(UUID tenantId,UUID actorAccountId,UUID productId,Action action,Snapshot before,Snapshot after) {
    public enum Action { CREATED, UPDATED }
    public record StatusChange(UUID productId, StatusSnapshot before, StatusSnapshot after) { }
    public record StatusSnapshot(String status, long version, boolean published, boolean everPublished) { }
    public record Snapshot(UUID categoryId,String sku,String name,String description,String status,long version,String baseUnit,
                           boolean fractionalQuantitiesEnabled,String type,UUID parentId,Map<String,String> attributes) { }
    public ProductAuditEvent { Objects.requireNonNull(tenantId);Objects.requireNonNull(actorAccountId);Objects.requireNonNull(productId);Objects.requireNonNull(action);Objects.requireNonNull(after); }
}
