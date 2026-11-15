package com.commercecore.diaz.identity;

import com.commercecore.diaz.identity.application.PlatformAuthorization;
import com.commercecore.diaz.identity.application.AccountProvisioning;
import io.quarkus.security.Authenticated;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PathParam;
import java.util.UUID;
import com.commercecore.diaz.identity.application.MembershipAdministration;
import com.commercecore.diaz.identity.application.TenantRoleAdministration;

@Path("/test-only-platform-authorization")
@Authenticated
public class SecurityProbe {
    @Inject PlatformAuthorization authorization;
    @Inject AccountProvisioning accounts;
    @Inject MembershipAdministration memberships;
    @Inject TenantRoleAdministration roles;

    @GET
    public String check() {
        if (!authorization.canCreateTenant()) {
            throw new WebApplicationException(403);
        }
        return "allowed";
    }

    @GET
    @Path("/account")
    public String provisionAccount() {
        return accounts.provisionCurrentActor().toString();
    }

    @POST
    @Path("/membership/{tenant}/{membership}/accept")
    public String accept(@PathParam("tenant") UUID tenant, @PathParam("membership") UUID membership) {
        return memberships.accept(tenant, membership).status().name();
    }

    @POST
    @Path("/role/{tenant}/{role}/clear")
    public void clearRole(@PathParam("tenant") UUID tenant, @PathParam("role") UUID role) {
        roles.changePermissions(tenant, role, java.util.Set.of());
    }

    @POST
    @Path("/platform/{tenant}/{membership}/{operation}")
    public void platformRole(@PathParam("tenant") UUID tenant, @PathParam("membership") UUID membership,
                             @PathParam("operation") String operation) {
        try {
            switch (operation) {
                case "designate" -> roles.designateAdmin(tenant, membership);
                case "remove" -> roles.removeAdmin(tenant, membership);
                default -> throw new WebApplicationException(404);
            }
        } catch (SecurityException e) {
            throw new WebApplicationException(403);
        } catch (com.commercecore.diaz.identity.application.MembershipConflictException e) {
            throw new WebApplicationException(409);
        }
    }
}
