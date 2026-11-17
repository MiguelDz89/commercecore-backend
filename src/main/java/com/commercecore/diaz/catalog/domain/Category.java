package com.commercecore.diaz.catalog.domain;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
public record Category(UUID id,UUID tenantId,UUID parentId,String code,String name,String description,Status status,
                       Instant createdAt,Instant updatedAt) {
    public enum Status { ACTIVE, INACTIVE }
    public Category {
        Objects.requireNonNull(id); Objects.requireNonNull(tenantId); Objects.requireNonNull(status);
        Objects.requireNonNull(createdAt); Objects.requireNonNull(updatedAt);
        if(code==null || code.isBlank() || code.length()>80) throw new IllegalArgumentException("Invalid category code");
        if(name==null || name.isBlank() || name.length()>160) throw new IllegalArgumentException("Invalid category name");
        if(id.equals(parentId)) throw new IllegalArgumentException("Invalid parent");
    }
}
