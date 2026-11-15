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
import java.util.UUID;
import jakarta.inject.Inject;
import javax.sql.DataSource;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;

@QuarkusTest
@QuarkusTestResource(value = KeycloakRealmResource.class, restrictToAnnotatedClass = true)
class OidcPlatformAuthorizationTest {
    @ConfigProperty(name = "quarkus.oidc.auth-server-url") String issuer;
    @Inject DataSource dataSource;

    @Test
    void verifiedActorProvisionsOnlyItsOwnAccount() throws Exception {
        String path = "/test-only-platform-authorization/account";
        given().when().get(path).then().statusCode(401);
        String accessToken = token(KeycloakRealmResource.MEMBER, "commercecore-api");
        String first = given().auth().oauth2(accessToken).when().get(path).then().statusCode(200).extract().asString();
        String second = given().auth().oauth2(accessToken).when().get(path).then().statusCode(200).extract().asString();
        assertEquals(first, second);
        try (var connection = dataSource.getConnection();
             var query = connection.prepareStatement("SELECT count(*) FROM tenant_membership WHERE user_id = ?")) {
            query.setObject(1, UUID.fromString(first));
            try (var row = query.executeQuery()) { assertTrue(row.next()); assertEquals(0, row.getInt(1)); }
        }
    }

    @Test
    void verifiedOidcIdentityAcceptsItsOwnInvitation() throws Exception {
        String token = token(KeycloakRealmResource.MEMBER, "commercecore-api");
        UUID user = UUID.fromString(given().auth().oauth2(token).get("/test-only-platform-authorization/account")
                .then().statusCode(200).extract().asString());
        UUID tenant = UUID.randomUUID(); UUID membership = UUID.randomUUID();
        try (var c = dataSource.getConnection()) {
            try (var s = c.prepareStatement("INSERT INTO tenant(tenant_id,slug,legal_name,display_name) VALUES (?,?,?,?)")) {
                s.setObject(1, tenant); s.setString(2, "oidc-" + tenant); s.setString(3, "Test"); s.setString(4, "Test"); s.executeUpdate();
            }
            try (var s = c.prepareStatement("INSERT INTO tenant_membership(membership_id,tenant_id,user_id,status) VALUES (?,?,?,'INVITED')")) {
                s.setObject(1, membership); s.setObject(2, tenant); s.setObject(3, user); s.executeUpdate();
            }
        }
        String path = "/test-only-platform-authorization/membership/" + tenant + "/" + membership + "/accept";
        given().post(path).then().statusCode(401);
        given().auth().oauth2(token).post(path).then().statusCode(200).body(is("ACTIVE"));
        try (var c = dataSource.getConnection()) {
            try (var s = c.prepareStatement("DELETE FROM audit_event WHERE tenant_id=?")) { s.setObject(1, tenant); s.executeUpdate(); }
            try (var s = c.prepareStatement("DELETE FROM tenant_membership WHERE tenant_id=?")) { s.setObject(1, tenant); s.executeUpdate(); }
            try (var s = c.prepareStatement("DELETE FROM tenant WHERE tenant_id=?")) { s.setObject(1, tenant); s.executeUpdate(); }
        }
    }

    @Test
    void cdiTransactionRollsBackWhenRealAuditInsertFails() throws Exception {
        String token = token(KeycloakRealmResource.MEMBER, "commercecore-api");
        UUID user = UUID.fromString(given().auth().oauth2(token).get("/test-only-platform-authorization/account")
                .then().statusCode(200).extract().asString());
        UUID tenant = UUID.randomUUID(), membership = UUID.randomUUID();
        String suffix = tenant.toString().replace("-", "");
        String function = "test_audit_failure_" + suffix;
        String trigger = "test_audit_trigger_" + suffix;
        try {
            try (var c = dataSource.getConnection()) {
                try (var s = c.prepareStatement("INSERT INTO tenant(tenant_id,slug,legal_name,display_name) VALUES (?,?,?,?)")) {
                    s.setObject(1, tenant); s.setString(2, "audit-" + tenant); s.setString(3, "Test"); s.setString(4, "Test"); s.executeUpdate();
                }
                try (var s = c.prepareStatement("INSERT INTO tenant_membership(membership_id,tenant_id,user_id,status) VALUES (?,?,?,'INVITED')")) {
                    s.setObject(1, membership); s.setObject(2, tenant); s.setObject(3, user); s.executeUpdate();
                }
                try (var s = c.createStatement()) {
                    s.execute("CREATE FUNCTION " + function + "() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'audit test failure'; END $$");
                    s.execute("CREATE TRIGGER " + trigger + " BEFORE INSERT ON audit_event FOR EACH ROW WHEN (NEW.tenant_id = '" + tenant + "'::uuid) EXECUTE FUNCTION " + function + "()");
                }
            }
            String path = "/test-only-platform-authorization/membership/" + tenant + "/" + membership + "/accept";
            given().auth().oauth2(token).post(path).then().statusCode(500);
            try (var c = dataSource.getConnection(); var s = c.prepareStatement("""
                    SELECT status, (SELECT count(*) FROM audit_event WHERE tenant_id=?)
                    FROM tenant_membership WHERE tenant_id=? AND membership_id=?
                    """)) {
                s.setObject(1, tenant); s.setObject(2, tenant); s.setObject(3, membership);
                try (var row = s.executeQuery()) {
                    assertTrue(row.next()); assertEquals("INVITED", row.getString(1)); assertEquals(0, row.getInt(2));
                }
            }
        } finally {
            try (var c = dataSource.getConnection()) {
                try (var s = c.createStatement()) {
                    s.execute("DROP TRIGGER IF EXISTS " + trigger + " ON audit_event");
                    s.execute("DROP FUNCTION IF EXISTS " + function + "()");
                }
                try (var s = c.prepareStatement("DELETE FROM tenant_membership WHERE tenant_id=?")) { s.setObject(1, tenant); s.executeUpdate(); }
                try (var s = c.prepareStatement("DELETE FROM tenant WHERE tenant_id=?")) { s.setObject(1, tenant); s.executeUpdate(); }
            }
        }
    }

    @Test
    void rolePermissionChangeRollsBackThroughCdiWhenAuditFails() throws Exception {
        String token = token(KeycloakRealmResource.MEMBER, "commercecore-api");
        UUID user = UUID.fromString(given().auth().oauth2(token).get("/test-only-platform-authorization/account")
                .then().statusCode(200).extract().asString());
        UUID tenant=UUID.randomUUID(), member=UUID.randomUUID(), manager=UUID.randomUUID(), commercial=UUID.randomUUID(), role=UUID.randomUUID();
        String suffix=tenant.toString().replace("-", ""), function="test_role_failure_"+suffix, trigger="test_role_trigger_"+suffix;
        try {
            try(var c=dataSource.getConnection(); var s=c.createStatement()) {
                c.setAutoCommit(false);
                s.execute("INSERT INTO tenant(tenant_id,slug,legal_name,display_name) VALUES ('"+tenant+"','role-"+tenant+"','Test','Test')");
                s.execute("INSERT INTO tenant_membership(membership_id,tenant_id,user_id,status) VALUES ('"+member+"','"+tenant+"','"+user+"','ACTIVE')");
                s.execute("INSERT INTO app_role(role_id,tenant_id,code,name) VALUES ('"+manager+"','"+tenant+"','TENANT_ADMIN','Manager'),('"+commercial+"','"+tenant+"','commercial','Commercial'),('"+role+"','"+tenant+"','reader','Reader')");
                s.execute("INSERT INTO role_permission(tenant_id,role_id,permission_code) VALUES ('"+tenant+"','"+manager+"','TENANT_READ'),('"+tenant+"','"+manager+"','TENANT_MANAGE'),('"+tenant+"','"+manager+"','USER_MANAGE'),('"+tenant+"','"+commercial+"','PRODUCT_READ'),('"+tenant+"','"+role+"','PRODUCT_READ')");
                s.execute("INSERT INTO membership_role(tenant_id,membership_id,role_id) VALUES ('"+tenant+"','"+member+"','"+manager+"'),('"+tenant+"','"+member+"','"+commercial+"')");
                s.execute("CREATE FUNCTION "+function+"() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'audit test failure'; END $$");
                s.execute("CREATE TRIGGER "+trigger+" BEFORE INSERT ON audit_event FOR EACH ROW WHEN (NEW.tenant_id = '"+tenant+"'::uuid) EXECUTE FUNCTION "+function+"()");
                c.commit();
            }
            given().auth().oauth2(token).post("/test-only-platform-authorization/role/"+tenant+"/"+role+"/clear").then().statusCode(500);
            try(var c=dataSource.getConnection(); var s=c.prepareStatement("SELECT count(*) FROM role_permission WHERE tenant_id=? AND role_id=? AND permission_code='PRODUCT_READ'")) {
                s.setObject(1,tenant); s.setObject(2,role);
                try(var rows=s.executeQuery()) { assertTrue(rows.next()); assertEquals(1,rows.getInt(1)); }
            }
        } finally {
            try(var c=dataSource.getConnection(); var s=c.createStatement()) {
                s.execute("DROP TRIGGER IF EXISTS "+trigger+" ON audit_event"); s.execute("DROP FUNCTION IF EXISTS "+function+"()");
                s.execute("DELETE FROM membership_role WHERE tenant_id='"+tenant+"'");
                s.execute("DELETE FROM role_permission WHERE tenant_id='"+tenant+"' AND role_id IN (SELECT role_id FROM app_role WHERE tenant_id='"+tenant+"' AND code<>'TENANT_ADMIN')");
                s.execute("DELETE FROM tenant_membership WHERE tenant_id='"+tenant+"'");
                s.execute("DELETE FROM app_role WHERE tenant_id='"+tenant+"' AND code<>'TENANT_ADMIN'");
            }
        }
    }

    @Test
    void platformRoleOperationsUseVerifiedClientRolesAndLocalAuditAccount() throws Exception {
        String platform = token(KeycloakRealmResource.ADMIN, "commercecore-api");
        String manager = token(KeycloakRealmResource.TENANT, "commercecore-api");
        String otherClient = token(KeycloakRealmResource.OTHER, "commercecore-api");
        var otherClaims = new ObjectMapper().readTree(Base64.getUrlDecoder().decode(otherClient.split("\\.")[1]));
        boolean otherRole = false;
        for (var role : otherClaims.path("resource_access").path("other-client").path("roles"))
            otherRole |= "PLATFORM_SUPER_ADMIN".equals(role.asText());
        assertTrue(otherRole, "Test token must contain the homonymous role from the other client");
        String unprovisioned = token(KeycloakRealmResource.UNPROVISIONED, "commercecore-api");
        String firstToken = token(KeycloakRealmResource.MEMBER, "commercecore-api");
        UUID platformUser = provision(platform), managerUser = provision(manager), targetUser = provision(otherClient), firstUser = provision(firstToken);
        UUID tenant=UUID.randomUUID(), first=UUID.randomUUID(), target=UUID.randomUUID(), managerMember=UUID.randomUUID();
        UUID protectedRole=UUID.randomUUID();
        try {
            try (var c=dataSource.getConnection(); var s=c.createStatement()) {
                c.setAutoCommit(false);
                s.execute("INSERT INTO tenant(tenant_id,slug,legal_name,display_name) VALUES ('"+tenant+"','platform-"+tenant+"','Test','Test')");
                s.execute("INSERT INTO tenant_membership(membership_id,tenant_id,user_id,status) VALUES ('"+first+"','"+tenant+"','"+firstUser+"','ACTIVE'),('"+target+"','"+tenant+"','"+targetUser+"','ACTIVE'),('"+managerMember+"','"+tenant+"','"+managerUser+"','ACTIVE')");
                s.execute("INSERT INTO app_role(role_id,tenant_id,code,name) VALUES ('"+protectedRole+"','"+tenant+"','TENANT_ADMIN','Admin')");
                s.execute("INSERT INTO role_permission(tenant_id,role_id,permission_code) VALUES ('"+tenant+"','"+protectedRole+"','TENANT_READ'),('"+tenant+"','"+protectedRole+"','TENANT_MANAGE'),('"+tenant+"','"+protectedRole+"','USER_MANAGE')");
                s.execute("INSERT INTO membership_role(tenant_id,membership_id,role_id) VALUES ('"+tenant+"','"+first+"','"+protectedRole+"'),('"+tenant+"','"+managerMember+"','"+protectedRole+"')");
                c.commit();
            }
            String base="/test-only-platform-authorization/platform/"+tenant+"/";
            given().post(base+target+"/designate").then().statusCode(401);
            given().auth().oauth2("invalid-token").post(base+target+"/designate").then().statusCode(401);
            given().auth().oauth2(unprovisioned).post(base+target+"/designate").then().statusCode(403);
            given().auth().oauth2(manager).post(base+target+"/designate").then().statusCode(403);
            given().auth().oauth2(manager).post(base+first+"/remove").then().statusCode(403);
            given().auth().oauth2(otherClient).post(base+target+"/designate").then().statusCode(403);
            given().auth().oauth2(otherClient).post(base+first+"/remove").then().statusCode(403);
            try (var c=dataSource.getConnection(); var s=c.prepareStatement("UPDATE tenant_membership SET status='SUSPENDED' WHERE tenant_id=? AND membership_id=?")) {
                s.setObject(1,tenant); s.setObject(2,managerMember); s.executeUpdate();
            }
            given().auth().oauth2(platform).post(base+target+"/designate").then().statusCode(204);
            given().auth().oauth2(platform).post(base+first+"/remove").then().statusCode(204);
            given().auth().oauth2(platform).post(base+target+"/remove").then().statusCode(409);
            try(var c=dataSource.getConnection(); var s=c.prepareStatement("""
                    SELECT (SELECT count(*) FROM membership_role WHERE tenant_id=? AND role_id=?),
                           (SELECT count(*) FROM audit_event WHERE tenant_id=? AND actor_user_id=? AND action IN ('ROLE_ASSIGNED','ROLE_REVOKED'))
                    """)) {
                s.setObject(1,tenant); s.setObject(2,protectedRole); s.setObject(3,tenant); s.setObject(4,platformUser);
                try(var rows=s.executeQuery()) { assertTrue(rows.next()); assertEquals(2,rows.getInt(1)); assertEquals(2,rows.getInt(2)); }
            }
        } finally {
            try(var c=dataSource.getConnection(); var s=c.createStatement()) {
                s.execute("DELETE FROM audit_event WHERE tenant_id='"+tenant+"'");
                s.execute("DELETE FROM membership_role WHERE tenant_id='"+tenant+"'");
                s.execute("DELETE FROM role_permission WHERE tenant_id='"+tenant+"' AND role_id IN (SELECT role_id FROM app_role WHERE tenant_id='"+tenant+"' AND code<>'TENANT_ADMIN')");
                s.execute("DELETE FROM tenant_membership WHERE tenant_id='"+tenant+"'");
                s.execute("DELETE FROM app_role WHERE tenant_id='"+tenant+"' AND code<>'TENANT_ADMIN'");
            }
        }
    }

    private UUID provision(String token) {
        return UUID.fromString(given().auth().oauth2(token).get("/test-only-platform-authorization/account")
                .then().statusCode(200).extract().asString());
    }

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
