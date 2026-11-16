package com.commercecore.diaz.identity.api;

import com.commercecore.diaz.identity.api.dto.RolePageResponse;
import com.commercecore.diaz.identity.api.dto.RoleResponse;
import com.commercecore.diaz.identity.application.TenantRoleAdministration;
import com.commercecore.diaz.platform.http.error.ProblemDetails;
import io.quarkus.security.Authenticated;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.enums.SchemaType;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;

@Path("/api/v1/tenants/{tenantId}/memberships/{membershipId}/roles") @Authenticated @Produces(MediaType.APPLICATION_JSON)
@SecurityRequirement(name="bearerAuth")
@APIResponse(responseCode="400",description="Solicitud inválida",content=@Content(mediaType="application/problem+json",schema=@Schema(implementation=ProblemDetails.class)))
@APIResponse(responseCode="401",description="Autenticación requerida",content=@Content(mediaType="application/problem+json",schema=@Schema(implementation=ProblemDetails.class)))
@APIResponse(responseCode="403",description="Operación prohibida",content=@Content(mediaType="application/problem+json",schema=@Schema(implementation=ProblemDetails.class)))
@APIResponse(responseCode="404",description="Recurso no disponible",content=@Content(mediaType="application/problem+json",schema=@Schema(implementation=ProblemDetails.class)))
@APIResponse(responseCode="409",description="Conflicto de asignación",content=@Content(mediaType="application/problem+json",schema=@Schema(implementation=ProblemDetails.class)))
@APIResponse(responseCode="500",description="Error interno",content=@Content(mediaType="application/problem+json",schema=@Schema(implementation=ProblemDetails.class)))
@APIResponse(responseCode="503",description="Dependencia no disponible",content=@Content(mediaType="application/problem+json",schema=@Schema(implementation=ProblemDetails.class)))
public class MembershipRolesResource {
    private final TenantRoleAdministration roles;
    public MembershipRolesResource(TenantRoleAdministration roles) { this.roles=roles; }
    @GET @Operation(summary="Listar roles de membresía con USER_MANAGE; limit de 1 a 100")
    @APIResponse(responseCode="200",description="Página de roles",content=@Content(schema=@Schema(implementation=RolePageResponse.class)))
    public RolePageResponse list(@PathParam("tenantId") String tenant,@PathParam("membershipId") String member,
            @Parameter(description="Desplazamiento no negativo",schema=@Schema(type=SchemaType.INTEGER,minimum="0",defaultValue="0")) @QueryParam("offset") @DefaultValue("0") String offset,@Parameter(description="Tamaño de página de 1 a 100",schema=@Schema(type=SchemaType.INTEGER,minimum="1",maximum="100",defaultValue="20")) @QueryParam("limit") @DefaultValue("20") String limit) {
        int o=RoleHttpParameters.number(offset,0,Integer.MAX_VALUE), l=RoleHttpParameters.number(limit,1,100);
        return new RolePageResponse(roles.membershipRoles(RoleHttpParameters.id(tenant),RoleHttpParameters.id(member),o,l)
                .stream().map(RoleResponse::from).toList(),o,l);
    }
    @PUT @Path("/{roleId}") @Operation(summary="Asignar rol ordinario con USER_MANAGE")
    @APIResponse(responseCode="204",description="Rol asignado")
    public void assign(@PathParam("tenantId") String tenant,@PathParam("membershipId") String member,@PathParam("roleId") String role) {
        roles.assign(RoleHttpParameters.id(tenant),RoleHttpParameters.id(member),RoleHttpParameters.id(role));
    }
    @DELETE @Path("/{roleId}") @Operation(summary="Revocar rol ordinario con USER_MANAGE")
    @APIResponse(responseCode="204",description="Rol revocado")
    public void revoke(@PathParam("tenantId") String tenant,@PathParam("membershipId") String member,@PathParam("roleId") String role) {
        roles.revoke(RoleHttpParameters.id(tenant),RoleHttpParameters.id(member),RoleHttpParameters.id(role));
    }
}
