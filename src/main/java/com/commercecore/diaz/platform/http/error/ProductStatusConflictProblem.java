package com.commercecore.diaz.platform.http.error;

import com.commercecore.diaz.catalog.application.ProductStatusConflictException.Code;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import java.util.List;
import java.util.UUID;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

@Schema(allOf = ProblemDetails.class, requiredProperties = "conflicts")
public record ProductStatusConflictProblem(
        @JsonUnwrapped @Schema(hidden = true) ProblemDetails problem,
        @Schema(minItems = 1, description = "Conflictos de productos del tenant autorizado.") List<Conflict> conflicts) {

    @Schema(name = "ProductStatusConflict", requiredProperties = {"productId", "code"})
    public record Conflict(
            @Schema(format = "uuid") UUID productId,
            Code code,
            @JsonInclude(JsonInclude.Include.NON_NULL)
            @Schema(format = "uuid", description = "Variante bloqueante para UNARCHIVED_VARIANT.") UUID variantId) { }
}
