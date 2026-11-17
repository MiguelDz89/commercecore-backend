package com.commercecore.diaz.catalog.application;
import com.commercecore.diaz.catalog.domain.Product;
import java.util.List;
import java.util.UUID;
public interface ProductAdministration {
    Product create(UUID tenant,UUID category,String sku,String name,String description);
    Product get(UUID tenant,UUID id);
    List<Product> list(UUID tenant,int offset,int limit,String sku,String name,UUID category,Product.Status status);
    Product update(UUID tenant,UUID id,ProductPatch patch);
}
