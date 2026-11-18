package com.commercecore.diaz.catalog.api;

import com.commercecore.diaz.catalog.application.ProductPriceAdministration;
import com.commercecore.diaz.catalog.domain.PriceSchedule;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.quarkus.security.Authenticated;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.RequestBody;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.enums.SchemaType;
import com.commercecore.diaz.platform.http.error.ProblemDetails;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Path("/api/v1/tenants/{tenantId}/products/{productId}/prices")
@Authenticated @Produces(MediaType.APPLICATION_JSON) @SecurityRequirement(name="bearerAuth")
@APIResponse(responseCode="400",description="Solicitud inválida",content=@Content(mediaType="application/problem+json",schema=@Schema(implementation=ProblemDetails.class)))
@APIResponse(responseCode="401",description="Autenticación requerida; desafío Bearer",content=@Content(mediaType="application/problem+json",schema=@Schema(implementation=ProblemDetails.class)))
@APIResponse(responseCode="404",description="Recurso no disponible",content=@Content(mediaType="application/problem+json",schema=@Schema(implementation=ProblemDetails.class)))
@APIResponse(responseCode="500",description="Error interno",content=@Content(mediaType="application/problem+json",schema=@Schema(implementation=ProblemDetails.class)))
@APIResponse(responseCode="503",description="Dependencia no disponible",content=@Content(mediaType="application/problem+json",schema=@Schema(implementation=ProblemDetails.class)))
public class ProductPriceResource {
    private static final String AMOUNT_UPPER_BOUND = "10000000000000000";
    private static final String QUANTITY_UPPER_BOUND = "1000000000000000";
    private static final String CONFLICT = "Conflicto de versión o estado";
    private static final ObjectMapper EXACT_JSON = new ObjectMapper()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .setNodeFactory(JsonNodeFactory.withExactBigDecimals(true));
    private final ProductPriceAdministration prices;
    public ProductPriceResource(ProductPriceAdministration prices){this.prices=prices;}
    @Schema(requiredProperties={"configured","retailPrice","currency","version"})
    public record SaleResponse(boolean configured,
            @Schema(nullable=true, minimum="0", maximum=AMOUNT_UPPER_BOUND, exclusiveMaximum=true, multipleOf=0.01) BigDecimal retailPrice,
            String currency, @Schema(minimum="0") long version) { }

    @Schema(requiredProperties={"cost","version","currency","acquisition"})
    public record CostResponse(
            @Schema(nullable=true, minimum="0", maximum=AMOUNT_UPPER_BOUND, exclusiveMaximum=true, multipleOf=0.01) BigDecimal cost,
            @Schema(minimum="0") long version, String currency, @Schema(nullable=true) AcquisitionResponse acquisition) { }

    @Schema(requiredProperties={"sourceAmount","sourceCurrency","operationDate","rateDate","rate","recordedBy"})
    public record AcquisitionResponse(
            @Schema(minimum="0", maximum=AMOUNT_UPPER_BOUND, exclusiveMaximum=true, multipleOf=0.01) BigDecimal sourceAmount,
            String sourceCurrency,
            @Schema(type=SchemaType.STRING,format="date") String operationDate,
            @Schema(nullable=true,type=SchemaType.STRING,format="date") String rateDate,
            @Schema(nullable=true,minimum="0.00000001",maximum="10000000000",exclusiveMaximum=true,multipleOf=0.00000001)
            BigDecimal rate,
            @Schema(type=SchemaType.STRING,format="uuid") UUID recordedBy) { }

    @Schema(requiredProperties={"quantity","unitPrice"})
    public record QuoteResponse(@Schema(minimum="0.001", maximum=QUANTITY_UPPER_BOUND, exclusiveMaximum=true, multipleOf=0.001) BigDecimal quantity,
            @Schema(minimum="0", maximum=AMOUNT_UPPER_BOUND, exclusiveMaximum=true, multipleOf=0.01) BigDecimal unitPrice) { }

    @Schema(requiredProperties={"minimumQuantity","unitPrice"}, additionalProperties=Schema.False.class)
    public record PriceTier(
            @Schema(minimum="0.001", maximum=QUANTITY_UPPER_BOUND, exclusiveMaximum=true, multipleOf=0.001) BigDecimal minimumQuantity,
            @Schema(minimum="0", maximum=AMOUNT_UPPER_BOUND, exclusiveMaximum=true, multipleOf=0.01) BigDecimal unitPrice) { }

    @Schema(requiredProperties={"minimumQuantity","unitPrice"}, additionalProperties=Schema.False.class)
    public record PriceTierRequest(
            @Schema(minimum="2", maximum=QUANTITY_UPPER_BOUND, exclusiveMaximum=true, multipleOf=0.001) BigDecimal minimumQuantity,
            @Schema(minimum="0", maximum=AMOUNT_UPPER_BOUND, exclusiveMaximum=true, multipleOf=0.01) BigDecimal unitPrice) { }

    @Schema(requiredProperties={"version","retailPrice","tiers"}, additionalProperties=Schema.False.class)
    public record SaleRequest(@Schema(minimum="0") long version,
            @Schema(minimum="0", maximum=AMOUNT_UPPER_BOUND, exclusiveMaximum=true, multipleOf=0.01,
                    description="Importe JSON con hasta dos decimales") BigDecimal retailPrice,
            @Schema(description="Lista completa, con umbrales distintos y precios no crecientes por umbral. Puede estar vacía.")
            List<PriceTierRequest> tiers) { }

    @Schema(requiredProperties={"version","cost"}, additionalProperties=Schema.False.class)
    public record CostRequest(@Schema(minimum="0") long version,
            @Schema(nullable=true, minimum="0", maximum=AMOUNT_UPPER_BOUND, exclusiveMaximum=true, multipleOf=0.01,
                    description="Importe en la moneda de origen. Use null para retirar el coste.") BigDecimal cost,
            String sourceCurrency,@Schema(type=SchemaType.STRING,format="date") String operationDate) { }

    @GET @Operation(summary="Consultar precio minorista; PRODUCT_READ")
    @APIResponse(responseCode="200",description="Precio minorista",content=@Content(schema=@Schema(implementation=SaleResponse.class)))
    public SaleResponse sale(@Parameter(schema=@Schema(type=SchemaType.STRING,format="uuid")) @PathParam("tenantId") String tenant,@Parameter(schema=@Schema(type=SchemaType.STRING,format="uuid")) @PathParam("productId") String product){
        var p=prices.sale(id(tenant),id(product));return new SaleResponse(p.configured(),p.configured()?p.price():null,p.currency(),p.version());
    }
    @GET @Path("/tiers") @Operation(summary="Consultar escalones; PRODUCT_PRICE_TIER_READ")
    @APIResponse(responseCode="200",description="Escalones")
    public List<PriceTier> tiers(@Parameter(schema=@Schema(type=SchemaType.STRING,format="uuid")) @PathParam("tenantId") String tenant,@Parameter(schema=@Schema(type=SchemaType.STRING,format="uuid")) @PathParam("productId") String product){return prices.tiers(id(tenant),id(product)).stream()
                .map(tier -> new PriceTier(tier.minimumQuantity(), tier.unitPrice())).toList();}
    @GET @Path("/cost") @Operation(summary="Consultar coste; PRODUCT_COST_READ")
    @APIResponse(responseCode="200",description="Coste opcional",content=@Content(schema=@Schema(implementation=CostResponse.class)))
    public CostResponse cost(@Parameter(schema=@Schema(type=SchemaType.STRING,format="uuid")) @PathParam("tenantId") String tenant,@Parameter(schema=@Schema(type=SchemaType.STRING,format="uuid")) @PathParam("productId") String product){
        var cost=prices.cost(id(tenant),id(product));
        var evidence=cost.acquisition();
        var response=evidence==null?null:new AcquisitionResponse(evidence.sourceAmount(),evidence.sourceCurrency(),
                evidence.operationDate().toString(),evidence.rateDate()==null?null:evidence.rateDate().toString(),
                evidence.rate(),evidence.recordedBy());
        return new CostResponse(cost.price(),cost.version(),cost.currency(),response);
    }
    @GET @Path("/quote") @Operation(summary="Resolver precio unitario; PRODUCT_PRICE_TIER_READ")
    @APIResponse(responseCode="200",description="Cotización",content=@Content(schema=@Schema(implementation=QuoteResponse.class)))
    @APIResponse(responseCode="409",description="Precio sin configurar, moneda histórica discrepante o escalones incompatibles",content=@Content(mediaType="application/problem+json",schema=@Schema(implementation=ProblemDetails.class)))
    public QuoteResponse quote(@Parameter(schema=@Schema(type=SchemaType.STRING,format="uuid")) @PathParam("tenantId") String tenant,@Parameter(schema=@Schema(type=SchemaType.STRING,format="uuid")) @PathParam("productId") String product,@Parameter(required=true,schema=@Schema(type=SchemaType.NUMBER, minimum="0.001", maximum=QUANTITY_UPPER_BOUND, exclusiveMaximum=true, multipleOf=0.001, description="Parte decimal solo si está habilitada en empresa y producto. Use punto decimal.")) @QueryParam("quantity") String quantity){
        if(quantity==null || !quantity.matches("[0-9]{1,15}(?:\\.[0-9]{1,3})?"))
            throw new IllegalArgumentException("Invalid quantity");
        BigDecimal q=new BigDecimal(quantity);
        return new QuoteResponse(q,prices.quote(id(tenant),id(product),q));
    }
    @PUT @Path("/sale") @Consumes(MediaType.APPLICATION_JSON) @Operation(summary="Reemplazar precio minorista y escalones; PRODUCT_PRICE_UPDATE")
    @RequestBody(required=true,content=@Content(schema=@Schema(implementation=SaleRequest.class)))
    @APIResponse(responseCode="204",description="Precio actualizado")
    @APIResponse(responseCode="409",description=CONFLICT,content=@Content(mediaType="application/problem+json",schema=@Schema(implementation=ProblemDetails.class)))
    public Response updateSale(@Parameter(schema=@Schema(type=SchemaType.STRING,format="uuid")) @PathParam("tenantId") String tenant,@Parameter(schema=@Schema(type=SchemaType.STRING,format="uuid")) @PathParam("productId") String product,String json){
        JsonNode body=parse(json);
        fields(body,Set.of("version","retailPrice","tiers"));long version=version(body);BigDecimal price=amount(body.get("retailPrice"));JsonNode nodes=body.get("tiers");
        if(nodes==null||!nodes.isArray())throw new IllegalArgumentException("Invalid tiers");var tiers=new ArrayList<PriceSchedule.Tier>();
        for(JsonNode node:nodes){fields(node,Set.of("minimumQuantity","unitPrice"));var q=node.get("minimumQuantity");if(q==null||!q.isNumber())throw new IllegalArgumentException("Invalid quantity");tiers.add(new PriceSchedule.Tier(q.decimalValue(),amount(node.get("unitPrice"))));}
        prices.updateSale(id(tenant),id(product),version,new PriceSchedule(price,tiers));return Response.noContent().build();
    }
    @PUT @Path("/cost") @Consumes(MediaType.APPLICATION_JSON) @Operation(summary="Actualizar coste; PRODUCT_COST_UPDATE")
    @RequestBody(required=true,content=@Content(schema=@Schema(implementation=CostRequest.class)))
    @APIResponse(responseCode="204",description="Coste actualizado")
    @APIResponse(responseCode="409",description=CONFLICT,content=@Content(mediaType="application/problem+json",schema=@Schema(implementation=ProblemDetails.class)))
    public Response updateCost(@Parameter(schema=@Schema(type=SchemaType.STRING,format="uuid")) @PathParam("tenantId") String tenant,@Parameter(schema=@Schema(type=SchemaType.STRING,format="uuid")) @PathParam("productId") String product,String json){
        JsonNode body=parse(json);
        fields(body,Set.of("version","cost","sourceCurrency","operationDate"));var value=body.get("cost");if(value==null)throw new IllegalArgumentException("Cost required");
        String source=text(body,"sourceCurrency");
        String date=text(body,"operationDate");
        prices.updateCost(id(tenant),id(product),version(body),value.isNull()?null:amount(value),
                source,date==null?null:PurchaseExchangeRateResource.parseDate(date));
        return Response.noContent().build();
    }
    private static String text(JsonNode body,String field) {
        JsonNode value=body.get(field);
        if (value==null) return null;
        if (!value.isTextual()) throw new IllegalArgumentException("Invalid acquisition field");
        return value.textValue();
    }

    private static void fields(JsonNode node,Set<String> names){if(node==null||!node.isObject())throw new IllegalArgumentException("Invalid body");node.fieldNames().forEachRemaining(f->{if(!names.contains(f))throw new IllegalArgumentException("Unknown field");});}
    private static JsonNode parse(String json){try{return EXACT_JSON.readTree(json);}catch(Exception e){throw new IllegalArgumentException("Invalid body");}}
    private static BigDecimal amount(JsonNode value){if(value==null||!value.isNumber())throw new IllegalArgumentException("Invalid amount");return PriceSchedule.money(value.decimalValue());}
    private static long version(JsonNode body){var v=body.get("version");if(v==null||!v.isIntegralNumber()||!v.canConvertToLong()||v.longValue()<0)throw new IllegalArgumentException("Invalid version");return v.longValue();}
    private static UUID id(String value){try{return UUID.fromString(value);}catch(RuntimeException e){throw new IllegalArgumentException("Invalid identifier");}}
}
