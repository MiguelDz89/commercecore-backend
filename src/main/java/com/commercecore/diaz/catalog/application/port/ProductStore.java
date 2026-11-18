package com.commercecore.diaz.catalog.application.port;
import com.commercecore.diaz.catalog.application.CreateProduct;
import com.commercecore.diaz.catalog.domain.Product;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
public interface ProductStore {
    record VariantReference(UUID parentId, UUID variantId) { }

    Product insert(UUID tenant,UUID actor,CreateProduct command,String currency);
    boolean hasCommercialValues(UUID tenant,UUID product);
    boolean hasFractionalTiers(UUID tenant,UUID product);
    Optional<Product> find(UUID tenant,UUID id);
    List<Product> list(UUID tenant,int offset,int limit,String sku,String name,UUID category,Product.Status status,Product.Type type,UUID parent);
    Optional<Product> update(Product desired,long expectedVersion,UUID actor);
    List<Product> findSelected(UUID tenant, List<UUID> productIds);
    List<VariantReference> findUnarchivedVariants(UUID tenant, List<UUID> parentIds);
    void changeStatus(UUID tenant, UUID actor, Product.Status target, List<Product> selected);
}
