package com.commercecore.diaz.identity;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusIntegrationTest;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.*;

/** Runs against the packaged application and services owned by this test. */
@QuarkusIntegrationTest
@QuarkusTestResource(value=SprintOneIntegrationResource.class,restrictToAnnotatedClass=true)
class SprintOnePackagedIT {
    @Test
    void twoTenantsEndToEndAndDatabaseOutage() throws Exception {
        String platform=token(KeycloakRealmResource.ADMIN), adminA=token(KeycloakRealmResource.TENANT);
        String adminB=token(KeycloakRealmResource.ONBOARD_TARGET), member=token(KeycloakRealmResource.MEMBER);
        String otherClient=token(KeycloakRealmResource.OTHER);
        UUID aAccount=account(adminA), bAccount=account(adminB), memberAccount=account(member), otherAccount=account(otherClient);
        account(platform);
        UUID tenantA=create(platform,aAccount,"it-a-",List.of("CATALOG_OPERATOR"));
        UUID tenantB=create(platform,bAccount,"it-b-",List.of());
        assertEquals(1,countInitialAdministrator(tenantA,aAccount));
        assertEquals(1,countInitialAdministrator(tenantB,bAccount));
        String a="/api/v1/tenants/"+tenantA, b="/api/v1/tenants/"+tenantB;
        given().auth().oauth2(platform).get(a+"/roles").then().statusCode(404);
        given().auth().oauth2(adminA).get(b+"/roles").then().statusCode(404);
        given().auth().oauth2(member).get(a+"/roles").then().statusCode(404);
        var roleResponse=given().auth().oauth2(adminA).get(a+"/roles").then().statusCode(200).extract().response();
        String role=roleResponse.jsonPath().getString("items.find { it.code == 'CATALOG_OPERATOR' }.roleId");
        assertNotNull(role);
        assertTrue(roleResponse.asString().contains("TENANT_ADMIN"));
        given().auth().oauth2(adminB).get(b+"/roles").then().statusCode(200)
                .body("items.size()",org.hamcrest.Matchers.is(1)).body("items[0].code",org.hamcrest.Matchers.is("TENANT_ADMIN"));
        String membership=given().auth().oauth2(adminA).contentType("application/json")
                .body(Map.of("accountId",memberAccount.toString())).post(a+"/memberships")
                .then().statusCode(201).extract().path("membershipId");
        given().auth().oauth2(adminB).post(a+"/memberships/"+membership+"/acceptance").then().statusCode(404);
        given().auth().oauth2(member).post(a+"/memberships/"+membership+"/acceptance").then().statusCode(200);
        String assignment=a+"/memberships/"+membership+"/roles/"+role;
        given().auth().oauth2(adminA).put(assignment).then().statusCode(204);
        given().auth().oauth2(adminA).get(a+"/memberships/"+membership+"/roles").then().statusCode(200);
        given().auth().oauth2(adminA).get(a+"/roles/"+role+"/permissions").then().statusCode(200);
        given().auth().oauth2(adminA).delete(assignment).then().statusCode(204);
        given().auth().oauth2(adminA).post(a+"/memberships/"+membership+"/suspension").then().statusCode(200);
        given().auth().oauth2(adminA).post(a+"/memberships/"+membership+"/reactivation").then().statusCode(200);
        given().auth().oauth2(adminA).put(a+"/memberships/"+membership+"/roles/"+UUID.randomUUID()).then().statusCode(404);
        given().auth().oauth2(adminA).contentType("application/json").body(List.of("USER_MANAGE"))
                .put(a+"/roles/"+role+"/permissions").then().statusCode(404);
        var forbiddenCreate=Map.of("slug","forbidden-"+UUID.randomUUID(),"legalName","Empresa","displayName","Empresa",
                "currencyCode","PEN","timeZone","UTC","initialAdministratorAccountId",aAccount.toString(),"commercialProfiles",List.of());
        given().auth().oauth2(otherClient).contentType("application/json").body(forbiddenCreate).post("/api/v1/platform/tenants").then().statusCode(403);
        String protectedRole=roleResponse.jsonPath().getString("items.find { it.code == 'TENANT_ADMIN' }.roleId");
        assertNotNull(protectedRole);
        given().auth().oauth2(adminA).put(a+"/memberships/"+membership+"/roles/"+protectedRole).then().statusCode(404);
        UUID adminMembership=findMembership(tenantA,aAccount);
        given().auth().oauth2(platform).delete("/api/v1/platform/tenants/"+tenantA+"/administrators/"+adminMembership).then().statusCode(409);
        given().get(a+"/roles").then().statusCode(401);
        given().auth().oauth2("invalid").get(a+"/roles").then().statusCode(401);
        String expired=token(KeycloakRealmResource.MEMBER,"short-lived");
        var claims=new ObjectMapper().readTree(java.util.Base64.getUrlDecoder().decode(expired.split("\\.")[1]));
        long waitMillis=(claims.path("exp").asLong()-java.time.Instant.now().getEpochSecond()+2)*1000;
        assertTrue(waitMillis<=12000,"The Keycloak test client must issue short-lived tokens");
        if(waitMillis>0) Thread.sleep(waitMillis);
        given().auth().oauth2(expired).get(a+"/roles").then().statusCode(401);
        try(var c=DriverManager.getConnection(SprintOneIntegrationResource.jdbc,SprintOneIntegrationResource.username,SprintOneIntegrationResource.password);
            var st=c.createStatement(); var rows=st.executeQuery("SELECT count(*) FROM flyway_schema_history WHERE success AND version IN ('1','2','3','4')")) {
            assertTrue(rows.next()); assertEquals(4,rows.getInt(1));
        }
        long before=countMemberships(tenantA), auditBefore=countAudit(tenantA);
        String container=SprintOneIntegrationResource.postgres.getContainerId();
        try {
            DockerClientFactory.instance().client().pauseContainerCmd(container).exec();
            assertNotNull(token(KeycloakRealmResource.TENANT));
            var unavailable=given().auth().oauth2(adminA).contentType("application/json")
                    .body(Map.of("accountId",otherAccount.toString())).post(a+"/memberships");
            unavailable.then().statusCode(503).contentType("application/problem+json");
            assertFalse(unavailable.asString().contains("SELECT"));
            assertFalse(unavailable.asString().contains(SprintOneIntegrationResource.password));
        } finally {
            DockerClientFactory.instance().client().unpauseContainerCmd(container).exec();
        }
        assertEquals(before,countMemberships(tenantA));
        assertEquals(auditBefore,countAudit(tenantA));
        given().auth().oauth2(adminA).get(a+"/roles").then().statusCode(200);
        given().auth().oauth2(adminA).contentType("application/json").body(Map.of("accountId",otherAccount.toString()))
                .post(a+"/memberships").then().statusCode(201);
    }
    private static long countInitialAdministrator(UUID tenant,UUID account) throws Exception {
        try(var c=DriverManager.getConnection(SprintOneIntegrationResource.jdbc,SprintOneIntegrationResource.username,SprintOneIntegrationResource.password);
            var q=c.prepareStatement("SELECT count(*) FROM tenant_membership m JOIN user_account u ON u.user_id=m.user_id JOIN membership_role mr ON mr.tenant_id=m.tenant_id AND mr.membership_id=m.membership_id JOIN app_role r ON r.tenant_id=mr.tenant_id AND r.role_id=mr.role_id WHERE m.tenant_id=? AND m.user_id=? AND m.status='ACTIVE' AND u.status='ACTIVE' AND r.code='TENANT_ADMIN'")) {
            q.setObject(1,tenant); q.setObject(2,account); try(var rows=q.executeQuery()) { assertTrue(rows.next()); return rows.getLong(1); }
        }
    }
    private static UUID findMembership(UUID tenant,UUID account) throws Exception {
        try(var c=DriverManager.getConnection(SprintOneIntegrationResource.jdbc,SprintOneIntegrationResource.username,SprintOneIntegrationResource.password);
            var q=c.prepareStatement("SELECT membership_id FROM tenant_membership WHERE tenant_id=? AND user_id=?")) {
            q.setObject(1,tenant); q.setObject(2,account); try(var rows=q.executeQuery()) { assertTrue(rows.next()); return rows.getObject(1,UUID.class); }
        }
    }
    private static long countAudit(UUID tenant) throws Exception {
        try(var c=DriverManager.getConnection(SprintOneIntegrationResource.jdbc,SprintOneIntegrationResource.username,SprintOneIntegrationResource.password);
            var q=c.prepareStatement("SELECT count(*) FROM audit_event WHERE tenant_id=?")) {
            q.setObject(1,tenant); try(var rows=q.executeQuery()) { assertTrue(rows.next()); return rows.getLong(1); }
        }
    }
    private static long countMemberships(UUID tenant) throws Exception {
        try(var c=DriverManager.getConnection(SprintOneIntegrationResource.jdbc,SprintOneIntegrationResource.username,SprintOneIntegrationResource.password);
            var q=c.prepareStatement("SELECT count(*) FROM tenant_membership WHERE tenant_id=?")) {
            q.setObject(1,tenant); try(var rows=q.executeQuery()) { assertTrue(rows.next()); return rows.getLong(1); }
        }
    }
    private static UUID create(String token, UUID target, String prefix,List<String> profiles) {
        var body=Map.of("slug",prefix+UUID.randomUUID(),"legalName","Empresa","displayName","Empresa",
                "currencyCode","PEN","timeZone","UTC","initialAdministratorAccountId",target.toString(),"commercialProfiles",profiles);
        return UUID.fromString(given().auth().oauth2(token).contentType("application/json").body(body)
                .post("/api/v1/platform/tenants").then().statusCode(201).extract().path("tenantId"));
    }
    private static UUID account(String token) {
        return UUID.fromString(given().auth().oauth2(token).post("/api/v1/accounts/me/provisioning")
                .then().statusCode(200).extract().path("accountId"));
    }
    private static String token(String username) throws Exception { return token(username,"commercecore-api"); }
    private static String token(String username,String client) throws Exception {
        String form="grant_type=password&client_id="+URLEncoder.encode(client,StandardCharsets.UTF_8)+"&username="+URLEncoder.encode(username,StandardCharsets.UTF_8)
                +"&password="+URLEncoder.encode(KeycloakRealmResource.PASSWORD,StandardCharsets.UTF_8);
        var request=HttpRequest.newBuilder(URI.create(SprintOneIntegrationResource.issuer+"/protocol/openid-connect/token"))
                .header("Content-Type","application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(form)).build();
        var response=HttpClient.newHttpClient().send(request,HttpResponse.BodyHandlers.ofString());
        assertEquals(200,response.statusCode()); return new ObjectMapper().readTree(response.body()).path("access_token").asText();
    }
}
