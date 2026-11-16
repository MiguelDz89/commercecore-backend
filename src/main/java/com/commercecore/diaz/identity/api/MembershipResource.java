package com.commercecore.diaz.identity.api;

import com.commercecore.diaz.identity.api.dto.InviteMembershipRequest;
import com.commercecore.diaz.identity.api.dto.MembershipPageResponse;
import com.commercecore.diaz.identity.api.dto.MembershipResponse;
import com.commercecore.diaz.identity.application.MembershipAdministration;
import com.commercecore.diaz.platform.http.error.ProblemDetails;
import io.quarkus.security.Authenticated;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.UUID;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.enums.SchemaType;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;

@Path("/api/v1/tenants/{tenantId}/memberships")
@Authenticated
@Produces(MediaType.APPLICATION_JSON)
@SecurityRequirement(name="bearerAuth")
@APIResponse(responseCode="400",description="Solicitud inválida",content=@Content(mediaType="application/problem+json",schema=@Schema(implementation=ProblemDetails.class)))
@APIResponse(responseCode="401",description="Autenticación requerida",content=@Content(mediaType="application/problem+json",schema=@Schema(implementation=ProblemDetails.class)))
@APIResponse(responseCode="403",description="Operación prohibida",content=@Content(mediaType="application/problem+json",schema=@Schema(implementation=ProblemDetails.class)))
@APIResponse(responseCode="404",description="Recurso no disponible",content=@Content(mediaType="application/problem+json",schema=@Schema(implementation=ProblemDetails.class)))
@APIResponse(responseCode="409",description="Conflicto de membresía",content=@Content(mediaType="application/problem+json",schema=@Schema(implementation=ProblemDetails.class)))
@APIResponse(responseCode="500",description="Error interno",content=@Content(mediaType="application/problem+json",schema=@Schema(implementation=ProblemDetails.class)))
@APIResponse(responseCode="503",description="Dependencia no disponible",content=@Content(mediaType="application/problem+json",schema=@Schema(implementation=ProblemDetails.class)))
public class MembershipResource {
    private final MembershipAdministration memberships;
    public MembershipResource(MembershipAdministration memberships) { this.memberships=memberships; }

    @GET @Operation(summary="Listar miembros de una empresa con USER_MANAGE")
    @APIResponse(responseCode="200",description="Página de membresías",content=@Content(schema=@Schema(implementation=MembershipPageResponse.class)))
    public MembershipPageResponse list(@PathParam("tenantId") String tenantId,
            @Parameter(description="Desplazamiento no negativo",schema=@Schema(type=SchemaType.INTEGER,minimum="0",defaultValue="0"))
            @QueryParam("offset") @DefaultValue("0") String offsetValue,
            @Parameter(description="Tamaño de página entre 1 y 100",schema=@Schema(type=SchemaType.INTEGER,minimum="1",maximum="100",defaultValue="20"))
            @QueryParam("limit") @DefaultValue("20") String limitValue) {
        int offset=number(offsetValue,0,Integer.MAX_VALUE), limit=number(limitValue,1,100);
        return new MembershipPageResponse(memberships.list(uuid(tenantId),offset,limit).stream().map(MembershipResponse::from).toList(),offset,limit);
    }

    @POST @Consumes(MediaType.APPLICATION_JSON) @Operation(summary="Invitar una cuenta existente con USER_MANAGE")
    @APIResponse(responseCode="201",description="Invitación creada",content=@Content(schema=@Schema(implementation=MembershipResponse.class)))
    public Response invite(@PathParam("tenantId") String tenantId, @NotNull @Valid InviteMembershipRequest request) {
        return Response.status(201).entity(MembershipResponse.from(memberships.invite(uuid(tenantId),request.accountId()))).build();
    }

    @POST @Path("/{membershipId}/acceptance") @Operation(summary="Aceptar la propia invitación")
    @APIResponse(responseCode="200",description="Membresía activada",content=@Content(schema=@Schema(implementation=MembershipResponse.class)))
    public MembershipResponse accept(@PathParam("tenantId") String tenantId,@PathParam("membershipId") String id) {
        return MembershipResponse.from(memberships.accept(uuid(tenantId),uuid(id)));
    }

    @POST @Path("/{membershipId}/suspension") @Operation(summary="Suspender una membresía ordinaria con USER_MANAGE")
    @APIResponse(responseCode="200",description="Membresía suspendida",content=@Content(schema=@Schema(implementation=MembershipResponse.class)))
    public MembershipResponse suspend(@PathParam("tenantId") String tenantId,@PathParam("membershipId") String id) {
        return MembershipResponse.from(memberships.suspend(uuid(tenantId),uuid(id)));
    }

    @POST @Path("/{membershipId}/reactivation") @Operation(summary="Reactivar una membresía ordinaria con USER_MANAGE")
    @APIResponse(responseCode="200",description="Membresía reactivada",content=@Content(schema=@Schema(implementation=MembershipResponse.class)))
    public MembershipResponse reactivate(@PathParam("tenantId") String tenantId,@PathParam("membershipId") String id) {
        return MembershipResponse.from(memberships.reactivate(uuid(tenantId),uuid(id)));
    }
    private static UUID uuid(String value) {
        try { return UUID.fromString(value); }
        catch (RuntimeException e) { throw new IllegalArgumentException("Invalid identifier"); }
    }
    private static int number(String value, int minimum, int maximum) {
        try {
            int result=Integer.parseInt(value);
            if (result < minimum || result > maximum) throw new IllegalArgumentException("Invalid pagination");
            return result;
        } catch (NumberFormatException e) { throw new IllegalArgumentException("Invalid pagination"); }
    }
}
