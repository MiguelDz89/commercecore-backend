package com.commercecore.diaz.catalog.api.dto;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;
public record CreateCategoryRequest(@NotBlank @Size(max=80) String code,@NotBlank @Size(max=160) String name,
                                    String description,UUID parentCategoryId) {
    @JsonAnySetter public void rejectUnknown(String name,Object value) { throw new IllegalArgumentException("Unknown property"); }
}
