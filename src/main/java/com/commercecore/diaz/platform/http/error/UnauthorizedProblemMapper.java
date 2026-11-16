package com.commercecore.diaz.platform.http.error;

import io.quarkus.security.UnauthorizedException;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import java.util.UUID;

@Provider
public class UnauthorizedProblemMapper implements ExceptionMapper<UnauthorizedException> {
    @Context UriInfo uri;
    @Override public Response toResponse(UnauthorizedException error) {
        var problem=new ProblemDetails("about:blank","Autenticación requerida",401,"Se requiere un token válido.",
                uri==null ? null : (uri.getPath().startsWith("/") ? uri.getPath() : "/"+uri.getPath()),UUID.randomUUID().toString());
        return Response.status(401).header("WWW-Authenticate","Bearer").type("application/problem+json").entity(problem).build();
    }
}
