package com.commercecore.diaz.identity.api;

import com.commercecore.diaz.identity.application.AccountProvisioning;
import com.commercecore.diaz.identity.api.dto.AccountProvisionedResponse;
import com.commercecore.diaz.platform.http.error.ProblemDetails;
import io.quarkus.security.Authenticated;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;

@Path("/api/v1/accounts/me/provisioning")
@Authenticated
@Produces(MediaType.APPLICATION_JSON)
@SecurityRequirement(name="bearerAuth")
public class AccountProvisioningResource {
    private final AccountProvisioning accounts;
    public AccountProvisioningResource(AccountProvisioning accounts) { this.accounts=accounts; }
    @POST @Operation(summary="Provisionar idempotentemente la cuenta de la identidad autenticada")
    @APIResponse(responseCode="200",description="Cuenta local disponible",content=@Content(mediaType="application/json",schema=@Schema(implementation=AccountProvisionedResponse.class)))
    @APIResponse(responseCode="401",description="Bearer ausente o inválido",content=@Content(mediaType="application/problem+json",schema=@Schema(implementation=ProblemDetails.class)))
    @APIResponse(responseCode="400",description="Identidad no válida",content=@Content(mediaType="application/problem+json",schema=@Schema(implementation=ProblemDetails.class)))
    @APIResponse(responseCode="403",description="Operación denegada",content=@Content(mediaType="application/problem+json",schema=@Schema(implementation=ProblemDetails.class)))
    @APIResponse(responseCode="500",description="Error interno",content=@Content(mediaType="application/problem+json",schema=@Schema(implementation=ProblemDetails.class)))
    @APIResponse(responseCode="503",description="Dependencia temporalmente no disponible",content=@Content(mediaType="application/problem+json",schema=@Schema(implementation=ProblemDetails.class)))
    public AccountProvisionedResponse provision() { return new AccountProvisionedResponse(accounts.provisionCurrentActor()); }
}
