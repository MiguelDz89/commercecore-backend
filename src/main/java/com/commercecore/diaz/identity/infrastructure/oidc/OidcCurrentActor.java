package com.commercecore.diaz.identity.infrastructure.oidc;

import com.commercecore.diaz.identity.application.CurrentActor;
import com.commercecore.diaz.identity.domain.ActorId;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.RequestScoped;
import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.json.JsonString;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.jwt.JsonWebToken;

@RequestScoped
public class OidcCurrentActor implements CurrentActor {
    private final SecurityIdentity identity;
    private final JsonWebToken token;
    private final String clientId;

    public OidcCurrentActor(SecurityIdentity identity, JsonWebToken token,
            @ConfigProperty(name = "quarkus.oidc.client-id") String clientId) {
        this.identity = identity;
        this.token = token;
        this.clientId = clientId;
    }

    @Override
    public Optional<ActorId> id() {
        if (identity.isAnonymous()) {
            return Optional.empty();
        }
        String issuer = token.getIssuer();
        String subject = token.getSubject();
        Number expiration = token.getClaim("exp");
        if (issuer == null || issuer.isBlank() || subject == null || subject.isBlank()
                || expiration == null || expiration.longValue() <= Instant.now().getEpochSecond()) {
            return Optional.empty();
        }
        return Optional.of(new ActorId(issuer, subject));
    }

    @Override
    public boolean hasPlatformSuperAdminRole() {
        if (id().isEmpty()) {
            return false;
        }
        Object access = token.getClaim("resource_access");
        if (access instanceof JsonObject clients) {
            JsonObject client = clients.getJsonObject(clientId);
            JsonArray roles = client == null ? null : client.getJsonArray("roles");
            return roles != null && roles.stream().anyMatch(role -> role instanceof JsonString value
                    && "PLATFORM_SUPER_ADMIN".equals(value.getString()));
        }
        if (!(access instanceof Map<?, ?> clients)
                || !(clients.get(clientId) instanceof Map<?, ?> client)
                || !(client.get("roles") instanceof Collection<?> roles)) {
            return false;
        }
        return roles.contains("PLATFORM_SUPER_ADMIN");
    }
}
