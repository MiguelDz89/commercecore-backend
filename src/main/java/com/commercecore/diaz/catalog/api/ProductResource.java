package com.commercecore.diaz.catalog.api;

import com.commercecore.diaz.catalog.api.dto.*;
import com.commercecore.diaz.catalog.application.ProductAdministration;
import com.commercecore.diaz.catalog.application.ProductPatch;
import com.commercecore.diaz.catalog.domain.Product;
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
import org.eclipse.microprofile.openapi.annotations.enums.SchemaType;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.parameters.RequestBody;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;

@Path("/api/v1/tenants/{tenantId}/products") @Authenticated @Produces(MediaType.APPLICATION_JSON)
@SecurityRequirement(name="bearerAuth")
@APIResponse(responseCode="400",description="Datos inválidos",content=@Content(mediaType="application/problem+json",schema=@Schema(implementation=ProblemDetails.class)))
@APIResponse(responseCode="401",description="Bearer requerido",content=@Content(mediaType="application/problem+json",schema=@Schema(implementation=ProblemDetails.class)))
@APIResponse(responseCode="404",description="Recurso no disponible",content=@Content(mediaType="application/problem+json",schema=@Schema(implementation=ProblemDetails.class)))
@APIResponse(responseCode="409",description="Conflicto de producto",content=@Content(mediaType="application/problem+json",schema=@Schema(implementation=ProblemDetails.class)))
@APIResponse(responseCode="500",description="Error interno",content=@Content(mediaType="application/problem+json",schema=@Schema(implementation=ProblemDetails.class)))
@APIResponse(responseCode="503",description="Dependencia no disponible",content=@Content(mediaType="application/problem+json",schema=@Schema(implementation=ProblemDetails.class)))
public class ProductResource {
    private final ProductAdministration products;
    public ProductResource(ProductAdministration products){this.products=products;}
    @POST @Consumes(MediaType.APPLICATION_JSON) @Operation(summary="Crear producto simple en borrador; PRODUCT_CREATE")
    @APIResponse(responseCode="201",description="Producto creado",content=@Content(schema=@Schema(implementation=ProductResponse.class)))
    public Response create(@PathParam("tenantId") String tenant,@NotNull @Valid CreateProductRequest body){
        return Response.status(201).entity(ProductResponse.from(products.create(id(tenant),body.categoryId(),body.sku(),body.name(),body.description()))).build();
    }
    @GET @Operation(summary="Buscar productos simples; PRODUCT_READ")
    @APIResponse(responseCode="200",description="Página de productos",content=@Content(schema=@Schema(implementation=ProductPageResponse.class)))
    public ProductPageResponse list(@PathParam("tenantId") String tenant,
            @Parameter(schema=@Schema(type=SchemaType.INTEGER,minimum="0",defaultValue="0")) @QueryParam("offset") @DefaultValue("0") String offset,
            @Parameter(schema=@Schema(type=SchemaType.INTEGER,minimum="1",maximum="100",defaultValue="20")) @QueryParam("limit") @DefaultValue("20") String limit,
            @QueryParam("sku") String sku,@QueryParam("name") String name,@QueryParam("categoryId") String category,@QueryParam("status") String status){
        int o=number(offset,0,Integer.MAX_VALUE),l=number(limit,1,100);
        Product.Status state=status==null?null:Product.Status.valueOf(status);
        return new ProductPageResponse(products.list(id(tenant),o,l,sku,name,category==null?null:id(category),state).stream().map(ProductResponse::from).toList(),o,l);
    }
    @GET @Path("/{productId}") @Operation(summary="Consultar producto simple; PRODUCT_READ")
    @APIResponse(responseCode="200",description="Producto",content=@Content(schema=@Schema(implementation=ProductResponse.class)))
    public ProductResponse get(@PathParam("tenantId") String tenant,@PathParam("productId") String product){return ProductResponse.from(products.get(id(tenant),id(product)));}
    @PATCH @Path("/{productId}") @Consumes(MediaType.APPLICATION_JSON) @Operation(summary="Actualizar producto simple con versión; PRODUCT_UPDATE")
    @RequestBody(required=true,content=@Content(schema=@Schema(implementation=ProductPatchRequest.class)))
    @APIResponse(responseCode="200",description="Producto actualizado",content=@Content(schema=@Schema(implementation=ProductResponse.class)))
    public ProductResponse update(@PathParam("tenantId") String tenant,@PathParam("productId") String product,JsonNode body){
        if(body==null || !body.isObject())throw new IllegalArgumentException("Invalid request");
        var allowed=Set.of("version","name","description","categoryId");body.fieldNames().forEachRemaining(f->{if(!allowed.contains(f))throw new IllegalArgumentException("Unknown field");});
        JsonNode v=body.get("version");if(v==null || !v.isIntegralNumber() || !v.canConvertToLong() || v.longValue()<0)throw new IllegalArgumentException("Invalid version");
        String name=text(body,"name",false),description=text(body,"description",true),category=text(body,"categoryId",false);
        return ProductResponse.from(products.update(id(tenant),id(product),new ProductPatch(v.longValue(),body.has("name"),name,
                body.has("description"),description,body.has("categoryId"),category==null?null:id(category))));
    }
    private static String text(JsonNode body,String field,boolean nullable){JsonNode v=body.get(field);if(v==null)return null;if(v.isNull() && nullable)return null;if(!v.isTextual())throw new IllegalArgumentException("Invalid field");return v.textValue();}
    private static UUID id(String value){try{return UUID.fromString(value);}catch(RuntimeException e){throw new IllegalArgumentException("Invalid identifier");}}
    private static int number(String value,int min,int max){try{int n=Integer.parseInt(value);if(n<min||n>max)throw new IllegalArgumentException("Invalid page");return n;}catch(NumberFormatException e){throw new IllegalArgumentException("Invalid page");}}
}
