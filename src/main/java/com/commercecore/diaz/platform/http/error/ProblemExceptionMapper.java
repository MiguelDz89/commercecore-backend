package com.commercecore.diaz.platform.http.error;

import com.commercecore.diaz.tenant.application.DuplicateTenantSlugException;
import com.commercecore.diaz.identity.application.MembershipConflictException;
import com.commercecore.diaz.catalog.application.CategoryConflictException;
import com.commercecore.diaz.catalog.application.ProductConflictException;
import jakarta.validation.ConstraintViolationException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import java.sql.SQLTransientConnectionException;
import java.sql.SQLRecoverableException;
import java.sql.SQLException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.UUID;

@Provider
public class ProblemExceptionMapper implements ExceptionMapper<Exception> {
    @Context UriInfo uri;
    @Override public Response toResponse(Exception exception) {
        if (exception instanceof WebApplicationException web && web.getResponse().getStatus()!=400
                && web.getResponse().getStatus()!=403 && web.getResponse().getStatus()!=404)
            return web.getResponse(); // Preserve Bearer challenges and framework-specific responses.
        int status=classify(exception);
        String title=switch(status) { case 400 -> "Solicitud inválida"; case 403 -> "Operación denegada";
            case 404 -> "Recurso no encontrado"; case 409 -> "Conflicto"; case 503 -> "Servicio no disponible"; default -> "Error interno"; };
        String detail=switch(status) { case 400 -> "Compruebe los datos enviados.";
            case 403 -> "No se puede realizar esta operación."; case 404 -> "El recurso no está disponible."; case 409 -> "La operación entra en conflicto con el estado actual.";
            case 503 -> "Una dependencia no está disponible temporalmente."; default -> "No se pudo completar la operación."; };
        var problem=new ProblemDetails("about:blank",title,status,detail,
                uri==null ? null : (uri.getPath().startsWith("/") ? uri.getPath() : "/"+uri.getPath()),UUID.randomUUID().toString());
        return Response.status(status).type("application/problem+json").entity(problem).build();
    }
    private boolean isMembershipRequest() {
        return uri!=null && uri.getPath().matches("/?api/v1/tenants/[^/]+/(?:memberships|roles|categories|products)(?:/.*)?");
    }
    private int classify(Throwable error) {
        Set<Throwable> seen=Collections.newSetFromMap(new IdentityHashMap<>());
        for(Throwable e=error; e!=null && seen.add(e); e=e.getCause()) {
            if(e instanceof DuplicateTenantSlugException || e instanceof MembershipConflictException || e instanceof CategoryConflictException || e instanceof ProductConflictException) return 409;
            if(e instanceof SecurityException) return isMembershipRequest() ? 404 : 403;
            if(e instanceof ConstraintViolationException || e instanceof IllegalArgumentException) return 400;
            if(e instanceof SQLTransientConnectionException || e instanceof SQLRecoverableException) return 503;
            if(e instanceof SQLException sql && ("08006".equals(sql.getSQLState()) || "08001".equals(sql.getSQLState()))) return 503;
            if(e instanceof WebApplicationException web && (web.getResponse().getStatus()==400
                    || web.getResponse().getStatus()==403 || web.getResponse().getStatus()==404))
                return web.getResponse().getStatus();
        }
        return 500;
    }
}
