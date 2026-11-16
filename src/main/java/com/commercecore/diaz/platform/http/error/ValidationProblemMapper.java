package com.commercecore.diaz.platform.http.error;

import jakarta.validation.ConstraintViolationException;
import jakarta.validation.ElementKind;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import java.util.UUID;

@Provider
public class ValidationProblemMapper implements ExceptionMapper<ConstraintViolationException> {
    @Context UriInfo uri;
    @Override public Response toResponse(ConstraintViolationException error) {
        boolean returned=error.getConstraintViolations().stream().anyMatch(v -> {
            for (var node : v.getPropertyPath()) if (node.getKind()==ElementKind.RETURN_VALUE) return true;
            return false;
        });
        int status=returned ? 500 : 400;
        var problem=new ProblemDetails("about:blank",returned ? "Error interno" : "Solicitud inválida",status,
                returned ? "No se pudo completar la operación." : "Compruebe los datos enviados.",
                uri==null ? null : (uri.getPath().startsWith("/") ? uri.getPath() : "/"+uri.getPath()),UUID.randomUUID().toString());
        return Response.status(status).type("application/problem+json").entity(problem).build();
    }
}
