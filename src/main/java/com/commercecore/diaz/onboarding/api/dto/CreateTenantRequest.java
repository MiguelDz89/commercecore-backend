package com.commercecore.diaz.onboarding.api.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Set;
import java.util.UUID;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

@Schema(description="Solicitud administrativa de onboarding",example="{\"slug\":\"ejemplo\",\"legalName\":\"Empresa Ejemplo\",\"displayName\":\"Ejemplo\",\"currencyCode\":\"PEN\",\"timeZone\":\"America/Lima\",\"initialAdministratorAccountId\":\"00000000-0000-4000-8000-000000000001\",\"commercialProfiles\":[]}")
public record CreateTenantRequest(
        @NotBlank @Size(max=80) @Pattern(regexp="^[a-z0-9]+(?:-[a-z0-9]+)*$") String slug,
        @NotBlank @Size(max=200) String legalName,
        @NotBlank @Size(max=160) String displayName,
        @Pattern(regexp="^[A-Z]{3}$") @Schema(defaultValue="PEN") String currencyCode,
        @NotBlank @Size(max=64) String timeZone,
        @NotNull UUID initialAdministratorAccountId,
        @NotNull @Size(max=32) Set<@NotBlank String> commercialProfiles,
        @Schema(description="Habilita cantidades fraccionadas en la empresa desde el alta", defaultValue="false")
        boolean fractionalQuantitiesEnabled) {
    @JsonAnySetter
    public void rejectUnknown(String name, Object value) { throw new IllegalArgumentException("Unknown request property"); }
}
