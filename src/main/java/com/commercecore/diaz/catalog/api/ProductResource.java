package com.commercecore.diaz.catalog.api;

import com.commercecore.diaz.catalog.api.dto.*;
import com.commercecore.diaz.catalog.application.CreateProduct;
import com.commercecore.diaz.catalog.application.ProductAdministration;
import com.commercecore.diaz.catalog.application.ProductPatch;
import com.commercecore.diaz.catalog.application.ProductStatusChange;
import com.commercecore.diaz.catalog.domain.Product;
import com.commercecore.diaz.platform.http.error.ProblemDetails;
import com.commercecore.diaz.platform.http.error.ProductStatusConflictProblem;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import io.quarkus.security.Authenticated;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
    private final ObjectReader statusRequestReader;

    public ProductResource(ProductAdministration products, ObjectMapper mapper) {
        this.products = products;
        this.statusRequestReader = mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY);
    }
    @POST @Consumes(MediaType.APPLICATION_JSON) @Operation(summary="Crear un producto en borrador; PRODUCT_CREATE")
    @APIResponse(responseCode="201",description="Producto creado",content=@Content(schema=@Schema(implementation=ProductResponse.class)))
    public Response create(@PathParam("tenantId") String tenant,@NotNull @Valid CreateProductRequest body){
        var attributes=new HashMap<String,String>();
        if (body.attributes()!=null) body.attributes().forEach((key,value)->attributes.put(key,attribute(value)));
        var command=new CreateProduct(body.categoryId(),body.sku(),body.name(),body.description(),
                body.baseUnit()==null?Product.DEFAULT_BASE_UNIT:body.baseUnit(),body.fractionalQuantitiesEnabled(),
                body.type()==null?Product.Type.SIMPLE:body.type(),body.parentId(),attributes);
        return Response.status(201).entity(ProductResponse.from(products.create(id(tenant),command))).build();
    }
    @GET @Operation(summary="Buscar productos o variantes; PRODUCT_READ")
    @APIResponse(responseCode="200",description="Página de productos",content=@Content(schema=@Schema(implementation=ProductPageResponse.class)))
    public ProductPageResponse list(@PathParam("tenantId") String tenant,
            @Parameter(schema=@Schema(type=SchemaType.INTEGER,minimum="0",defaultValue="0")) @QueryParam("offset") @DefaultValue("0") String offset,
            @Parameter(schema=@Schema(type=SchemaType.INTEGER,minimum="1",maximum="100",defaultValue="20")) @QueryParam("limit") @DefaultValue("20") String limit,
            @QueryParam("sku") String sku,@QueryParam("name") String name,@QueryParam("categoryId") String category,
            @QueryParam("status") String status,@QueryParam("type") String type,@QueryParam("parentId") String parent){
        int o=number(offset,0,Integer.MAX_VALUE),l=number(limit,1,100);
        Product.Status state=status==null?null:Product.Status.valueOf(status);
        Product.Type structure=type==null?null:Product.Type.valueOf(type);
        return new ProductPageResponse(products.list(id(tenant),o,l,sku,name,category==null?null:id(category),state,structure,
                parent==null?null:id(parent)).stream().map(ProductResponse::from).toList(),o,l);
    }
    @GET @Path("/{productId}") @Operation(summary="Consultar producto o variante; PRODUCT_READ")
    @APIResponse(responseCode="200",description="Producto",content=@Content(schema=@Schema(implementation=ProductResponse.class)))
    public ProductResponse get(@PathParam("tenantId") String tenant,@PathParam("productId") String product){return ProductResponse.from(products.get(id(tenant),id(product)));}
    @PATCH @Path("/{productId}") @Consumes(MediaType.APPLICATION_JSON) @Operation(summary="Actualizar producto o variante con versión; PRODUCT_UPDATE")
    @RequestBody(required=true,content=@Content(schema=@Schema(implementation=ProductPatchRequest.class)))
    @APIResponse(responseCode="200",description="Producto actualizado",content=@Content(schema=@Schema(implementation=ProductResponse.class)))
    public ProductResponse update(@PathParam("tenantId") String tenant,@PathParam("productId") String product,JsonNode body){
        if(body==null || !body.isObject())throw new IllegalArgumentException("Invalid request");
        var allowed=Set.of("version","name","description","categoryId","baseUnit","fractionalQuantitiesEnabled","attributes");body.fieldNames().forEachRemaining(f->{if(!allowed.contains(f))throw new IllegalArgumentException("Unknown field");});
        JsonNode v=body.get("version");if(v==null || !v.isIntegralNumber() || !v.canConvertToLong() || v.longValue()<0)throw new IllegalArgumentException("Invalid version");
        String name=text(body,"name",false),description=text(body,"description",true),category=text(body,"categoryId",false);
        JsonNode fractional=body.get("fractionalQuantitiesEnabled");
        if(fractional!=null && !fractional.isBoolean())throw new IllegalArgumentException("Invalid fractional quantity setting");
        return ProductResponse.from(products.update(id(tenant),id(product),new ProductPatch(v.longValue(),body.has("name"),name,
                body.has("description"),description,body.has("categoryId"),category==null?null:id(category),
                text(body,"baseUnit",false),fractional==null?null:fractional.booleanValue(),attributes(body.get("attributes")))));
    }
    @PATCH
    @Path("/{productId}/status")
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(summary = "Cambiar el estado de un producto o variante", description = "Requiere PRODUCT_STATUS_UPDATE.")
    @RequestBody(required = true, content = @Content(schema = @Schema(implementation = ProductStatusRequest.class)))
    @APIResponse(responseCode = "200", description = "Estado actualizado",
            content = @Content(schema = @Schema(implementation = ProductStatusChangeResponse.class)))
    @APIResponse(responseCode = "409", description = "Conflicto de estado, versión o concurrencia",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(anyOf = {ProblemDetails.class, ProductStatusConflictProblem.class})))
    public ProductStatusChangeResponse changeStatus(
            @Parameter(schema = @Schema(format = "uuid", type = SchemaType.STRING)) @PathParam("tenantId") String tenant,
            @Parameter(schema = @Schema(format = "uuid", type = SchemaType.STRING)) @PathParam("productId") String product,
            String json) {
        JsonNode body = statusRequest(json, Set.of("target", "version", "reason"));
        var item = new ProductStatusChange.Item(statusId(product), statusVersion(body));
        var change = new ProductStatusChange(statusTarget(body), text(body, "reason", true), List.of(item));
        return new ProductStatusChangeResponse(products.changeStatus(statusId(tenant), change));
    }

    @POST
    @Path("/status-changes")
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(summary = "Cambiar el estado de un lote de productos o variantes",
            description = "Requiere PRODUCT_STATUS_UPDATE. La operación se confirma o se revierte en su totalidad.")
    @RequestBody(required = true, content = @Content(schema = @Schema(implementation = ProductStatusRequest.Batch.class)))
    @APIResponse(responseCode = "200", description = "Estados del lote actualizados",
            content = @Content(schema = @Schema(implementation = ProductStatusChangeResponse.class)))
    @APIResponse(responseCode = "409", description = "Conflicto de estado, versión o concurrencia del lote",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(anyOf = {ProblemDetails.class, ProductStatusConflictProblem.class})))
    public ProductStatusChangeResponse changeStatuses(
            @Parameter(schema = @Schema(format = "uuid", type = SchemaType.STRING)) @PathParam("tenantId") String tenant,
            String json) {
        JsonNode body = statusRequest(json, Set.of("target", "items", "reason"));
        JsonNode nodes = body.path("items");
        if (!nodes.isArray()) throw new IllegalArgumentException("Invalid product list");

        var items = new ArrayList<ProductStatusChange.Item>();
        for (JsonNode node : nodes) {
            requireFields(node, Set.of("productId", "version"));
            items.add(new ProductStatusChange.Item(statusId(text(node, "productId", false)), statusVersion(node)));
        }
        var change = new ProductStatusChange(statusTarget(body), text(body, "reason", true), items);
        return new ProductStatusChangeResponse(products.changeStatus(statusId(tenant), change));
    }

    private JsonNode statusRequest(String json, Set<String> allowed) {
        if (json == null) throw new IllegalArgumentException("Invalid request");
        try {
            JsonNode body = statusRequestReader.readTree(json);
            requireFields(body, allowed);
            return body;
        } catch (JsonProcessingException error) {
            throw new IllegalArgumentException("Invalid request", error);
        }
    }

    private static void requireFields(JsonNode body, Set<String> allowed) {
        if (body == null || !body.isObject()) throw new IllegalArgumentException("Invalid request");
        body.fieldNames().forEachRemaining(field -> {
            if (!allowed.contains(field)) throw new IllegalArgumentException("Unknown field");
        });
    }

    private static Product.Status statusTarget(JsonNode body) {
        String target = text(body, "target", false);
        if (target == null) throw new IllegalArgumentException("A target status is required");
        return Product.Status.valueOf(ProductStatusRequest.Target.valueOf(target).name());
    }

    private static long statusVersion(JsonNode body) {
        JsonNode version = body.path("version");
        if (!version.isIntegralNumber() || !version.canConvertToLong())
            throw new IllegalArgumentException("Invalid version");
        return version.longValue();
    }

    private static UUID statusId(String value) {
        UUID uuid = id(value);
        if (!uuid.toString().equalsIgnoreCase(value)) throw new IllegalArgumentException("Invalid identifier");
        return uuid;
    }

    private static Map<String,String> attributes(JsonNode node) {
        if (node==null) return null;
        if (!node.isObject()) throw new IllegalArgumentException("Invalid attributes");
        var attributes=new HashMap<String,String>();
        node.fields().forEachRemaining(entry->attributes.put(entry.getKey(),attribute(entry.getValue())));
        return attributes;
    }
    private static String attribute(JsonNode value) {
        if (value==null || !value.isTextual()) throw new IllegalArgumentException("Invalid attribute");
        return value.textValue();
    }
    private static String text(JsonNode body,String field,boolean nullable){JsonNode v=body.get(field);if(v==null)return null;if(v.isNull() && nullable)return null;if(!v.isTextual())throw new IllegalArgumentException("Invalid field");return v.textValue();}
    private static UUID id(String value){try{return UUID.fromString(value);}catch(RuntimeException e){throw new IllegalArgumentException("Invalid identifier");}}
    private static int number(String value,int min,int max){try{int n=Integer.parseInt(value);if(n<min||n>max)throw new IllegalArgumentException("Invalid page");return n;}catch(NumberFormatException e){throw new IllegalArgumentException("Invalid page");}}
}
