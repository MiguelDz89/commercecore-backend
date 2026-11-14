package com.commercecore.diaz.identity;

import com.commercecore.diaz.identity.application.PlatformAuthorization;
import io.quarkus.security.Authenticated;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.WebApplicationException;

@Path("/test-only-platform-authorization")
@Authenticated
public class SecurityProbe {
    @Inject PlatformAuthorization authorization;

    @GET
    public String check() {
        if (!authorization.canCreateTenant()) {
            throw new WebApplicationException(403);
        }
        return "allowed";
    }
}
