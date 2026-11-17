package com.commercecore.diaz.catalog.api;

import com.commercecore.diaz.catalog.api.dto.CategoryPageResponse;
import com.commercecore.diaz.catalog.api.dto.CategoryPatchRequest;
import com.commercecore.diaz.catalog.api.dto.CategoryResponse;
import com.commercecore.diaz.catalog.api.dto.CreateCategoryRequest;
import com.commercecore.diaz.catalog.application.CategoryAdministration;
import com.commercecore.diaz.catalog.application.CategoryPatch;
import com.commercecore.diaz.platform.http.error.ProblemDetails;
import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.security.Authenticated;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.Set;
import java.util.UUID;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.parameters.RequestBody;
import org.eclipse.microprofile.openapi.annotations.enums.SchemaType;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;

@Path("/api/v1/tenants/{tenantId}/categories") @Authenticated @Produces(MediaType.APPLICATION_JSON)
@SecurityRequirement(name="bearerAuth")
@APIResponse(responseCode="400",description="Datos inválidos",content=@Content(mediaType="application/problem+json",schema=@Schema(implementation=ProblemDetails.class)))
@APIResponse(responseCode="401",description="Bearer requerido",content=@Content(mediaType="application/problem+json",schema=@Schema(implementation=ProblemDetails.class)))
@APIResponse(responseCode="404",description="Recurso no disponible",content=@Content(mediaType="application/problem+json",schema=@Schema(implementation=ProblemDetails.class)))
@APIResponse(responseCode="409",description="Conflicto de categoría",content=@Content(mediaType="application/problem+json",schema=@Schema(implementation=ProblemDetails.class)))
@APIResponse(responseCode="500",description="Error interno",content=@Content(mediaType="application/problem+json",schema=@Schema(implementation=ProblemDetails.class)))
@APIResponse(responseCode="503",description="Dependencia no disponible",content=@Content(mediaType="application/problem+json",schema=@Schema(implementation=ProblemDetails.class)))
public class CategoryResource {
    private final CategoryAdministration categories;
    public CategoryResource(CategoryAdministration categories) { this.categories=categories; }
    @GET @Operation(summary="Listar y buscar categorías; PRODUCT_READ o CATEGORY_MANAGE")
    @APIResponse(responseCode="200",description="Página de categorías",content=@Content(schema=@Schema(implementation=CategoryPageResponse.class)))
    public CategoryPageResponse list(@PathParam("tenantId") String tenant,@Parameter(description="Desplazamiento no negativo",schema=@Schema(type=SchemaType.INTEGER,minimum="0",defaultValue="0")) @QueryParam("offset") @DefaultValue("0") String offset,
            @Parameter(description="Tamaño de página entre 1 y 100",schema=@Schema(type=SchemaType.INTEGER,minimum="1",maximum="100",defaultValue="20")) @QueryParam("limit") @DefaultValue("20") String limit,@QueryParam("search") String search) {
        int o=number(offset,0,Integer.MAX_VALUE),l=number(limit,1,100);
        return new CategoryPageResponse(categories.list(id(tenant),o,l,search).stream().map(CategoryResponse::from).toList(),o,l);
    }
    @GET @Path("/{categoryId}") @Operation(summary="Consultar categoría; PRODUCT_READ o CATEGORY_MANAGE")
    @APIResponse(responseCode="200",description="Categoría",content=@Content(schema=@Schema(implementation=CategoryResponse.class)))
    public CategoryResponse get(@PathParam("tenantId") String tenant,@PathParam("categoryId") String category) {
        return CategoryResponse.from(categories.get(id(tenant),id(category)));
    }
    @POST @Consumes(MediaType.APPLICATION_JSON) @Operation(summary="Crear categoría; CATEGORY_MANAGE")
    @APIResponse(responseCode="201",description="Categoría creada",content=@Content(schema=@Schema(implementation=CategoryResponse.class)))
    public Response create(@PathParam("tenantId") String tenant,@NotNull @Valid CreateCategoryRequest body) {
        return Response.status(201).entity(CategoryResponse.from(categories.create(id(tenant),body.code(),body.name(),body.description(),body.parentCategoryId()))).build();
    }
    @PATCH @Path("/{categoryId}") @Consumes(MediaType.APPLICATION_JSON) @Operation(summary="Actualizar nombre, descripción o padre; CATEGORY_MANAGE")
    @APIResponse(responseCode="200",description="Categoría modificada",content=@Content(schema=@Schema(implementation=CategoryResponse.class)))
    @RequestBody(required=true,content=@Content(schema=@Schema(implementation=CategoryPatchRequest.class)))
    public CategoryResponse update(@PathParam("tenantId") String tenant,@PathParam("categoryId") String category,JsonNode body) {
        if(body==null || !body.isObject()) throw new IllegalArgumentException("Invalid request");
        var allowed=Set.of("name","description","parentCategoryId");
        body.fieldNames().forEachRemaining(field -> { if(!allowed.contains(field)) throw new IllegalArgumentException("Unknown field"); });
        String name=text(body,"name",false),description=text(body,"description",true),parent=text(body,"parentCategoryId",true);
        var patch=new CategoryPatch(body.has("name"),name,body.has("description"),description,body.has("parentCategoryId"),parent==null?null:id(parent));
        return CategoryResponse.from(categories.update(id(tenant),id(category),patch));
    }
    @POST @Path("/{categoryId}/deactivation") @Operation(summary="Desactivar categoría; CATEGORY_MANAGE")
    @APIResponse(responseCode="200",description="Categoría desactivada",content=@Content(schema=@Schema(implementation=CategoryResponse.class)))
    public CategoryResponse deactivate(@PathParam("tenantId") String tenant,@PathParam("categoryId") String category) {
        return CategoryResponse.from(categories.deactivate(id(tenant),id(category)));
    }
    @POST @Path("/{categoryId}/reactivation") @Operation(summary="Reactivar categoría; CATEGORY_MANAGE")
    @APIResponse(responseCode="200",description="Categoría reactivada",content=@Content(schema=@Schema(implementation=CategoryResponse.class)))
    public CategoryResponse reactivate(@PathParam("tenantId") String tenant,@PathParam("categoryId") String category) {
        return CategoryResponse.from(categories.reactivate(id(tenant),id(category)));
    }
    private static String text(JsonNode body,String field,boolean nullable) {
        JsonNode value=body.get(field);if(value==null)return null;
        if(value.isNull() && nullable)return null;
        if(!value.isTextual())throw new IllegalArgumentException("Invalid field");return value.textValue();
    }
    private static UUID id(String value) { try{return UUID.fromString(value);}catch(RuntimeException e){throw new IllegalArgumentException("Invalid identifier");} }
    private static int number(String value,int min,int max) { try{int n=Integer.parseInt(value);if(n<min||n>max)throw new IllegalArgumentException("Invalid page");return n;}catch(NumberFormatException e){throw new IllegalArgumentException("Invalid page");} }
}
