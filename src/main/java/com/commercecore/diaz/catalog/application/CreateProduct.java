package com.commercecore.diaz.catalog.application;

import com.commercecore.diaz.catalog.domain.Product;
import java.util.Map;
import java.util.UUID;

public record CreateProduct(UUID categoryId, String sku, String name, String description,
                            String baseUnit, boolean fractionalQuantitiesEnabled,
                            Product.Type type, UUID parentId, Map<String, String> attributes) {
    public CreateProduct {
        attributes = Product.variantAttributes(type, parentId, attributes);
        Product.requireSku(type, sku);
        Product.requireName(name);
        Product.requireBaseUnit(baseUnit);
    }
}
