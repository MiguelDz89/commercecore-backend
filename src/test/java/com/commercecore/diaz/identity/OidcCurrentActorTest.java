package com.commercecore.diaz.identity;

import com.commercecore.diaz.identity.infrastructure.oidc.OidcCurrentActor;
import io.quarkus.security.identity.SecurityIdentity;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OidcCurrentActorTest {
    @Test
    void onlyConfiguredClientAndValidActorCanGrantRole() {
        assertTrue(actor("subject", Instant.now().plusSeconds(60).getEpochSecond(),
                Map.of("commercecore-api", Map.of("roles", List.of("PLATFORM_SUPER_ADMIN")))).hasPlatformSuperAdminRole());
        assertFalse(actor("subject", Instant.now().plusSeconds(60).getEpochSecond(),
                Map.of("other-client", Map.of("roles", List.of("PLATFORM_SUPER_ADMIN")))).hasPlatformSuperAdminRole());
        assertFalse(actor(" ", Instant.now().plusSeconds(60).getEpochSecond(),
                Map.of("commercecore-api", Map.of("roles", List.of("PLATFORM_SUPER_ADMIN")))).hasPlatformSuperAdminRole());
        assertFalse(actor(null, Instant.now().plusSeconds(60).getEpochSecond(),
                Map.of("commercecore-api", Map.of("roles", List.of("PLATFORM_SUPER_ADMIN")))).hasPlatformSuperAdminRole());
        assertFalse(actor("subject", Instant.now().minusSeconds(1).getEpochSecond(),
                Map.of("commercecore-api", Map.of("roles", List.of("PLATFORM_SUPER_ADMIN")))).hasPlatformSuperAdminRole());
    }

    private static OidcCurrentActor actor(String subject, long exp, Map<String, Object> access) {
        SecurityIdentity identity = (SecurityIdentity) Proxy.newProxyInstance(SecurityIdentity.class.getClassLoader(),
                new Class<?>[]{SecurityIdentity.class}, (proxy, method, args) ->
                        method.getName().equals("isAnonymous") ? false : null);
        JsonWebToken token = (JsonWebToken) Proxy.newProxyInstance(JsonWebToken.class.getClassLoader(),
                new Class<?>[]{JsonWebToken.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getIssuer" -> "https://issuer.example";
                    case "getSubject" -> subject;
                    case "getClaim" -> "exp".equals(args[0]) ? exp : "resource_access".equals(args[0]) ? access : null;
                    default -> null;
                });
        return new OidcCurrentActor(identity, token, "commercecore-api");
    }
}
