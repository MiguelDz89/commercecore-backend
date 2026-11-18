package com.commercecore.diaz.catalog.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ProductStatusTest {
    @Test
    void acceptsOnlyApprovedTransitions() {
        Map<Product.Status, Set<Product.Status>> allowed = Map.of(
                Product.Status.DRAFT, Set.of(Product.Status.PENDING_RECEIPT, Product.Status.ACTIVE, Product.Status.ARCHIVED),
                Product.Status.PENDING_RECEIPT, Set.of(Product.Status.ACTIVE, Product.Status.INACTIVE, Product.Status.ARCHIVED),
                Product.Status.ACTIVE, Set.of(Product.Status.INACTIVE, Product.Status.PENDING_RECEIPT, Product.Status.ARCHIVED),
                Product.Status.INACTIVE, Set.of(Product.Status.ACTIVE, Product.Status.PENDING_RECEIPT, Product.Status.ARCHIVED),
                Product.Status.ARCHIVED, Set.of());
        for (Product.Status source : Product.Status.values()) {
            for (Product.Status target : Product.Status.values()) {
                assertEquals(allowed.get(source).contains(target), source.permitsTransitionTo(target),
                        source + " -> " + target);
            }
            assertEquals(false, source.permitsTransitionTo(null));
        }
    }
}
