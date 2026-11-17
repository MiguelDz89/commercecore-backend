package com.commercecore.diaz.catalog.api.dto;
import java.util.UUID;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
@Schema(description="PATCH parcial: omitir un campo lo conserva; null elimina descripción o padre",example="{\"name\":\"Repuestos\",\"parentCategoryId\":null}")
public record CategoryPatchRequest(String name,String description,UUID parentCategoryId) { }
