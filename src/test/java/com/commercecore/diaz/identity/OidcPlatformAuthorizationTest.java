package com.commercecore.diaz.identity;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Test;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import com.fasterxml.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.Base64;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;

@QuarkusTest
@QuarkusTestResource(value = KeycloakRealmResource.class, restrictToAnnotatedClass = true)
class OidcPlatformAuthorizationTest {
    @ConfigProperty(name = "quarkus.oidc.auth-server-url") String issuer;

    @Test
    void onlyRoleOfConfiguredClientGrantsGlobalAuthority() {
        check(KeycloakRealmResource.ADMIN, 200);
        check(KeycloakRealmResource.MEMBER, 403);
        check(KeycloakRealmResource.TENANT, 403);
        check(KeycloakRealmResource.OTHER, 403);
        given().when().get("/test-only-platform-authorization").then().statusCode(401);
        given().auth().oauth2("invalid-token").when().get("/test-only-platform-authorization").then().statusCode(401);
        String wrongAudience = token(KeycloakRealmResource.MEMBER, "other-client");
        given().auth().oauth2(wrongAudience).when().get("/test-only-platform-authorization").then().statusCode(401);
        String wrongIssuer = token(KeycloakRealmResource.ADMIN, "commercecore-api", issuer.replace("/realms/commercecore-test", "/realms/foreign-test"));
        given().auth().oauth2(wrongIssuer).when().get("/test-only-platform-authorization").then().statusCode(401);
    }

    private void check(String username, int status) {
        String accessToken = token(username, "commercecore-api");
        if (username.equals(KeycloakRealmResource.ADMIN)) {
            try {
                var claims = new ObjectMapper().readTree(Base64.getUrlDecoder().decode(accessToken.split("\\.")[1]));
                boolean hasRole = false;
                for (var role : claims.path("resource_access").path("commercecore-api").path("roles")) {
                    hasRole |= "PLATFORM_SUPER_ADMIN".equals(role.asText());
                }
                assertTrue(hasRole, "Keycloak did not issue the expected API client role");
            } catch (java.io.IOException e) {
                throw new IllegalStateException("Cannot inspect token claims", e);
            }
        }
        var response = given().auth().oauth2(accessToken)
                .when().get("/test-only-platform-authorization").then().statusCode(status);
        if (status == 200) response.body(is("allowed"));
    }

    private String token(String username, String client) {
        return token(username, client, issuer);
    }

    private String token(String username, String client, String tokenIssuer) {
        try {
            String form = "grant_type=password&client_id=" + encode(client) + "&username="
                    + encode(username) + "&password=" + encode(KeycloakRealmResource.PASSWORD);
            HttpRequest request = HttpRequest.newBuilder(URI.create(tokenIssuer + "/protocol/openid-connect/token"))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(form)).build();
            HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
            String error = new ObjectMapper().readTree(response.body()).path("error").asText();
            assertEquals(200, response.statusCode(), "Keycloak token request failed: " + error);
            return new ObjectMapper().readTree(response.body()).path("access_token").asText();
        } catch (Exception e) {
            throw new IllegalStateException("Keycloak token request failed", e);
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
