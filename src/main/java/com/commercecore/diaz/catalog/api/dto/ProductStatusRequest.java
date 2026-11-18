package com.commercecore.diaz.catalog.api.dto;

import java.util.List;
import java.util.UUID;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

@Schema(requiredProperties = {"target", "version"}, additionalProperties = Schema.False.class)
public record ProductStatusRequest(
        Target target,
        @Schema(minimum = "0") long version,
        @Schema(nullable = true, description = "Motivo obligatorio para INACTIVE y ARCHIVED.") String reason) {

    public enum Target { PENDING_RECEIPT, ACTIVE, INACTIVE, ARCHIVED }

    @Schema(name = "ProductStatusBatchRequest", requiredProperties = {"target", "items"},
            additionalProperties = Schema.False.class)
    public record Batch(
            Target target,
            @Schema(nullable = true, description = "Motivo común obligatorio para INACTIVE y ARCHIVED.") String reason,
            @Schema(minItems = 1, description = "Cada producto aparece una sola vez.") List<Item> items) { }

    @Schema(name = "ProductStatusBatchItem", requiredProperties = {"productId", "version"},
            additionalProperties = Schema.False.class)
    public record Item(@Schema(format = "uuid") UUID productId, @Schema(minimum = "0") long version) { }
}
