package com.commercecore.diaz.catalog.application;
import com.commercecore.diaz.catalog.domain.Product;
import java.util.List;
import java.util.UUID;
public interface ProductAdministration {
    Product create(UUID tenant,CreateProduct command);
    Product get(UUID tenant,UUID id);
    List<Product> list(UUID tenant,int offset,int limit,String sku,String name,UUID category,Product.Status status,Product.Type type,UUID parent);
    Product update(UUID tenant,UUID id,ProductPatch patch);
    UUID changeStatus(UUID tenant, ProductStatusChange change);
}
