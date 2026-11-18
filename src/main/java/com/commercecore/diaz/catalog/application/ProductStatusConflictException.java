package com.commercecore.diaz.catalog.application;

import java.util.List;
import java.util.UUID;

public final class ProductStatusConflictException extends ProductConflictException {
    public enum Code {
        VERSION_CONFLICT,
        INVALID_TRANSITION,
        UNARCHIVED_VARIANT,
        CONCURRENT_MODIFICATION
    }

    public record Conflict(UUID productId, Code code, UUID variantId) {
        public Conflict(UUID productId, Code code) {
            this(productId, code, null);
        }
    }

    private final List<Conflict> conflicts;

    public ProductStatusConflictException(List<Conflict> conflicts) {
        super("Product status transition conflicts with the current state");
        this.conflicts = List.copyOf(conflicts);
    }

    public List<Conflict> conflicts() {
        return conflicts;
    }
}
