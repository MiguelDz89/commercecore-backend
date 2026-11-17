package com.commercecore.diaz.catalog.domain;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
public record Product(UUID id,UUID tenantId,UUID categoryId,String sku,String name,String description,Status status,
                      long version,Instant createdAt,Instant updatedAt) {
    public enum Status { DRAFT, ACTIVE, INACTIVE, ARCHIVED }
    public Product {
        Objects.requireNonNull(id);Objects.requireNonNull(tenantId);Objects.requireNonNull(categoryId);Objects.requireNonNull(status);
        Objects.requireNonNull(createdAt);Objects.requireNonNull(updatedAt);
        if(sku==null || sku.isBlank() || sku.length()>80) throw new IllegalArgumentException("Invalid SKU");
        if(name==null || name.isBlank() || name.length()>200) throw new IllegalArgumentException("Invalid name");
        if(version<0) throw new IllegalArgumentException("Invalid version");
    }
}
