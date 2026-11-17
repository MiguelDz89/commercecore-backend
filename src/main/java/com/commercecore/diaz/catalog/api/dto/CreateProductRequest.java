package com.commercecore.diaz.catalog.api.dto;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;
public record CreateProductRequest(@NotNull UUID categoryId,@NotBlank @Size(max=80) String sku,
                                   @NotBlank @Size(max=200) String name,String description) {
    @JsonAnySetter public void rejectUnknown(String name,Object value){throw new IllegalArgumentException("Unknown property");}
}
