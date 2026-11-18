package com.commercecore.diaz.catalog.api;

import com.commercecore.diaz.catalog.application.ExchangeRateAdministration;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import io.quarkus.security.Authenticated;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.UUID;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;

@Path("/api/v1/tenants/{tenantId}/exchange-rates/{sourceCurrency}/{date}")
@Authenticated
@Produces(MediaType.APPLICATION_JSON)
@SecurityRequirement(name="bearerAuth")
public class PurchaseExchangeRateResource {
    public record Update(@NotNull BigDecimal rate) {
        @JsonAnySetter
        public void unknown(String property,Object value) {
            throw new IllegalArgumentException("Unknown exchange rate property");
        }
    }

    private final ExchangeRateAdministration rates;

    public PurchaseExchangeRateResource(ExchangeRateAdministration rates) {
        this.rates=rates;
    }

    @GET
    public ExchangeRateAdministration.Rate get(@PathParam("tenantId") String tenant,
            @PathParam("sourceCurrency") String source, @PathParam("date") String date) {
        return rates.get(UUID.fromString(tenant),source,parseDate(date)).orElseThrow(NotFoundException::new);
    }

    @PUT
    @Consumes(MediaType.APPLICATION_JSON)
    public Response put(@PathParam("tenantId") String tenant, @PathParam("sourceCurrency") String source,
                        @PathParam("date") String date, @Valid @NotNull Update body) {
        rates.put(UUID.fromString(tenant),source,parseDate(date),body.rate());
        return Response.noContent().build();
    }

    static LocalDate parseDate(String value) {
        if(value==null || !value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}"))
            throw new IllegalArgumentException("Invalid date");
        try {
            LocalDate date=LocalDate.parse(value);
            if(date.getYear()==0) throw new IllegalArgumentException("Invalid date");
            return date;
        } catch(DateTimeParseException error) {
            throw new IllegalArgumentException("Invalid date",error);
        }
    }
}
