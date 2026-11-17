package com.commercecore.diaz.catalog.api.dto;
import com.commercecore.diaz.catalog.domain.Category;
import java.time.Instant;
import java.util.UUID;
public record CategoryResponse(UUID categoryId,UUID tenantId,UUID parentCategoryId,String code,String name,String description,
                               Category.Status status,Instant createdAt,Instant updatedAt) {
    public static CategoryResponse from(Category c) { return new CategoryResponse(c.id(),c.tenantId(),c.parentId(),c.code(),c.name(),c.description(),c.status(),c.createdAt(),c.updatedAt()); }
}
