package com.commercecore.diaz.catalog.api.dto;
import java.util.UUID;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
@Schema(description="Versión obligatoria; omitir campos conserva el valor y null elimina descripción",example="{\"version\":0,\"name\":\"Filtro actualizado\"}")
public record ProductPatchRequest(long version,String name,String description,UUID categoryId) { }
