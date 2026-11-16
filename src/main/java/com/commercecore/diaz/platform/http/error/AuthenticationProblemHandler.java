package com.commercecore.diaz.platform.http.error;

import io.quarkus.security.AuthenticationFailedException;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import java.util.UUID;

/** Preserve the challenge set by Quarkus when authentication fails before JAX-RS. */
@ApplicationScoped
public class AuthenticationProblemHandler {
    void install(@Observes Router router) {
        router.route().failureHandler(ctx -> {
            String path=ctx.request().path();
            boolean api=path.equals("/api/v1/accounts/me/provisioning") || path.equals("/api/v1/platform/tenants")
                    || path.matches("/api/v1/tenants/[^/]+/(?:memberships|roles)(?:/.*)?")
                    || path.matches("/api/v1/platform/tenants/[^/]+/administrators/[^/]+");
            if (api && !ctx.response().ended() && (ctx.statusCode()==401 || ctx.failure() instanceof AuthenticationFailedException)) {
                ctx.response().setStatusCode(401).putHeader("Content-Type","application/problem+json");
                if (!ctx.response().headers().contains("WWW-Authenticate")) ctx.response().putHeader("WWW-Authenticate","Bearer");
                ctx.response().end(new JsonObject().put("type","about:blank").put("title","Autenticación requerida")
                        .put("status",401).put("detail","Se requiere un token válido.").put("instance",path)
                        .put("correlationId",UUID.randomUUID().toString()).encode());
            } else ctx.next();
        });
    }
}
