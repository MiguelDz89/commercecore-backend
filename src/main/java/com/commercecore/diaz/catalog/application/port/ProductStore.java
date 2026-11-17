package com.commercecore.diaz.catalog.application.port;
import com.commercecore.diaz.catalog.domain.Product;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
public interface ProductStore {
    Product insert(UUID tenant,UUID actor,UUID category,String sku,String name,String description);
    Optional<Product> find(UUID tenant,UUID id);
    List<Product> list(UUID tenant,int offset,int limit,String sku,String name,UUID category,Product.Status status);
    Optional<Product> update(Product desired,long expectedVersion,UUID actor);
}
