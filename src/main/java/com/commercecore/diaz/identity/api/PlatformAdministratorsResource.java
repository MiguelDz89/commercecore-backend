package com.commercecore.diaz.identity.api;

import com.commercecore.diaz.identity.application.TenantRoleAdministration;
import com.commercecore.diaz.platform.http.error.ProblemDetails;
import io.quarkus.security.Authenticated;
import jakarta.ws.rs.*;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;

@Path("/api/v1/platform/tenants/{tenantId}/administrators/{membershipId}") @Authenticated
@SecurityRequirement(name="bearerAuth")
@APIResponse(responseCode="400",description="Solicitud inválida",content=@Content(mediaType="application/problem+json",schema=@Schema(implementation=ProblemDetails.class)))
@APIResponse(responseCode="401",description="Autenticación requerida",content=@Content(mediaType="application/problem+json",schema=@Schema(implementation=ProblemDetails.class)))
@APIResponse(responseCode="403",description="Operación denegada",content=@Content(mediaType="application/problem+json",schema=@Schema(implementation=ProblemDetails.class)))
@APIResponse(responseCode="409",description="Conflicto de administración",content=@Content(mediaType="application/problem+json",schema=@Schema(implementation=ProblemDetails.class)))
@APIResponse(responseCode="500",description="Error interno",content=@Content(mediaType="application/problem+json",schema=@Schema(implementation=ProblemDetails.class)))
@APIResponse(responseCode="503",description="Dependencia no disponible",content=@Content(mediaType="application/problem+json",schema=@Schema(implementation=ProblemDetails.class)))
public class PlatformAdministratorsResource {
    private final TenantRoleAdministration roles;
    public PlatformAdministratorsResource(TenantRoleAdministration roles) { this.roles=roles; }
    @PUT @Operation(summary="Designar TENANT_ADMIN; requiere PLATFORM_SUPER_ADMIN")
    @APIResponse(responseCode="204",description="Administrador designado")
    public void designate(@PathParam("tenantId") String tenant,@PathParam("membershipId") String member) {
        roles.designateAdmin(RoleHttpParameters.id(tenant),RoleHttpParameters.id(member));
    }
    @DELETE @Operation(summary="Retirar TENANT_ADMIN; requiere PLATFORM_SUPER_ADMIN")
    @APIResponse(responseCode="204",description="Administrador retirado")
    public void remove(@PathParam("tenantId") String tenant,@PathParam("membershipId") String member) {
        roles.removeAdmin(RoleHttpParameters.id(tenant),RoleHttpParameters.id(member));
    }
}
