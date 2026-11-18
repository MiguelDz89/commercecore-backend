package com.commercecore.diaz.catalog.application;

import com.commercecore.diaz.catalog.domain.Product;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

/** A single product transition or an explicitly selected batch of transitions. */
public record ProductStatusChange(Product.Status target, String reason, List<Item> items) {
    public record Item(UUID productId, long version) {
        public Item {
            if (productId == null) throw new IllegalArgumentException("Product identifier is required");
            if (version < 0) throw new IllegalArgumentException("Invalid product version");
        }
    }

    public ProductStatusChange {
        if (target == null) throw new IllegalArgumentException("A target status is required");
        if (items == null || items.isEmpty()) throw new IllegalArgumentException("Products are required");
        for (Item item : items) {
            if (item == null) throw new IllegalArgumentException("Product item is required");
        }
        items = List.copyOf(items);
        var ids = new HashSet<UUID>();
        for (Item item : items) {
            if (!ids.add(item.productId())) throw new IllegalArgumentException("Duplicate product");
        }
        if (requiresReason(target) && (reason == null || reason.isBlank()))
            throw new IllegalArgumentException("A reason is required for this transition");
    }

    private static boolean requiresReason(Product.Status target) {
        return target == Product.Status.INACTIVE || target == Product.Status.ARCHIVED;
    }
}
