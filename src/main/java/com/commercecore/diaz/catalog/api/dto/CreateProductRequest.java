package com.commercecore.diaz.catalog.api.dto;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.databind.JsonNode;
import com.commercecore.diaz.catalog.domain.Product;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.Map;
import java.util.UUID;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
public record CreateProductRequest(@NotNull UUID categoryId,
                                   @Size(max=80) @Schema(description="Obligatorio para tipos vendibles; omitir para VARIABLE") String sku,
                                   @NotBlank @Size(max=200) String name,String description,
                                   @jakarta.validation.constraints.Pattern(regexp="[A-Z][A-Z0-9_]{0,31}") String baseUnit,
                                   boolean fractionalQuantitiesEnabled,Product.Type type,UUID parentId,
                                   @Schema(description="Mapa de códigos y valores textuales de la variante",additionalProperties=String.class)
                                   Map<String,JsonNode> attributes) {
    @JsonAnySetter public void rejectUnknown(String name,Object value){throw new IllegalArgumentException("Unknown property");}
}
