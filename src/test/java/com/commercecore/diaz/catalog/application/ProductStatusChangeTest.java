package com.commercecore.diaz.catalog.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.commercecore.diaz.catalog.domain.Product;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ProductStatusChangeTest {
    @Test
    void acceptsSingleAndBatchTransitions() {
        var single = new ProductStatusChange(Product.Status.ACTIVE, null,
                List.of(new ProductStatusChange.Item(UUID.randomUUID(), 0)));
        var batch = new ProductStatusChange(Product.Status.PENDING_RECEIPT, null,
                List.of(item(), item()));

        assertEquals(1, single.items().size());
        assertEquals(2, batch.items().size());
    }

    @Test
    void allowsFirstActivationWithoutReason() {
        var change = new ProductStatusChange(Product.Status.ACTIVE, null, List.of(item()));
        assertEquals(Product.Status.ACTIVE, change.target());
    }

    @Test
    void requiresReasonForArchiveAndInactivation() {
        for (var target : List.of(Product.Status.ARCHIVED, Product.Status.INACTIVE)) {
            assertThrows(IllegalArgumentException.class,
                    () -> new ProductStatusChange(target, null, List.of(item())));
            assertThrows(IllegalArgumentException.class,
                    () -> new ProductStatusChange(target, "  ", List.of(item())));
        }
        assertEquals("Fin de vida", new ProductStatusChange(Product.Status.ARCHIVED,
                "Fin de vida", List.of(item())).reason());
    }

    @Test
    void rejectsNullOrEmptyItems() {
        assertThrows(IllegalArgumentException.class,
                () -> new ProductStatusChange(Product.Status.ACTIVE, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> new ProductStatusChange(Product.Status.ACTIVE, null, List.of()));
        var items = new ArrayList<ProductStatusChange.Item>();
        items.add(null);
        assertThrows(IllegalArgumentException.class,
                () -> new ProductStatusChange(Product.Status.ACTIVE, null, items));
    }

    @Test
    void rejectsNullTargetAndProductIdentifier() {
        assertThrows(IllegalArgumentException.class,
                () -> new ProductStatusChange(null, null, List.of(item())));
        assertThrows(IllegalArgumentException.class,
                () -> new ProductStatusChange.Item(null, 0));
    }

    @Test
    void rejectsDuplicateProductsAndNegativeVersions() {
        var duplicate = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class, () -> new ProductStatusChange(
                Product.Status.ACTIVE, null,
                List.of(new ProductStatusChange.Item(duplicate, 0),
                        new ProductStatusChange.Item(duplicate, 1))));
        assertThrows(IllegalArgumentException.class, () -> new ProductStatusChange.Item(UUID.randomUUID(), -1));
    }

    @Test
    void copiesItemsToPreventExternalMutation() {
        var items = new ArrayList<>(List.of(item()));
        var change = new ProductStatusChange(Product.Status.ACTIVE, null, items);
        items.clear();
        assertEquals(1, change.items().size());
        assertThrows(UnsupportedOperationException.class, () -> change.items().clear());
    }

    private static ProductStatusChange.Item item() {
        return new ProductStatusChange.Item(UUID.randomUUID(), 3);
    }
}
