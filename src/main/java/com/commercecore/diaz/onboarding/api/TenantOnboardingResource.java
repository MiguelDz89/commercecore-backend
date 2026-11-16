package com.commercecore.diaz.onboarding.api;

import com.commercecore.diaz.onboarding.api.dto.CreateTenantRequest;
import com.commercecore.diaz.onboarding.api.dto.TenantCreatedResponse;
import com.commercecore.diaz.onboarding.application.CreateTenantCommand;
import com.commercecore.diaz.onboarding.application.TenantOnboarding;
import io.quarkus.security.Authenticated;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import com.commercecore.diaz.platform.http.error.ProblemDetails;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.security.SecurityScheme;
import org.eclipse.microprofile.openapi.annotations.enums.SecuritySchemeType;

@SecurityScheme(securitySchemeName="bearerAuth",type=SecuritySchemeType.HTTP,scheme="bearer",bearerFormat="JWT")
@Path("/api/v1/platform/tenants")
@Authenticated
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
@SecurityRequirement(name="bearerAuth")
public class TenantOnboardingResource {
    private final TenantOnboarding onboarding;
    public TenantOnboardingResource(TenantOnboarding onboarding) { this.onboarding=onboarding; }
    @POST @Operation(summary="Crear empresa y administrador inicial de forma transaccional")
    @APIResponse(responseCode="201",description="Empresa creada",content=@Content(mediaType="application/json",schema=@Schema(implementation=TenantCreatedResponse.class)))
    @APIResponse(responseCode="400",description="Solicitud inválida",content=@Content(mediaType="application/problem+json",schema=@Schema(implementation=ProblemDetails.class)))
    @APIResponse(responseCode="401",description="Bearer ausente o inválido",content=@Content(mediaType="application/problem+json",schema=@Schema(implementation=ProblemDetails.class)))
    @APIResponse(responseCode="403",description="Operación denegada",content=@Content(mediaType="application/problem+json",schema=@Schema(implementation=ProblemDetails.class)))
    @APIResponse(responseCode="409",description="Slug ya registrado",content=@Content(mediaType="application/problem+json",schema=@Schema(implementation=ProblemDetails.class)))
    @APIResponse(responseCode="500",description="Error interno",content=@Content(mediaType="application/problem+json",schema=@Schema(implementation=ProblemDetails.class)))
    @APIResponse(responseCode="503",description="Dependencia temporalmente no disponible",content=@Content(mediaType="application/problem+json",schema=@Schema(implementation=ProblemDetails.class)))
    public Response create(@NotNull @Valid CreateTenantRequest request) {
        var result=onboarding.create(new CreateTenantCommand(request.slug(),request.legalName(),request.displayName(),
                request.currencyCode(),request.timeZone(),request.initialAdministratorAccountId(),request.commercialProfiles()));
        return Response.status(Response.Status.CREATED)
                .entity(new TenantCreatedResponse(result.tenantId(),result.slug(),result.initialMembershipId())).build();
    }
}
