package com.commercecore.diaz.catalog.api.dto;
import com.commercecore.diaz.catalog.domain.Product;
import java.time.Instant;
import java.util.UUID;
public record ProductResponse(UUID productId,UUID tenantId,UUID categoryId,String sku,String name,String description,
                              Product.Status status,long version,Instant createdAt,Instant updatedAt) {
    public static ProductResponse from(Product p){return new ProductResponse(p.id(),p.tenantId(),p.categoryId(),p.sku(),p.name(),p.description(),p.status(),p.version(),p.createdAt(),p.updatedAt());}
}
