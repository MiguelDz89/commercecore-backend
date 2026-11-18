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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
    void incompleteOrRepeatedOnboardingCannotCommitThroughCdi() throws Exception {
        String admin=token(KeycloakRealmResource.ADMIN,"commercecore-api");
        provision(admin);
        UUID target=provision(token(KeycloakRealmResource.ONBOARD_TARGET,"commercecore-api"));
        for (String scenario : java.util.List.of("repository","direct","noaudit","premature","double","other-tenant")) {
            String slug="integrity-"+UUID.randomUUID();
            given().auth().oauth2(admin).post("/test-only-platform-authorization/integrity/"+scenario+"/"+slug+"/"+target)
                    .then().statusCode(scenario.equals("direct") || scenario.equals("noaudit") ? 500 : 403);
            assertEquals(0,sqlCount("SELECT count(*) FROM tenant WHERE slug IN (?,?)",slug,slug+"-other"),scenario);
            assertEquals(0,sqlCount("SELECT count(*) FROM audit_event a JOIN tenant t ON t.tenant_id=a.tenant_id WHERE t.slug IN (?,?)",slug,slug+"-other"),scenario);
        }
    }

    @Test
    void onboardingReceiptsCannotCrossCommitOrRollback() throws Exception {
        String admin=token(KeycloakRealmResource.ADMIN,"commercecore-api");
        provision(admin);
        UUID target=provision(token(KeycloakRealmResource.ONBOARD_TARGET,"commercecore-api"));
        for (String outcome : java.util.List.of("commit","rollback")) {
            String slug="receipt-"+UUID.randomUUID();
            given().auth().oauth2(admin).post("/test-only-platform-authorization/integrity-reuse/"+outcome+"/"+slug+"/"+target)
                    .then().statusCode(204);
            assertEquals(outcome.equals("commit") ? 1 : 0,sqlCount("SELECT count(*) FROM tenant WHERE slug=?",slug));
        }
    }

    @Test
    void administrativeHttpApiAndExternalTransaction() throws Exception {
        String admin=token(KeycloakRealmResource.ADMIN,"commercecore-api");
        String member=token(KeycloakRealmResource.MEMBER,"commercecore-api");
        String other=token(KeycloakRealmResource.OTHER,"commercecore-api");
        String tenantAdmin=token(KeycloakRealmResource.TENANT,"commercecore-api");
        String unprovisioned=token(KeycloakRealmResource.UNPROVISIONED,"commercecore-api");
        String accountPath="/api/v1/accounts/me/provisioning";
        given().post(accountPath).then().statusCode(401).header("WWW-Authenticate",org.hamcrest.Matchers.containsString("Bearer")).contentType("application/problem+json");
        given().auth().oauth2("bad").post(accountPath).then().statusCode(401).contentType("application/problem+json");
        String actor=given().auth().oauth2(admin).post(accountPath).then().statusCode(200).extract().path("accountId");
        assertEquals(actor,given().auth().oauth2(admin).post(accountPath).then().statusCode(200).extract().path("accountId"));
        UUID target=provision(token(KeycloakRealmResource.ONBOARD_TARGET,"commercecore-api"));
        String slug="api-"+UUID.randomUUID();
        var request=new java.util.HashMap<String,Object>();
        request.put("slug",slug); request.put("legalName","Empresa Legal"); request.put("displayName","Empresa");
        request.put("currencyCode","PEN"); request.put("timeZone","UTC");
        request.put("initialAdministratorAccountId",target.toString()); request.put("commercialProfiles",java.util.List.of());
        request.put("fractionalQuantitiesEnabled",true);
        String path="/api/v1/platform/tenants";
        given().contentType("application/json").body(request).post(path).then().statusCode(401);
        for(String t:java.util.List.of(member,other,tenantAdmin,unprovisioned))
            given().auth().oauth2(t).contentType("application/json").body(request).post(path).then().statusCode(403);
        UUID id=UUID.fromString(given().auth().oauth2(admin).contentType("application/json").body(request).post(path)
                .then().statusCode(201).extract().path("tenantId"));
        assertEquals(1,sqlCount("SELECT count(*) FROM tenant WHERE tenant_id=? AND fractional_quantities_enabled",id));
        assertEquals(1,sqlCount("SELECT count(*) FROM tenant WHERE tenant_id=?",id));
        var duplicate=given().auth().oauth2(admin).contentType("application/json").body(request).post(path);
        duplicate.then().statusCode(409).contentType("application/problem+json");
        assertNotNull(duplicate.jsonPath().getString("correlationId"));
        assertFalse(duplicate.asString().contains("tenant_slug_key"));
        request.put("slug","api-profile-"+UUID.randomUUID()); request.put("commercialProfiles",java.util.List.of("CATALOG_VIEWER"));
        given().auth().oauth2(admin).contentType("application/json").body(request).post(path).then().statusCode(201);
        request.put("slug","api-missing-"+UUID.randomUUID()); request.put("initialAdministratorAccountId",UUID.randomUUID().toString());
        given().auth().oauth2(admin).contentType("application/json").body(request).post(path).then().statusCode(403);
        request.put("initialAdministratorAccountId",target.toString());
        request.put("slug","INVALID SLUG");
        given().auth().oauth2(admin).contentType("application/json").body(request).post(path).then().statusCode(400).contentType("application/problem+json");
        request.put("slug","api-invalid-"+UUID.randomUUID()); request.put("commercialProfiles",java.util.List.of("UNKNOWN"));
        given().auth().oauth2(admin).contentType("application/json").body(request).post(path).then().statusCode(400);
        request.put("commercialProfiles",java.util.List.of()); request.put("actor","fake");
        given().auth().oauth2(admin).contentType("application/json").body(request).post(path).then().statusCode(400);
        String external="external-"+UUID.randomUUID();
        given().auth().oauth2(admin).post("/test-only-platform-authorization/onboard-in-transaction/"+external+"/"+target)
                .then().statusCode(500);
        assertEquals(0,sqlCount("SELECT count(*) FROM tenant WHERE slug=?",external));
    }

    @Test
    void administrativeHttpRollsBackOnAuditAndDeferredConstraint() throws Exception {
        String admin=token(KeycloakRealmResource.ADMIN,"commercecore-api");
        provision(admin); UUID target=provision(token(KeycloakRealmResource.ONBOARD_TARGET,"commercecore-api"));
        for(String table:java.util.List.of("audit_event","role_permission")) {
            String slug="api-failure-"+UUID.randomUUID(), suffix=UUID.randomUUID().toString().replace("-","");
            String function="api_fail_"+suffix, trigger="api_trigger_"+suffix;
            String action=table.equals("role_permission") ? "IF NEW.permission_code='USER_MANAGE' THEN RETURN NULL; END IF;"
                    : "RAISE EXCEPTION 'sensitive test failure';";
            try {
                try(var c=dataSource.getConnection(); var st=c.createStatement()) {
                    st.execute("CREATE FUNCTION "+function+"() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF EXISTS (SELECT 1 FROM tenant WHERE tenant_id=NEW.tenant_id AND slug='"+slug+"') THEN "+action+" END IF; RETURN NEW; END $$");
                    st.execute("CREATE TRIGGER "+trigger+" BEFORE INSERT ON "+table+" FOR EACH ROW EXECUTE FUNCTION "+function+"()");
                }
                var body=java.util.Map.of("slug",slug,"legalName","Legal","displayName","Empresa","currencyCode","PEN",
                        "timeZone","UTC","initialAdministratorAccountId",target.toString(),"commercialProfiles",java.util.List.of());
                var response=given().auth().oauth2(admin).contentType("application/json").body(body).post("/api/v1/platform/tenants");
                response.then().statusCode(500).contentType("application/problem+json");
                assertFalse(response.asString().contains("sensitive test failure"));
                assertEquals(0,sqlCount("SELECT count(*) FROM tenant WHERE slug=?",slug));
            } finally {
                try(var c=dataSource.getConnection(); var st=c.createStatement()) {
                    st.execute("DROP TRIGGER IF EXISTS "+trigger+" ON "+table);
                    st.execute("DROP FUNCTION IF EXISTS "+function+"()");
                }
            }
        }
    }

    @Test
    void administrativeHttpConcurrentSlugHasOneWinner() throws Exception {
        String admin=token(KeycloakRealmResource.ADMIN,"commercecore-api");
        provision(admin); UUID target=provision(token(KeycloakRealmResource.ONBOARD_TARGET,"commercecore-api"));
        String slug="api-race-"+UUID.randomUUID();
        var body=java.util.Map.of("slug",slug,"legalName","Legal","displayName","Empresa","currencyCode","PEN",
                "timeZone","UTC","initialAdministratorAccountId",target.toString(),"commercialProfiles",java.util.List.of());
        var start=new java.util.concurrent.CountDownLatch(1); var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Callable<Integer> call=() -> { start.await(); return given().auth().oauth2(admin)
                    .contentType("application/json").body(body).post("/api/v1/platform/tenants").statusCode(); };
            var a=pool.submit(call); var b=pool.submit(call); start.countDown();
            assertEquals(java.util.Set.of(201,409),java.util.Set.of(a.get(),b.get()));
            assertEquals(1,sqlCount("SELECT count(*) FROM tenant WHERE slug=?",slug));
        } finally { pool.shutdownNow(); }
    }

    @Test
    void openApiDocumentsAdministrativeEndpointsAndBearer() throws Exception {
        String json=given().queryParam("format","json").get("/q/openapi").then().statusCode(200).extract().asString();
        var document=new ObjectMapper().enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).readTree(json);
        assertTrue(document.path("paths").has("/api/v1/accounts/me/provisioning"));
        assertTrue(document.path("paths").has("/api/v1/platform/tenants"));
        assertTrue(document.path("components").path("securitySchemes").has("bearerAuth"));
        assertPriceOpenApi(document);
        String base="/api/v1/tenants/{tenantId}/memberships";
        assertTrue(document.path("paths").path(base).has("get"));
        assertTrue(document.path("paths").path(base).has("post"));
        for(String action:java.util.List.of("acceptance","suspension","reactivation"))
            assertTrue(document.path("paths").path(base+"/{membershipId}/"+action).has("post"));
        String roles="/api/v1/tenants/{tenantId}/roles";
        assertTrue(document.path("paths").path(roles).has("get"));
        assertTrue(document.path("paths").path(roles+"/{roleId}/permissions").has("get"));
        assertTrue(document.path("paths").path(roles+"/{roleId}/permissions").has("put"));
        String memberRoles=base+"/{membershipId}/roles";
        assertTrue(document.path("paths").path(memberRoles).has("get"));
        assertTrue(document.path("paths").path(memberRoles+"/{roleId}").has("put"));
        assertTrue(document.path("paths").path(memberRoles+"/{roleId}").has("delete"));
        String platform="/api/v1/platform/tenants/{tenantId}/administrators/{membershipId}";
        assertTrue(document.path("paths").path(platform).has("put"));
        assertTrue(document.path("paths").path(platform).has("delete"));
        String categories="/api/v1/tenants/{tenantId}/categories";
        assertTrue(document.path("paths").path(categories).has("get"));
        assertTrue(document.path("paths").path(categories).has("post"));
        assertTrue(document.path("paths").path(categories+"/{categoryId}").has("get"));
        assertTrue(document.path("paths").path(categories+"/{categoryId}").has("patch"));
        assertTrue(document.path("paths").path(categories+"/{categoryId}/deactivation").has("post"));
        assertTrue(document.path("paths").path(categories+"/{categoryId}/reactivation").has("post"));
        String products="/api/v1/tenants/{tenantId}/products";
        assertTrue(document.path("paths").path(products).has("get"));
        assertTrue(document.path("paths").path(products).has("post"));
        assertTrue(document.path("paths").path(products+"/{productId}").has("get"));
        assertTrue(document.path("paths").path(products+"/{productId}").has("patch"));
    }

    @Test
    void openApiDocumentsProductStatusChanges() throws Exception {
        String json = given().queryParam("format", "json").get("/q/openapi").then().statusCode(200).extract().asString();
        var document = new ObjectMapper().readTree(json);
        String root = "/api/v1/tenants/{tenantId}/products";
        var single = assertStatusChangeOperation(document, root + "/{productId}/status", "patch", "tenantId", "productId");
        var batch = assertStatusChangeOperation(document, root + "/status-changes", "post", "tenantId");
        assertEquals(java.util.Set.of("target", "version"), stringValues(single.path("required")));
        assertEquals(java.util.Set.of("target", "items"), stringValues(batch.path("required")));
        assertEquals(java.util.Set.of("target", "version", "reason"), fields(single.path("properties")));
        assertEquals(java.util.Set.of("target", "items", "reason"), fields(batch.path("properties")));
        for (var request : java.util.List.of(single, batch)) {
            assertEquals(com.fasterxml.jackson.databind.node.BooleanNode.FALSE, request.path("additionalProperties"));
            var target = priceSchema(document, request.path("properties").path("target"));
            assertEquals(java.util.Set.of("PENDING_RECEIPT", "ACTIVE", "INACTIVE", "ARCHIVED"), stringValues(target.path("enum")));
            var reason = request.path("properties").path("reason");
            assertPriceType(reason, "string", "", true);
            assertTrue(reason.path("description").asText().contains("INACTIVE y ARCHIVED"));
        }
        var items = batch.path("properties").path("items");
        assertPriceType(items, "array", "", false);
        assertEquals(1, items.path("minItems").asInt());
        var item = priceSchema(document, items.path("items"));
        assertFields(item, "productId", "version");
        assertEquals(com.fasterxml.jackson.databind.node.BooleanNode.FALSE, item.path("additionalProperties"));
        assertPriceType(priceSchema(document, item.path("properties").path("productId")), "string", "uuid", false);
        for (var schema : java.util.List.of(single, item)) {
            var version = schema.path("properties").path("version");
            assertPriceType(version, "integer", "int64", false);
            assertTrue(version.path("minimum").isNumber());
            assertEquals(0, version.path("minimum").asInt());
        }
    }

    private static com.fasterxml.jackson.databind.JsonNode assertStatusChangeOperation(
            com.fasterxml.jackson.databind.JsonNode document, String route, String method, String... pathIds) {
        var path = document.path("paths").path(route);
        var operation = path.path(method);
        assertTrue(operation.isObject(), route);
        assertEquals(java.util.Set.of("bearerAuth"), fields(operation.path("security").get(0)));
        var ids = new java.util.HashSet<String>();
        for (var parent : java.util.List.of(path, operation)) {
            for (var parameter : parent.path("parameters")) {
                var resolved = priceSchema(document, parameter);
                assertEquals("path", resolved.path("in").asText());
                assertTrue(resolved.path("required").asBoolean());
                assertPriceType(priceSchema(document, resolved.path("schema")), "string", "uuid", false);
                ids.add(resolved.path("name").asText());
            }
        }
        assertEquals(java.util.Set.of(pathIds), ids);
        var responses = operation.path("responses");
        assertEquals(java.util.Set.of("200", "400", "401", "404", "409", "500", "503"), fields(responses));
        var success = priceSchema(document, responses.path("200").path("content").path("application/json").path("schema"));
        assertFields(success, "correlationId");
        assertPriceType(priceSchema(document, success.path("properties").path("correlationId")), "string", "uuid", false);
        for (String code : java.util.List.of("400", "401", "404", "500", "503")) {
            var error = priceSchema(document, responses.path(code).path("content").path("application/problem+json").path("schema"));
            assertFields(error, "type", "title", "status", "detail", "instance", "correlationId");
        }
        assertStatusConflictSchema(document,
                responses.path("409").path("content").path("application/problem+json").path("schema"));
        var request = operation.path("requestBody");
        assertTrue(request.path("required").asBoolean());
        return priceSchema(document, request.path("content").path("application/json").path("schema"));
    }

    private static void assertStatusConflictSchema(com.fasterxml.jackson.databind.JsonNode document,
                                                   com.fasterxml.jackson.databind.JsonNode schema) {
        var alternatives = priceSchema(document, schema).path("anyOf");
        assertEquals(2, alternatives.size(), schema.toString());
        var problem = priceSchema(document, alternatives.get(0));
        assertFields(problem, "type", "title", "status", "detail", "instance", "correlationId");
        var extension = priceSchema(document, alternatives.get(1));
        var inherited = new java.util.ArrayList<com.fasterxml.jackson.databind.JsonNode>();
        extension.path("allOf").forEach(part -> inherited.add(priceSchema(document, part)));
        assertTrue(inherited.contains(problem), extension.toString());
        inherited.add(extension);
        var conflictProperties = inherited.stream().filter(part -> part.path("properties").has("conflicts"))
                .findFirst().orElseThrow();
        var expected = new java.util.HashSet<>(fields(problem.path("properties")));
        expected.add("conflicts");
        var effectiveProperties = new java.util.HashSet<String>();
        var effectiveRequired = new java.util.HashSet<String>();
        for (var part : inherited) {
            effectiveProperties.addAll(fields(part.path("properties")));
            effectiveRequired.addAll(stringValues(part.path("required")));
        }
        assertEquals(expected, effectiveProperties);
        assertEquals(expected, effectiveRequired);
        var conflicts = conflictProperties.path("properties").path("conflicts");
        assertPriceType(conflicts, "array", "", false);
        assertEquals(1, conflicts.path("minItems").asInt());
        var item = priceSchema(document, conflicts.path("items"));
        assertEquals(java.util.Set.of("productId", "code", "variantId"), fields(item.path("properties")));
        assertEquals(java.util.Set.of("productId", "code"), stringValues(item.path("required")));
        for (String id : java.util.List.of("productId", "variantId")) {
            assertPriceType(priceSchema(document, item.path("properties").path(id)), "string", "uuid", false);
        }
        var code = priceSchema(document, item.path("properties").path("code"));
        assertEquals(java.util.Set.of("VERSION_CONFLICT", "INVALID_TRANSITION", "UNARCHIVED_VARIANT", "CONCURRENT_MODIFICATION"),
                stringValues(code.path("enum")));
    }

    private static java.util.Set<String> stringValues(com.fasterxml.jackson.databind.JsonNode array) {
        var values = new java.util.HashSet<String>();
        array.forEach(value -> values.add(value.asText()));
        return values;
    }

    @Test
    void changesProductStatusThroughBothAuthenticatedRoutes() throws Exception {
        String admin = token(KeycloakRealmResource.ADMIN, "commercecore-api");
        var fixture = statusFixture(admin);
        UUID tenant = fixture.tenant();
        String products = fixture.products();
        var ids = new java.util.ArrayList<String>();
        for (String sku : java.util.List.of("SKU-1", "SKU-2")) {
            ids.add(createStatusProduct(admin, fixture, java.util.Map.of("sku", sku, "name", sku)));
        }
        String single = products + "/" + ids.getFirst() + "/status";
        String batch = products + "/status-changes";
        String activation = """
                {"target":"ACTIVE","version":0}
                """;
        String archiving = """
                {"target":"ARCHIVED","reason":"Fin de vida","items":[
                    {"productId":"%s","version":1},{"productId":"%s","version":0}]}
                """.formatted(ids.getFirst(), ids.getLast());
        given().contentType("application/json").body(activation).patch(single).then().statusCode(401)
                .header("WWW-Authenticate", org.hamcrest.Matchers.startsWith("Bearer"));
        given().auth().oauth2("invalid").contentType("application/json").body(archiving).post(batch)
                .then().statusCode(401).contentType("application/problem+json");

        for (String invalid : java.util.List.of(
                "{\"target\":\"ACTIVE\",\"version\":0,\"productId\":\"ignored\"}",
                "{\"target\":\"ACTIVE\",\"version\":0,\"tenantId\":\"ignored\"}",
                "{\"target\":\"ACTIVE\",\"version\":0.0}",
                "{\"target\":\"ACTIVE\",\"version\":-1}",
                "{\"target\":\"ACTIVE\",\"version\":9223372036854775808}",
                "{\"target\":\"ACTIVE\"}", "{\"target\":\"DRAFT\",\"version\":0}",
                "{\"target\":\"INACTIVE\",\"version\":0,\"reason\":\" \"}",
                activation + "{}")) {
            given().auth().oauth2(admin).contentType("application/json").body(invalid).patch(single)
                    .then().statusCode(400).contentType("application/problem+json").body("status", is(400));
        }
        given().auth().oauth2(admin).contentType("application/json").body(activation)
                .patch(products + "/1-1-1-1-1/status").then().statusCode(400);
        given().auth().oauth2(admin).contentType("application/json").body(activation)
                .patch(single.replace(tenant.toString(), "invalid-tenant")).then().statusCode(400);
        given().auth().oauth2(admin).contentType("application/json").body("{\"status\":\"ACTIVE\",\"version\":0}")
                .patch(products + "/" + ids.getFirst()).then().statusCode(400);

        for (String invalid : java.util.List.of(
                "{\"target\":\"ACTIVE\",\"items\":[]}",
                "{\"target\":\"ACTIVE\",\"items\":[null]}",
                "{\"target\":\"ARCHIVED\",\"items\":[{\"productId\":\"%s\",\"version\":0}]}".formatted(ids.getFirst()),
                "{\"target\":\"ACTIVE\",\"items\":[{\"productId\":\"not-a-uuid\",\"version\":0}]}",
                "{\"target\":\"ACTIVE\",\"items\":[{\"productId\":\"%s\",\"version\":0,\"tenantId\":\"ignored\"}]}".formatted(ids.getFirst()),
                "{\"target\":\"ACTIVE\",\"items\":[{\"productId\":\"%s\",\"version\":0},{\"productId\":\"%s\",\"version\":1}]}".formatted(ids.getFirst(), ids.getFirst()))) {
            given().auth().oauth2(admin).contentType("application/json").body(invalid).post(batch)
                    .then().statusCode(400).contentType("application/problem+json").body("status", is(400));
        }

        String activationId = given().auth().oauth2(admin).contentType("application/json").body(activation).patch(single)
                .then().statusCode(200).extract().path("correlationId");
        assertEquals(activationId, UUID.fromString(activationId).toString());
        assertEquals(1, sqlCount("""
                SELECT count(*) FROM audit_event WHERE tenant_id=? AND entity_id=? AND correlation_id=?
                AND action='PRODUCT_STATUS_CHANGED' AND after_data->>'status'='ACTIVE'
                """, tenant, UUID.fromString(ids.getFirst()), activationId));
        given().auth().oauth2(admin).contentType("application/json")
                .body("{\"target\":\"PENDING_RECEIPT\",\"version\":0}").patch(single)
                .then().statusCode(409).contentType("application/problem+json").body("status", is(409))
                .body("conflicts", is(java.util.List.of(java.util.Map.of("productId", ids.getFirst(), "code", "VERSION_CONFLICT"))));

        String archiveId = given().auth().oauth2(admin).contentType("application/json").body(archiving).post(batch)
                .then().statusCode(200).extract().path("correlationId");
        assertEquals(archiveId, UUID.fromString(archiveId).toString());
        assertEquals(2, sqlCount("""
                SELECT count(DISTINCT entity_id) FROM audit_event WHERE tenant_id=? AND correlation_id=?
                AND entity_id IN (?,?) AND action='PRODUCT_STATUS_CHANGED' AND after_data->>'status'='ARCHIVED'
                """, tenant, archiveId, UUID.fromString(ids.getFirst()), UUID.fromString(ids.getLast())));
    }

    @Test
    void returnsAuthorizedProductStatusConflictsWithoutLeakingOrChangingTheBatch() throws Exception {
        String admin = token(KeycloakRealmResource.ADMIN, "commercecore-api");
        var fixture = statusFixture(admin);
        var foreign = statusFixture(admin);
        String validId = createStatusProduct(admin, fixture, java.util.Map.of("sku", "VALID", "name", "Producto válido"));
        String archivedId = createStatusProduct(admin, fixture, java.util.Map.of("sku", "ARCHIVED", "name", "Archivado"));
        String groupId = createStatusProduct(admin, fixture, java.util.Map.of("type", "VARIABLE", "name", "Agrupador"));
        var variantIds = new java.util.ArrayList<String>();
        for (String size : java.util.List.of("S", "M")) {
            variantIds.add(createStatusProduct(admin, fixture, java.util.Map.of(
                    "type", "VARIANT", "parentId", groupId, "sku", "GROUP-" + size, "name", "Variante " + size,
                    "attributes", java.util.Map.of("size", size))));
        }
        String foreignId = createStatusProduct(admin, foreign, java.util.Map.of("sku", "FOREIGN", "name", "Ajeno"));
        try (var connection = dataSource.getConnection();
                var activate = connection.prepareStatement("""
                        UPDATE product SET status='ACTIVE', published=true, ever_published=true
                        WHERE tenant_id=? AND product_id IN (?,?)
                        """);
                var archive = connection.prepareStatement("""
                        UPDATE product SET status='ARCHIVED' WHERE tenant_id=? AND product_id=?
                        """)) {
            activate.setObject(1, fixture.tenant());
            activate.setObject(2, UUID.fromString(validId));
            activate.setObject(3, UUID.fromString(groupId));
            assertEquals(2, activate.executeUpdate());
            archive.setObject(1, fixture.tenant());
            archive.setObject(2, UUID.fromString(archivedId));
            assertEquals(1, archive.executeUpdate());
        }
        String before = productRows(fixture.tenant());
        String foreignBefore = productRows(foreign.tenant());
        var mapper = new ObjectMapper();
        var request = mapper.createObjectNode().put("target", "ARCHIVED").put("reason", "Fin de vida");
        var items = request.putArray("items");
        items.addObject().put("productId", validId).put("version", 0);
        items.addObject().put("productId", groupId).put("version", 0);
        items.addObject().put("productId", archivedId).put("version", 1);
        String path = fixture.products() + "/status-changes";
        var result = mapper.readTree(given().auth().oauth2(admin).contentType("application/json").body(request.toString())
                .post(path).then().statusCode(409).contentType("application/problem+json").extract().asString());
        assertEquals(java.util.Set.of("type", "title", "status", "detail", "instance", "correlationId", "conflicts"), fields(result));
        assertEquals(path, result.path("instance").asText());
        assertEquals(409, result.path("status").asInt());
        UUID.fromString(result.path("correlationId").asText());
        var expected = new java.util.HashSet<com.fasterxml.jackson.databind.JsonNode>();
        expected.add(mapper.createObjectNode().put("productId", archivedId).put("code", "VERSION_CONFLICT"));
        expected.add(mapper.createObjectNode().put("productId", archivedId).put("code", "INVALID_TRANSITION"));
        for (String variantId : variantIds) {
            expected.add(mapper.createObjectNode().put("productId", groupId).put("code", "UNARCHIVED_VARIANT")
                    .put("variantId", variantId));
        }
        var conflicts = result.path("conflicts");
        var actual = new java.util.HashSet<com.fasterxml.jackson.databind.JsonNode>();
        conflicts.forEach(actual::add);
        assertEquals(expected.size(), conflicts.size());
        assertEquals(expected, actual);

        given().auth().oauth2(admin).contentType("application/json").body("{\"target\":\"ACTIVE\",\"version\":0}")
                .patch(fixture.products() + "/" + archivedId + "/status")
                .then().statusCode(409).contentType("application/problem+json")
                .body("conflicts", is(java.util.List.of(java.util.Map.of("productId", archivedId, "code", "INVALID_TRANSITION"))));

        var forbiddenItem = items.addObject().put("version", 0);
        forbiddenItem.put("productId", foreignId);
        String denied = given().auth().oauth2(admin).contentType("application/json").body(request.toString())
                .post(path).then().statusCode(404).contentType("application/problem+json").extract().asString();
        forbiddenItem.put("productId", UUID.randomUUID().toString());
        String missing = given().auth().oauth2(admin).contentType("application/json").body(request.toString())
                .post(path).then().statusCode(404).contentType("application/problem+json").extract().asString();
        var deniedProblem = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(denied);
        var missingProblem = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(missing);
        deniedProblem.remove("correlationId");
        missingProblem.remove("correlationId");
        assertEquals(missingProblem, deniedProblem);
        assertFalse(deniedProblem.has("conflicts"));
        assertFalse(denied.contains(foreignId));
        assertFalse(denied.contains("FOREIGN"));
        assertEquals(before, productRows(fixture.tenant()));
        assertEquals(foreignBefore, productRows(foreign.tenant()));
        assertEquals(0, sqlCount("""
                SELECT count(*) FROM audit_event WHERE tenant_id IN (?,?) AND action='PRODUCT_STATUS_CHANGED'
                """, fixture.tenant(), foreign.tenant()));
    }

    @Test
    void enforcesStatusPermissionForRealCommercialActorsOnBothRoutes() throws Exception {
        String admin = token(KeycloakRealmResource.ADMIN, "commercecore-api");
        String commercial = token(KeycloakRealmResource.MEMBER, "commercecore-api");
        String noMembership = token(KeycloakRealmResource.OTHER, "commercecore-api");
        String wrongAudience = token(KeycloakRealmResource.MEMBER, "other-client");
        UUID actor = provision(commercial);
        provision(noMembership);
        var fixture = statusFixture(admin);
        String first = createStatusProduct(admin, fixture, java.util.Map.of("sku", "PERM-1", "name", "Uno"));
        String second = createStatusProduct(admin, fixture, java.util.Map.of("sku", "PERM-2", "name", "Dos"));
        String rejected = createStatusProduct(admin, fixture, java.util.Map.of("sku", "PERM-3", "name", "Tres"));
        UUID member = UUID.randomUUID();
        try {
            try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
                statement.executeUpdate("INSERT INTO tenant_membership(membership_id,tenant_id,user_id,status) VALUES ('"
                        + member + "','" + fixture.tenant() + "','" + actor + "','ACTIVE')");
                statement.executeUpdate("INSERT INTO membership_role(tenant_id,membership_id,role_id) "
                        + "SELECT tenant_id,'" + member + "',role_id FROM app_role WHERE tenant_id='"
                        + fixture.tenant() + "' AND code='CATALOG_MANAGER'");
            }
            String singleCorrelation = given().auth().oauth2(commercial).contentType("application/json")
                    .body("{\"target\":\"ACTIVE\",\"version\":0}")
                    .patch(fixture.products() + "/" + first + "/status").then().statusCode(200)
                    .extract().path("correlationId");
            String batchCorrelation = given().auth().oauth2(commercial).contentType("application/json")
                    .body("{\"target\":\"ACTIVE\",\"items\":[{\"productId\":\"" + second + "\",\"version\":0}]}")
                    .post(fixture.products() + "/status-changes").then().statusCode(200)
                    .extract().path("correlationId");
            assertEquals(1, sqlCount("SELECT count(*) FROM audit_event WHERE tenant_id=? AND entity_id=? AND actor_user_id=? AND correlation_id=? AND action='PRODUCT_STATUS_CHANGED' AND before_data->>'status'='DRAFT' AND after_data->>'status'='ACTIVE'", fixture.tenant(), UUID.fromString(first), actor, singleCorrelation));
            assertEquals(1, sqlCount("SELECT count(*) FROM audit_event WHERE tenant_id=? AND entity_id=? AND actor_user_id=? AND correlation_id=? AND action='PRODUCT_STATUS_CHANGED' AND before_data->>'status'='DRAFT' AND after_data->>'status'='ACTIVE'", fixture.tenant(), UUID.fromString(second), actor, batchCorrelation));

            String before = productRows(fixture.tenant());
            int auditBefore = sqlCount("SELECT count(*) FROM audit_event WHERE tenant_id=? AND action='PRODUCT_STATUS_CHANGED'", fixture.tenant());
            assertStatusDeniedBothRoutes(fixture, rejected, null, 401);
            assertStatusDeniedBothRoutes(fixture, rejected, "invalid", 401);
            assertStatusDeniedBothRoutes(fixture, rejected, wrongAudience, 401);
            assertStatusDeniedBothRoutes(fixture, rejected, noMembership, 404);

            try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
                statement.executeUpdate("DELETE FROM role_permission WHERE tenant_id='" + fixture.tenant()
                        + "' AND permission_code='PRODUCT_STATUS_UPDATE'");
            }
            assertEquals(1, sqlCount("SELECT count(*) FROM role_permission p JOIN membership_role m ON p.tenant_id=m.tenant_id AND p.role_id=m.role_id WHERE m.tenant_id=? AND m.membership_id=? AND p.permission_code='PRODUCT_UPDATE'", fixture.tenant(), member));
            assertStatusDeniedBothRoutes(fixture, rejected, commercial, 404);

            try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
                statement.executeUpdate("INSERT INTO role_permission(tenant_id,role_id,permission_code) SELECT tenant_id,role_id,'PRODUCT_STATUS_UPDATE' FROM app_role WHERE tenant_id='" + fixture.tenant() + "' AND code='CATALOG_MANAGER'");
                statement.executeUpdate("UPDATE user_account SET status='DISABLED' WHERE user_id='" + actor + "'");
            }
            assertStatusDeniedBothRoutes(fixture, rejected, commercial, 404);
            try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
                statement.executeUpdate("UPDATE user_account SET status='ACTIVE' WHERE user_id='" + actor + "'");
                statement.executeUpdate("UPDATE tenant_membership SET status='SUSPENDED' WHERE membership_id='" + member + "'");
            }
            assertStatusDeniedBothRoutes(fixture, rejected, commercial, 404);
            try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
                statement.executeUpdate("UPDATE tenant_membership SET status='ACTIVE' WHERE membership_id='" + member + "'");
                statement.executeUpdate("UPDATE tenant SET status='SUSPENDED' WHERE tenant_id='" + fixture.tenant() + "'");
            }
            assertStatusDeniedBothRoutes(fixture, rejected, commercial, 404);
            assertEquals(before, productRows(fixture.tenant()));
            assertEquals(auditBefore, sqlCount("SELECT count(*) FROM audit_event WHERE tenant_id=? AND action='PRODUCT_STATUS_CHANGED'", fixture.tenant()));
        } finally {
            try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
                statement.executeUpdate("UPDATE user_account SET status='ACTIVE' WHERE user_id='" + actor + "'");
            }
        }
    }

    private void assertStatusDeniedBothRoutes(StatusFixture fixture, String product, String token, int expected) {
        var single = given().contentType("application/json").body("{\"target\":\"ACTIVE\",\"version\":0}");
        var batch = given().contentType("application/json").body("{\"target\":\"ACTIVE\",\"items\":[{\"productId\":\"" + product + "\",\"version\":0}]}");
        if (token != null) {
            single.auth().oauth2(token);
            batch.auth().oauth2(token);
        }
        single.patch(fixture.products() + "/" + product + "/status").then().statusCode(expected);
        batch.post(fixture.products() + "/status-changes").then().statusCode(expected);
    }

    private record StatusFixture(UUID tenant, String category, String products) { }

    private StatusFixture statusFixture(String admin) throws Exception {
        UUID account = provision(admin);
        var setup = java.util.Map.of("slug", "status-" + UUID.randomUUID(), "legalName", "Empresa",
                "displayName", "Empresa", "currencyCode", "PEN", "timeZone", "UTC",
                "initialAdministratorAccountId", account.toString(), "commercialProfiles", java.util.List.of("CATALOG_MANAGER"));
        UUID tenant = UUID.fromString(given().auth().oauth2(admin).contentType("application/json").body(setup)
                .post("/api/v1/platform/tenants").then().statusCode(201).extract().path("tenantId"));
        try (var connection = dataSource.getConnection(); var grant = connection.prepareStatement("""
                INSERT INTO role_permission (tenant_id,role_id,permission_code)
                SELECT tenant_id,role_id,'PRODUCT_STATUS_UPDATE' FROM app_role WHERE tenant_id=? AND code='CATALOG_MANAGER'
                """)) {
            grant.setObject(1, tenant);
            assertEquals(1, grant.executeUpdate());
        }
        String tenantPath = "/api/v1/tenants/" + tenant;
        String category = given().auth().oauth2(admin).contentType("application/json")
                .body(java.util.Map.of("code", "CAT", "name", "Categoría"))
                .post(tenantPath + "/categories").then().statusCode(201).extract().path("categoryId");
        return new StatusFixture(tenant, category, tenantPath + "/products");
    }

    private static String createStatusProduct(String admin, StatusFixture fixture, java.util.Map<String, ?> values) {
        var request = new java.util.HashMap<String, Object>(values);
        request.put("categoryId", fixture.category());
        return given().auth().oauth2(admin).contentType("application/json").body(request)
                .post(fixture.products()).then().statusCode(201).extract().path("productId");
    }

    private String productRows(UUID tenant) throws Exception {
        try (var connection = dataSource.getConnection(); var query = connection.prepareStatement("""
                SELECT jsonb_agg(to_jsonb(p) ORDER BY p.product_id)::text FROM product p WHERE p.tenant_id=?
                """)) {
            query.setObject(1, tenant);
            try (var rows = query.executeQuery()) {
                assertTrue(rows.next());
                return rows.getString(1);
            }
        }
    }

    @Test
    void enforcesBothQuantitySettingsAndProtectsCommercialUnits() throws Exception {
        String admin=token(KeycloakRealmResource.ADMIN,"commercecore-api");
        UUID account=provision(admin);
        for (boolean companyFractional : new boolean[]{false,true}) {
            var setup=java.util.Map.of("slug","quantity-"+UUID.randomUUID(),"legalName","Empresa",
                    "displayName","Empresa","currencyCode","PEN","timeZone","UTC",
                    "initialAdministratorAccountId",account.toString(),
                    "commercialProfiles",java.util.List.of("CATALOG_MANAGER","PRICE_MANAGER"),
                    "fractionalQuantitiesEnabled",companyFractional);
            UUID tenant=UUID.fromString(given().auth().oauth2(admin).contentType("application/json").body(setup)
                    .post("/api/v1/platform/tenants").then().statusCode(201).extract().path("tenantId"));
            try (var c=dataSource.getConnection(); var statement=c.prepareStatement("""
                    INSERT INTO role_permission (tenant_id,role_id,permission_code)
                    SELECT tenant_id,role_id,'PRODUCT_PRICE_TIER_READ' FROM app_role
                    WHERE tenant_id=? AND code='PRICE_MANAGER'
                    """)) {
                statement.setObject(1,tenant);
                assertEquals(1,statement.executeUpdate());
            }
            String base="/api/v1/tenants/"+tenant;
            String category=given().auth().oauth2(admin).contentType("application/json")
                    .body(java.util.Map.of("code","QUANTITY","name","Cantidad"))
                    .post(base+"/categories").then().statusCode(201).extract().path("categoryId");
            for (boolean productFractional : new boolean[]{false,true}) {
                String product=given().auth().oauth2(admin).contentType("application/json")
                        .body(java.util.Map.of("categoryId",category,"sku","qty-"+productFractional,"name","Cable",
                                "baseUnit","CM","fractionalQuantitiesEnabled",productFractional))
                        .post(base+"/products").then().statusCode(201).body("baseUnit",is("CM"))
                        .extract().path("productId");
                String productPath=base+"/products/"+product;
                given().auth().oauth2(admin).contentType("application/json").body("{\"version\":0,\"baseUnit\":\"M\"}")
                        .patch(productPath).then().statusCode(200).body("baseUnit",is("M"));
                String sale="{\"version\":1,\"retailPrice\":20,\"tiers\":[{\"minimumQuantity\":2.5,\"unitPrice\":15}]}";
                boolean permitted=companyFractional && productFractional;
                given().auth().oauth2(admin).contentType("application/json").body(sale)
                        .put(productPath+"/prices/sale").then().statusCode(permitted?204:400);
                if (!permitted) {
                    given().auth().oauth2(admin).contentType("application/json").body(sale.replace("2.5","3"))
                            .put(productPath+"/prices/sale").then().statusCode(204);
                }
                given().auth().oauth2(admin).queryParam("quantity","2.500").get(productPath+"/prices/quote")
                        .then().statusCode(permitted?200:400);
                given().auth().oauth2(admin).queryParam("quantity","5.000").get(productPath+"/prices/quote")
                        .then().statusCode(200);
                given().auth().oauth2(admin).queryParam("quantity","2,5").get(productPath+"/prices/quote")
                        .then().statusCode(400);
                given().auth().oauth2(admin).queryParam("quantity","2.5001").get(productPath+"/prices/quote")
                        .then().statusCode(400);
                given().auth().oauth2(admin).contentType("application/json").body("{\"version\":2,\"baseUnit\":\"CM\"}")
                        .patch(productPath).then().statusCode(409);
                if (permitted) {
                    var quote = given().auth().oauth2(admin).queryParam("quantity","2.5").get(productPath+"/prices/quote")
                            .then().statusCode(200).extract().response();
                    assertEquals(0,new java.math.BigDecimal(quote.jsonPath().get("unitPrice").toString())
                            .compareTo(new java.math.BigDecimal("15")));
                    given().auth().oauth2(admin).contentType("application/json")
                            .body("{\"version\":2,\"fractionalQuantitiesEnabled\":false}").patch(productPath).then().statusCode(409);
                    assertEquals(1,sqlCount("SELECT count(*) FROM product_price_tier WHERE tenant_id=? AND product_id=? AND minimum_quantity=2.500",
                            tenant,UUID.fromString(product)));
                }
            }
        }
    }

    @Test
    void recordsForeignCostUsingLatestPriorRateWithoutRecalculatingHistory() throws Exception {
        String token=token(KeycloakRealmResource.ADMIN,"commercecore-api");
        UUID actor=provision(token);
        var setup=java.util.Map.of("slug","money-"+UUID.randomUUID(),"legalName","Empresa",
                "displayName","Empresa","currencyCode","EUR","timeZone","UTC",
                "initialAdministratorAccountId",actor.toString(),
                "commercialProfiles",java.util.List.of("CATALOG_MANAGER","PRICE_MANAGER"));
        UUID tenant=UUID.fromString(given().auth().oauth2(token).contentType("application/json").body(setup)
                .post("/api/v1/platform/tenants").then().statusCode(201).extract().path("tenantId"));
        String base="/api/v1/tenants/"+tenant;
        String category=given().auth().oauth2(token).contentType("application/json")
                .body(java.util.Map.of("code","MONEDA","name","Moneda"))
                .post(base+"/categories").then().statusCode(201).extract().path("categoryId");
        UUID product=UUID.fromString(given().auth().oauth2(token).contentType("application/json")
                .body(java.util.Map.of("categoryId",category,"sku","CURRENCY","name","Cable"))
                .post(base+"/products").then().statusCode(201).extract().path("productId"));
        String prices=base+"/products/"+product+"/prices";
        given().auth().oauth2(token).get(prices).then().statusCode(200).body("currency",is("EUR"));
        String rates=base+"/exchange-rates/USD/";
        var rateRequest=java.util.Map.of("rate",new java.math.BigDecimal("0.73000000"));
        given().auth().oauth2(token).contentType("application/json").body(rateRequest)
                .put(rates+"2026-11-16").then().statusCode(404);

        // Future provisioning uses the approved profiles. This fixture isolates each endpoint's permission.
        try (var c=dataSource.getConnection(); var statement=c.prepareStatement("""
                INSERT INTO role_permission (tenant_id,role_id,permission_code)
                SELECT r.tenant_id,r.role_id,p.code FROM app_role r CROSS JOIN
                    (VALUES ('PURCHASE_EXCHANGE_RATE_UPDATE'),('PRODUCT_COST_UPDATE')) AS p(code)
                WHERE r.tenant_id=? AND r.code='PRICE_MANAGER'
                """)) {
            statement.setObject(1,tenant);
            assertEquals(2,statement.executeUpdate());
        }
        var acquisition=java.util.Map.of("version",0,"cost",100,"sourceCurrency","USD","operationDate","2026-11-17");
        given().auth().oauth2(token).contentType("application/json").body(acquisition)
                .put(prices+"/cost").then().statusCode(409);
        given().auth().oauth2(token).contentType("application/json").body(rateRequest)
                .put(rates+"2026-11-16").then().statusCode(204);
        given().auth().oauth2(token).contentType("application/json").body(java.util.Map.of("rate",2))
                .put(rates+"2026-11-18").then().statusCode(204);
        given().auth().oauth2(token).get(rates+"2026-11-16").then().statusCode(404);
        given().auth().oauth2(token).contentType("application/json").body(acquisition)
                .put(prices+"/cost").then().statusCode(204);
        given().auth().oauth2(token).get(prices+"/cost").then().statusCode(404);
        given().auth().oauth2(token).contentType("application/json").body(java.util.Map.of("rate",1))
                .put(rates+"2026-11-16").then().statusCode(204);

        try (var c=dataSource.getConnection(); var statement=c.prepareStatement("""
                INSERT INTO role_permission (tenant_id,role_id,permission_code)
                SELECT r.tenant_id,r.role_id,p.code FROM app_role r CROSS JOIN
                    (VALUES ('PURCHASE_EXCHANGE_RATE_READ'),('PRODUCT_COST_READ')) AS p(code)
                WHERE r.tenant_id=? AND r.code='PRICE_MANAGER'
                """)) {
            statement.setObject(1,tenant);
            assertEquals(2,statement.executeUpdate());
        }
        given().auth().oauth2(token).get(rates+"2026-11-16").then().statusCode(200).body("target",is("EUR"));
        String payload=given().auth().oauth2(token).get(prices+"/cost").then().statusCode(200).extract().asString();
        var result=new ObjectMapper().enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .readTree(payload);
        assertEquals(0,new java.math.BigDecimal("73.00").compareTo(result.path("cost").decimalValue()));
        assertEquals("2026-11-16",result.path("acquisition").path("rateDate").asText());
        assertEquals("USD",result.path("acquisition").path("sourceCurrency").asText());
        assertEquals(actor.toString(),result.path("acquisition").path("recordedBy").asText());
        assertEquals(0,new java.math.BigDecimal("0.73").compareTo(result.path("acquisition").path("rate").decimalValue()));
        assertEquals(1,sqlCount("SELECT count(*) FROM audit_event WHERE tenant_id=? AND entity_id=? AND action='PRODUCT_COST_UPDATED'",tenant,product));
        assertEquals(3,sqlCount("SELECT count(*) FROM audit_event WHERE tenant_id=? AND action='PURCHASE_EXCHANGE_RATE_UPDATED'",tenant));

        var otherSetup=new java.util.HashMap<>(setup);
        otherSetup.put("slug","other-money-"+UUID.randomUUID());
        UUID second=UUID.fromString(given().auth().oauth2(token).contentType("application/json").body(otherSetup)
                .post("/api/v1/platform/tenants").then().statusCode(201).extract().path("tenantId"));
        try (var c=dataSource.getConnection(); var statement=c.prepareStatement("""
                INSERT INTO role_permission (tenant_id,role_id,permission_code)
                SELECT tenant_id,role_id,'PURCHASE_EXCHANGE_RATE_READ' FROM app_role
                WHERE tenant_id=? AND code='PRICE_MANAGER'
                """)) {
            statement.setObject(1,second);
            assertEquals(1,statement.executeUpdate());
        }
        given().auth().oauth2(token).get("/api/v1/tenants/"+second+"/exchange-rates/USD/2026-11-16")
                .then().statusCode(404);

        String suffix=UUID.randomUUID().toString().replace("-","");
        String function="purchase_audit_function_"+suffix;
        String trigger="purchase_audit_trigger_"+suffix;
        try {
            try (var c=dataSource.getConnection(); var statement=c.createStatement()) {
                statement.execute("CREATE FUNCTION "+function+"() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN "
                        +"IF NEW.tenant_id='"+tenant+"' AND NEW.action IN ('PURCHASE_EXCHANGE_RATE_UPDATED','PRODUCT_COST_UPDATED') "
                        +"THEN RAISE EXCEPTION 'audit fixture'; END IF; RETURN NEW; END $$");
                statement.execute("CREATE TRIGGER "+trigger+" BEFORE INSERT ON audit_event FOR EACH ROW EXECUTE FUNCTION "+function+"()");
            }
            given().auth().oauth2(token).contentType("application/json").body(rateRequest)
                    .put(rates+"2026-11-14").then().statusCode(500);
            given().auth().oauth2(token).contentType("application/json")
                    .body(java.util.Map.of("version",1,"cost",200,"sourceCurrency","USD","operationDate","2026-11-17"))
                    .put(prices+"/cost").then().statusCode(500);
            assertEquals(0,sqlCount("SELECT count(*) FROM purchase_exchange_rate WHERE tenant_id=? AND rate_date=DATE '2026-11-14'",tenant));
            assertEquals(1,sqlCount("SELECT count(*) FROM product WHERE tenant_id=? AND product_id=? AND version=1 AND cost_price=73 AND cost_source_amount=100",tenant,product));
        } finally {
            try (var c=dataSource.getConnection(); var statement=c.createStatement()) {
                statement.execute("DROP TRIGGER IF EXISTS "+trigger+" ON audit_event");
                statement.execute("DROP FUNCTION IF EXISTS "+function+"()");
            }
        }
    }

    private static void assertPriceOpenApi(com.fasterxml.jackson.databind.JsonNode document) {
        String root = "/api/v1/tenants/{tenantId}/products/{productId}/prices";
        var bearer = document.path("components").path("securitySchemes").path("bearerAuth");
        assertEquals("http", bearer.path("type").asText());
        assertEquals("bearer", bearer.path("scheme").asText());
        assertEquals("JWT", bearer.path("bearerFormat").asText());
        var paths = document.path("paths");
        assertPriceOperation(document, paths.path(root), "get", "200", false, false);
        assertPriceOperation(document, paths.path(root+"/tiers"), "get", "200", false, false);
        assertPriceOperation(document, paths.path(root+"/quote"), "get", "200", true, true);
        assertPriceOperation(document, paths.path(root+"/cost"), "get", "200", false, false);
        assertPriceOperation(document, paths.path(root+"/sale"), "put", "204", true, false);
        assertPriceOperation(document, paths.path(root+"/cost"), "put", "204", true, false);

        var retail = priceSchema(document, paths.path(root).path("get").path("responses").path("200").path("content").path("application/json").path("schema"));
        var cost = priceSchema(document, paths.path(root+"/cost").path("get").path("responses").path("200").path("content").path("application/json").path("schema"));
        var quote = priceSchema(document, paths.path(root+"/quote").path("get").path("responses").path("200").path("content").path("application/json").path("schema"));
        var tiers = priceSchema(document, paths.path(root+"/tiers").path("get").path("responses").path("200").path("content").path("application/json").path("schema"));
        assertFields(retail, "configured","retailPrice","currency","version");
        assertFields(cost, "cost","version","currency","acquisition");
        assertPriceType(cost.path("properties").path("currency"),"string","",false);
        var acquisitionProperty=cost.path("properties").path("acquisition");
        var acquisition=nullableOpenApiObject(document,acquisitionProperty);
        assertFields(acquisition,"sourceAmount","sourceCurrency","operationDate","rateDate","rate","recordedBy");
        assertMoney(acquisition.path("properties").path("sourceAmount"),false);
        assertPriceType(acquisition.path("properties").path("sourceCurrency"),"string","",false);
        assertPriceType(acquisition.path("properties").path("operationDate"),"string","date",false);
        assertPriceType(acquisition.path("properties").path("rateDate"),"string","date",true);
        assertPriceType(priceSchema(document,acquisition.path("properties").path("recordedBy")),"string","uuid",false);
        var rate=acquisition.path("properties").path("rate");
        assertPriceType(rate,"number","",true);
        assertNumber(rate,"minimum","0.00000001");
        assertNumber(rate,"exclusiveMaximum","10000000000");
        assertNumber(rate,"multipleOf","0.00000001");
        assertFields(quote, "quantity","unitPrice");
        assertPriceType(retail.path("properties").path("configured"), "boolean", "", false);
        assertPriceType(retail.path("properties").path("currency"), "string", "", false);
        assertQuantity(quote.path("properties").path("quantity"),"0.001");
        assertMoney(retail.path("properties").path("retailPrice"), true);
        assertMoney(cost.path("properties").path("cost"), true);
        assertMoney(quote.path("properties").path("unitPrice"), false);
        assertVersion(retail.path("properties").path("version"));
        assertVersion(cost.path("properties").path("version"));
        assertPriceTiers(document, tiers, "0.001");

        var saleRequest = priceSchema(document, paths.path(root+"/sale").path("put").path("requestBody").path("content").path("application/json").path("schema"));
        var costRequest = priceSchema(document, paths.path(root+"/cost").path("put").path("requestBody").path("content").path("application/json").path("schema"));
        assertFields(saleRequest, "version","retailPrice","tiers");
        assertPriceType(costRequest,"object","",false);
        assertEquals(java.util.Set.of("version","cost","sourceCurrency","operationDate"),fields(costRequest.path("properties")));
        var requiredCostFields=new java.util.HashSet<String>();
        costRequest.path("required").forEach(field -> requiredCostFields.add(field.asText()));
        assertEquals(java.util.Set.of("version","cost"),requiredCostFields);
        assertPriceType(costRequest.path("properties").path("sourceCurrency"),"string","",false);
        assertPriceType(costRequest.path("properties").path("operationDate"),"string","date",false);
        assertEquals(com.fasterxml.jackson.databind.node.BooleanNode.FALSE,saleRequest.path("additionalProperties"));
        assertEquals(com.fasterxml.jackson.databind.node.BooleanNode.FALSE,costRequest.path("additionalProperties"));
        assertVersion(saleRequest.path("properties").path("version"));
        assertVersion(costRequest.path("properties").path("version"));
        assertMoney(saleRequest.path("properties").path("retailPrice"), false);
        assertMoney(costRequest.path("properties").path("cost"), true);
        assertPriceTiers(document,saleRequest.path("properties").path("tiers"),"2");
    }

    private static void assertPriceOperation(com.fasterxml.jackson.databind.JsonNode document,
            com.fasterxml.jackson.databind.JsonNode path, String method, String success, boolean conflict, boolean quantity) {
        var operation = path.path(method);
        assertTrue(operation.isObject(), path.toString());
        assertEquals(1, operation.path("security").size());
        assertEquals(java.util.Set.of("bearerAuth"), fields(operation.path("security").get(0)));
        assertTrue(operation.path("security").get(0).path("bearerAuth").isArray());
        assertEquals(0,operation.path("security").get(0).path("bearerAuth").size());
        var params = new java.util.HashMap<String,com.fasterxml.jackson.databind.JsonNode>();
        for (var source : java.util.List.of(path, operation))
            for (var parameter : source.path("parameters")) {
                var resolved = priceSchema(document, parameter);
                params.put(resolved.path("name").asText(), resolved);
            }
        assertEquals(quantity ? java.util.Set.of("tenantId","productId","quantity") : java.util.Set.of("tenantId","productId"),params.keySet());
        for (var item : params.entrySet()) {
            var parameter=item.getValue();
            assertTrue(parameter.path("required").asBoolean());
            if (item.getKey().equals("quantity")) {
                assertEquals("query",parameter.path("in").asText());
                assertQuantity(parameter.path("schema"),"0.001");
            } else {
                assertEquals("path",parameter.path("in").asText());
                assertPriceType(parameter.path("schema"),"string","uuid",false);
            }
        }
        var responses = operation.path("responses");
        var expected = new java.util.HashSet<>(java.util.Set.of(success,"400","401","404","500","503"));
        if (conflict) expected.add("409");
        assertEquals(expected, fields(responses));
        for (String code : expected) {
            var content=responses.path(code).path("content");
            if (code.equals("204")) {
                assertFalse(responses.path(code).has("content"));
            } else if (code.equals("200")) {
                assertEquals(java.util.Set.of("application/json"), fields(content));
            } else {
                assertEquals(java.util.Set.of("application/problem+json"), fields(content));
                var error=priceSchema(document,content.path("application/problem+json").path("schema"));
                assertFields(error,"type","title","status","detail","instance","correlationId");
                assertPriceType(error.path("properties").path("type"),"string","uri-reference",false);
                assertPriceType(error.path("properties").path("title"),"string","",false);
                assertPriceType(error.path("properties").path("status"),"integer","int32",false);
                assertPriceType(error.path("properties").path("detail"),"string","",false);
                assertPriceType(error.path("properties").path("instance"),"string","uri-reference",true);
                assertPriceType(error.path("properties").path("correlationId"),"string","uuid",false);
            }
        }
        if (method.equals("put")) {
            assertTrue(operation.path("requestBody").path("required").asBoolean());
            assertEquals(java.util.Set.of("application/json"),fields(operation.path("requestBody").path("content")));
        } else assertFalse(operation.has("requestBody"));
    }

    private static java.util.Set<String> fields(com.fasterxml.jackson.databind.JsonNode node) {
        var result=new java.util.HashSet<String>();
        node.fieldNames().forEachRemaining(result::add);
        return result;
    }

    private static com.fasterxml.jackson.databind.JsonNode priceSchema(com.fasterxml.jackson.databind.JsonNode document,
            com.fasterxml.jackson.databind.JsonNode node) {
        var seen=new java.util.HashSet<String>();
        while (node.has("$ref")) {
            String ref=node.path("$ref").asText();
            assertTrue(ref.startsWith("#/") && seen.add(ref),ref);
            node=document.at(ref.substring(1));
        }
        assertTrue(node.isObject(),node.toString());
        return node;
    }

    private static void assertFields(com.fasterxml.jackson.databind.JsonNode schema,String... names) {
        assertPriceType(schema,"object","",false);
        assertEquals(java.util.Set.of(names),fields(schema.path("properties")));
        var required=new java.util.HashSet<String>();
        schema.path("required").forEach(field -> required.add(field.asText()));
        assertEquals(java.util.Set.of(names), required);
    }

    private static com.fasterxml.jackson.databind.JsonNode nullableOpenApiObject(
            com.fasterxml.jackson.databind.JsonNode document,com.fasterxml.jackson.databind.JsonNode property) {
        var alternatives=property.path("anyOf");
        if (alternatives.isMissingNode()) alternatives=property.path("oneOf");
        assertEquals(2,alternatives.size(),property.toString());
        boolean allowsNull=false;
        com.fasterxml.jackson.databind.JsonNode object=null;
        for (var option:alternatives) {
            if(option.path("type").asText().equals("null")) allowsNull=true;
            else object=priceSchema(document,option);
        }
        assertTrue(allowsNull,property.toString());
        assertNotNull(object,property.toString());
        return object;
    }

    private static void assertPriceType(com.fasterxml.jackson.databind.JsonNode schema,String type,String format,boolean nullable) {
        var types=new java.util.HashSet<String>();
        if(schema.path("type").isArray())schema.path("type").forEach(value -> types.add(value.asText()));
        else types.add(schema.path("type").asText());
        if(schema.path("nullable").asBoolean())types.add("null");
        assertEquals(nullable?java.util.Set.of(type,"null"):java.util.Set.of(type),types,schema.toString());
        assertEquals(format,schema.path("format").asText(),schema.toString());
    }

    private static void assertNumber(com.fasterxml.jackson.databind.JsonNode schema,String key,String value) {
        assertTrue(schema.path(key).isNumber(),schema.toString());
        assertEquals(0,new java.math.BigDecimal(value).compareTo(schema.path(key).decimalValue()),schema.toString());
    }

    private static void assertMoney(com.fasterxml.jackson.databind.JsonNode schema,boolean nullable) {
        assertPriceType(schema,"number","",nullable);
        assertNumber(schema,"minimum","0");
        assertNumber(schema,"exclusiveMaximum","10000000000000000");
        assertNumber(schema,"multipleOf","0.01");
    }

    private static void assertVersion(com.fasterxml.jackson.databind.JsonNode schema) {
        assertPriceType(schema,"integer","int64",false);
        assertNumber(schema,"minimum","0");
    }

    private static void assertQuantity(com.fasterxml.jackson.databind.JsonNode schema,String minimum) {
        assertPriceType(schema,"number","",false);
        assertNumber(schema,"minimum",minimum);
        assertNumber(schema,"exclusiveMaximum","1000000000000000");
        assertNumber(schema,"multipleOf","0.001");
    }

    private static void assertPriceTiers(com.fasterxml.jackson.databind.JsonNode document,com.fasterxml.jackson.databind.JsonNode schema,String lowerBound) {
        assertPriceType(schema,"array","",false);
        var tier=priceSchema(document,schema.path("items"));
        assertFields(tier,"minimumQuantity","unitPrice");
        assertEquals(com.fasterxml.jackson.databind.node.BooleanNode.FALSE,tier.path("additionalProperties"));
        var minimum=tier.path("properties").path("minimumQuantity");
        assertQuantity(minimum,lowerBound);
        assertMoney(tier.path("properties").path("unitPrice"),false);
    }

    @Test
    void membershipHttpLifecycleAndIsolation() throws Exception {
        String admin=token(KeycloakRealmResource.ADMIN,"commercecore-api");
        String member=token(KeycloakRealmResource.TENANT,"commercecore-api");
        String other=token(KeycloakRealmResource.ONBOARD_TARGET,"commercecore-api");
        UUID adminAccount=provision(admin), memberAccount=provision(member), otherAccount=provision(other);
        String slug="api-members-"+UUID.randomUUID();
        var create=java.util.Map.of("slug",slug,"legalName","Legal","displayName","Empresa","currencyCode","PEN",
                "timeZone","UTC","initialAdministratorAccountId",adminAccount.toString(),"commercialProfiles",java.util.List.of());
        UUID tenant=UUID.fromString(given().auth().oauth2(admin).contentType("application/json").body(create)
                .post("/api/v1/platform/tenants").then().statusCode(201).extract().path("tenantId"));
        String base="/api/v1/tenants/"+tenant+"/memberships";
        given().get(base).then().statusCode(401).header("WWW-Authenticate",org.hamcrest.Matchers.containsString("Bearer"));
        given().auth().oauth2("bad").get(base).then().statusCode(401).contentType("application/problem+json");
        given().auth().oauth2(member).get(base).then().statusCode(404).contentType("application/problem+json");
        given().auth().oauth2(admin).queryParam("limit",0).get(base).then().statusCode(400);
        given().auth().oauth2(admin).queryParam("offset",-1).get(base).then().statusCode(400);
        given().auth().oauth2(admin).queryParam("limit",101).get(base).then().statusCode(400);
        given().auth().oauth2(admin).queryParam("offset","invalid").get(base).then().statusCode(400);
        given().auth().oauth2(admin).get("/api/v1/tenants/not-a-uuid/memberships").then().statusCode(400);
        given().auth().oauth2(admin).queryParam("offset",0).queryParam("limit",1).get(base)
                .then().statusCode(200).body("items.size()",is(1)).body("offset",is(0)).body("limit",is(1));
        var body=java.util.Map.of("accountId",memberAccount.toString());
        String membership=given().auth().oauth2(admin).contentType("application/json").body(body).post(base)
                .then().statusCode(201).body("status",is("INVITED")).extract().path("membershipId");
        given().auth().oauth2(admin).contentType("application/json").body(body).post(base).then().statusCode(409);
        given().auth().oauth2(admin).contentType("application/json").body(java.util.Map.of("accountId",UUID.randomUUID().toString())).post(base).then().statusCode(409);
        given().auth().oauth2(other).post(base+"/"+membership+"/acceptance").then().statusCode(404);
        given().auth().oauth2(member).post(base+"/"+membership+"/acceptance").then().statusCode(200).body("status",is("ACTIVE"));
        given().auth().oauth2(member).post(base+"/"+membership+"/acceptance").then().statusCode(409);
        given().auth().oauth2(admin).post(base+"/"+membership+"/suspension").then().statusCode(200).body("status",is("SUSPENDED"));
        given().auth().oauth2(admin).post(base+"/"+membership+"/reactivation").then().statusCode(200).body("status",is("ACTIVE"));
        assertEquals(0,sqlCount("SELECT count(*) FROM membership_role WHERE tenant_id=? AND membership_id=?",tenant,UUID.fromString(membership)));
        try(var c=dataSource.getConnection(); var q=c.prepareStatement("UPDATE user_account SET status=? WHERE user_id=?")) {
            q.setString(1,"DISABLED"); q.setObject(2,otherAccount); q.executeUpdate();
            given().auth().oauth2(admin).contentType("application/json").body(java.util.Map.of("accountId",otherAccount.toString())).post(base).then().statusCode(409);
            q.setString(1,"ACTIVE"); q.executeUpdate();
        }
        String suffix=UUID.randomUUID().toString().replace("-","");
        String function="membership_fail_"+suffix, trigger="membership_trigger_"+suffix;
        try {
            try(var c=dataSource.getConnection(); var st=c.createStatement()) {
                st.execute("CREATE FUNCTION "+function+"() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.tenant_id='"+tenant+"' THEN RAISE EXCEPTION 'internal audit failure'; END IF; RETURN NEW; END $$");
                st.execute("CREATE TRIGGER "+trigger+" BEFORE INSERT ON audit_event FOR EACH ROW EXECUTE FUNCTION "+function+"()");
            }
            var failure=given().auth().oauth2(admin).contentType("application/json").body(java.util.Map.of("accountId",otherAccount.toString())).post(base);
            failure.then().statusCode(500).contentType("application/problem+json");
            assertFalse(failure.asString().contains("internal audit failure"));
            assertEquals(0,sqlCount("SELECT count(*) FROM tenant_membership WHERE tenant_id=? AND user_id=?",tenant,otherAccount));
        } finally {
            try(var c=dataSource.getConnection(); var st=c.createStatement()) {
                st.execute("DROP TRIGGER IF EXISTS "+trigger+" ON audit_event"); st.execute("DROP FUNCTION IF EXISTS "+function+"()");
            }
        }
        UUID own;
        try(var c=dataSource.getConnection(); var q=c.prepareStatement("SELECT membership_id FROM tenant_membership WHERE tenant_id=? AND user_id=?")) {
            q.setObject(1,tenant); q.setObject(2,adminAccount); try(var rows=q.executeQuery()){assertTrue(rows.next());own=rows.getObject(1,UUID.class);}
        }
        given().auth().oauth2(admin).post(base+"/"+own+"/suspension").then().statusCode(404);
        UUID protectedMember=UUID.randomUUID();
        try(var c=dataSource.getConnection(); var q=c.prepareStatement("INSERT INTO tenant_membership(membership_id,tenant_id,user_id,status) VALUES (?,?,?,'ACTIVE')")) {
            q.setObject(1,protectedMember); q.setObject(2,tenant); q.setObject(3,otherAccount); q.executeUpdate();
        }
        try(var c=dataSource.getConnection(); var q=c.prepareStatement("INSERT INTO membership_role(tenant_id,membership_id,role_id) SELECT tenant_id,?,role_id FROM app_role WHERE tenant_id=? AND code='TENANT_ADMIN'")) {
            q.setObject(1,protectedMember); q.setObject(2,tenant); q.executeUpdate();
        }
        given().auth().oauth2(admin).post(base+"/"+protectedMember+"/suspension").then().statusCode(404);
        given().auth().oauth2(admin).post("/api/v1/tenants/"+UUID.randomUUID()+"/memberships/"+membership+"/suspension").then().statusCode(404);
        var second=new java.util.HashMap<>(create); second.put("slug","api-other-"+UUID.randomUUID());
        second.put("initialAdministratorAccountId",otherAccount.toString());
        String otherTenant=given().auth().oauth2(admin).contentType("application/json").body(second).post("/api/v1/platform/tenants")
                .then().statusCode(201).extract().path("tenantId");
        given().auth().oauth2(admin).get("/api/v1/tenants/"+otherTenant+"/memberships").then().statusCode(404);
        try(var c=dataSource.getConnection(); var q=c.prepareStatement("UPDATE tenant SET status='SUSPENDED' WHERE tenant_id=?")) {q.setObject(1,tenant);q.executeUpdate();}
        given().auth().oauth2(admin).get(base).then().statusCode(404);
    }

    @Test
    void roleHttpAdministrationPreservesPoliciesAndPlatformIsolation() throws Exception {
        String platform=token(KeycloakRealmResource.ADMIN,"commercecore-api");
        String ordinary=token(KeycloakRealmResource.TENANT,"commercecore-api");
        String foreignRole=token(KeycloakRealmResource.OTHER,"commercecore-api");
        UUID adminAccount=provision(platform), user=provision(ordinary);
        String slug="api-roles-"+UUID.randomUUID();
        var onboarding=java.util.Map.of("slug",slug,"legalName","Legal","displayName","Empresa","currencyCode","PEN",
                "timeZone","UTC","initialAdministratorAccountId",adminAccount.toString(),"commercialProfiles",java.util.List.of("CATALOG_OPERATOR"));
        var response=given().auth().oauth2(platform).contentType("application/json").body(onboarding).post("/api/v1/platform/tenants").then().statusCode(201).extract();
        UUID tenant=UUID.fromString(response.path("tenantId")), adminMember=UUID.fromString(response.path("initialMembershipId"));
        UUID member=UUID.randomUUID(), role=UUID.randomUUID();
        try(var c=dataSource.getConnection(); var st=c.createStatement()) {
            st.execute("INSERT INTO tenant_membership(membership_id,tenant_id,user_id,status) VALUES ('"+member+"','"+tenant+"','"+user+"','ACTIVE')");
            st.execute("INSERT INTO app_role(role_id,tenant_id,code,name) VALUES ('"+role+"','"+tenant+"','reader','Reader')");
            st.execute("INSERT INTO role_permission(tenant_id,role_id,permission_code) VALUES ('"+tenant+"','"+role+"','PRODUCT_READ')");
        }
        String base="/api/v1/tenants/"+tenant, roles=base+"/roles", assignment=base+"/memberships/"+member+"/roles/"+role;
        given().get(roles).then().statusCode(401);
        given().auth().oauth2("bad").get(roles).then().statusCode(401).contentType("application/problem+json");
        given().auth().oauth2(ordinary).get(roles).then().statusCode(404);
        given().auth().oauth2(platform).queryParam("offset",0).queryParam("limit",1).get(roles)
                .then().statusCode(200).body("items.size()",is(1)).body("limit",is(1));
        given().auth().oauth2(platform).queryParam("limit",101).get(roles).then().statusCode(400);
        given().auth().oauth2(platform).get(roles+"/"+role+"/permissions").then().statusCode(200).body("size()",is(1));
        given().auth().oauth2(platform).put(assignment).then().statusCode(204);
        given().auth().oauth2(platform).put(assignment).then().statusCode(409).contentType("application/problem+json");
        given().auth().oauth2(platform).get(base+"/memberships/"+member+"/roles").then().statusCode(200).body("items.size()",is(1));
        given().auth().oauth2(platform).delete(assignment).then().statusCode(204);
        given().auth().oauth2(platform).delete(assignment).then().statusCode(409);
        String suffix=UUID.randomUUID().toString().replace("-","");
        String function="role_http_fail_"+suffix, trigger="role_http_trigger_"+suffix;
        try {
            try(var c=dataSource.getConnection(); var st=c.createStatement()) {
                st.execute("CREATE FUNCTION "+function+"() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.tenant_id='"+tenant+"' THEN RAISE EXCEPTION 'internal audit failure'; END IF; RETURN NEW; END $$");
                st.execute("CREATE TRIGGER "+trigger+" BEFORE INSERT ON audit_event FOR EACH ROW EXECUTE FUNCTION "+function+"()");
            }
            var failed=given().auth().oauth2(platform).put(assignment);
            failed.then().statusCode(500).contentType("application/problem+json");
            assertFalse(failed.asString().contains("internal audit failure"));
            assertEquals(0,sqlCount("SELECT count(*) FROM membership_role WHERE tenant_id=? AND membership_id=? AND role_id=?",tenant,member,role));
        } finally {
            try(var c=dataSource.getConnection(); var st=c.createStatement()) {
                st.execute("DROP TRIGGER IF EXISTS "+trigger+" ON audit_event"); st.execute("DROP FUNCTION IF EXISTS "+function+"()");
            }
        }
        given().auth().oauth2(platform).contentType("application/json").body(java.util.List.of()).put(roles+"/"+role+"/permissions").then().statusCode(204);
        given().auth().oauth2(platform).contentType("application/json").body(java.util.List.of("PRODUCT_READ")).put(roles+"/"+role+"/permissions").then().statusCode(204);
        given().auth().oauth2(platform).contentType("application/json").body(java.util.List.of("USER_MANAGE")).put(roles+"/"+role+"/permissions").then().statusCode(404);
        given().auth().oauth2(platform).contentType("application/json").body(java.util.List.of("TENANT_MANAGE")).put(roles+"/"+role+"/permissions").then().statusCode(404);
        given().auth().oauth2(platform).contentType("application/json").body(java.util.List.of("PRODUCT_PRICE_UPDATE")).put(roles+"/"+role+"/permissions").then().statusCode(404);
        UUID ownRole;
        try(var c=dataSource.getConnection(); var q=c.prepareStatement("SELECT role_id FROM app_role WHERE tenant_id=? AND code='CATALOG_OPERATOR'")) {
            q.setObject(1,tenant); try(var rows=q.executeQuery()) { assertTrue(rows.next()); ownRole=rows.getObject(1,UUID.class); }
        }
        given().auth().oauth2(platform).contentType("application/json").body(java.util.List.of()).put(roles+"/"+ownRole+"/permissions").then().statusCode(404);
        given().auth().oauth2(platform).put(base+"/memberships/"+adminMember+"/roles/"+role).then().statusCode(404);
        UUID protectedRole;
        try(var c=dataSource.getConnection(); var q=c.prepareStatement("SELECT role_id FROM app_role WHERE tenant_id=? AND code='TENANT_ADMIN'")) {
            q.setObject(1,tenant); try(var rows=q.executeQuery()) { assertTrue(rows.next()); protectedRole=rows.getObject(1,UUID.class); }
        }
        given().auth().oauth2(platform).contentType("application/json").body(java.util.List.of("PRODUCT_READ"))
                .put(roles+"/"+protectedRole+"/permissions").then().statusCode(404);
        given().auth().oauth2(platform).get("/api/v1/tenants/"+UUID.randomUUID()+"/roles/"+role+"/permissions").then().statusCode(404);
        var another=new java.util.HashMap<>(onboarding); another.put("slug","roles-other-"+UUID.randomUUID());
        another.put("initialAdministratorAccountId",user.toString());
        String otherTenant=given().auth().oauth2(platform).contentType("application/json").body(another).post("/api/v1/platform/tenants")
                .then().statusCode(201).extract().path("tenantId");
        given().auth().oauth2(platform).get("/api/v1/tenants/"+otherTenant+"/roles").then().statusCode(404);
        String platformPath="/api/v1/platform/tenants/"+tenant+"/administrators/";
        given().auth().oauth2(ordinary).put(platformPath+member).then().statusCode(403);
        given().auth().oauth2(foreignRole).put(platformPath+member).then().statusCode(403);
        given().auth().oauth2(platform).put(platformPath+member).then().statusCode(204);
        given().auth().oauth2(ordinary).delete(platformPath+adminMember).then().statusCode(403);
        assertEquals(1,sqlCount("SELECT count(*) FROM tenant_membership m JOIN user_account u ON u.user_id=m.user_id JOIN membership_role mr ON mr.tenant_id=m.tenant_id AND mr.membership_id=m.membership_id JOIN app_role r ON r.tenant_id=mr.tenant_id AND r.role_id=mr.role_id WHERE m.tenant_id=? AND m.membership_id=? AND m.status='ACTIVE' AND u.status='ACTIVE' AND r.code='TENANT_ADMIN'",tenant,adminMember));
        given().auth().oauth2(platform).get(roles).then().statusCode(200);
        try(var c=dataSource.getConnection(); var q=c.prepareStatement("UPDATE tenant SET status=? WHERE tenant_id=?")) {
            q.setString(1,"SUSPENDED"); q.setObject(2,tenant); q.executeUpdate();
            given().auth().oauth2(platform).get(roles).then().statusCode(404);
            assertEquals(1,sqlCount("SELECT count(*) FROM tenant_membership m JOIN user_account u ON u.user_id=m.user_id JOIN membership_role mr ON mr.tenant_id=m.tenant_id AND mr.membership_id=m.membership_id JOIN app_role r ON r.tenant_id=mr.tenant_id AND r.role_id=mr.role_id WHERE m.tenant_id=? AND m.membership_id=? AND m.status='ACTIVE' AND u.status='ACTIVE' AND r.code='TENANT_ADMIN'",tenant,adminMember));
            q.setString(1,"ACTIVE"); q.executeUpdate();
        }
        given().auth().oauth2(platform).get(roles).then().statusCode(200);
        var latch=new java.util.concurrent.CountDownLatch(1); var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Callable<Integer> a=() -> { latch.await(); return given().auth().oauth2(platform).delete(platformPath+adminMember).statusCode(); };
            java.util.concurrent.Callable<Integer> b=() -> { latch.await(); return given().auth().oauth2(platform).delete(platformPath+member).statusCode(); };
            var first=pool.submit(a); var second=pool.submit(b); latch.countDown();
            assertEquals(java.util.Set.of(204,409),java.util.Set.of(first.get(),second.get()));
        } finally { pool.shutdownNow(); }
        assertEquals(1,sqlCount("SELECT count(*) FROM membership_role mr JOIN app_role r ON r.tenant_id=mr.tenant_id AND r.role_id=mr.role_id WHERE mr.tenant_id=? AND r.code='TENANT_ADMIN'",tenant));
    }

    @Test
    void categoriesHttpSecurityHierarchyConcurrencyAndAudit() throws Exception {
        String admin=token(KeycloakRealmResource.ADMIN,"commercecore-api");
        String reader=token(KeycloakRealmResource.OTHER,"commercecore-api");
        UUID adminId=provision(admin), readerId=provision(reader);
        String prefix="categories-"+UUID.randomUUID();
        var createTenant=java.util.Map.of("slug",prefix,"legalName","Empresa","displayName","Empresa","currencyCode","PEN",
                "timeZone","UTC","initialAdministratorAccountId",adminId.toString(),"commercialProfiles",java.util.List.of("CATALOG_MANAGER"));
        String tenant=given().auth().oauth2(admin).contentType("application/json").body(createTenant).post("/api/v1/platform/tenants").then().statusCode(201).extract().path("tenantId");
        var second=new java.util.HashMap<>(createTenant); second.put("slug","categories-other-"+UUID.randomUUID());
        second.put("initialAdministratorAccountId",readerId.toString()); second.put("commercialProfiles",java.util.List.of("CATALOG_VIEWER"));
        String other=given().auth().oauth2(admin).contentType("application/json").body(second).post("/api/v1/platform/tenants").then().statusCode(201).extract().path("tenantId");
        String base="/api/v1/tenants/"+tenant+"/categories", otherBase="/api/v1/tenants/"+other+"/categories";
        given().get(base).then().statusCode(401);
        given().auth().oauth2("invalid").get(base).then().statusCode(401).contentType("application/problem+json");
        given().auth().oauth2(reader).get(base).then().statusCode(404);
        given().auth().oauth2(admin).get(otherBase).then().statusCode(404);
        given().auth().oauth2(reader).get(otherBase).then().statusCode(200);
        given().auth().oauth2(reader).contentType("application/json").body(java.util.Map.of("code","NO","name","No"))
                .post(otherBase).then().statusCode(404);
        String root=given().auth().oauth2(admin).contentType("application/json").body(java.util.Map.of("code","A","name","Raiz"))
                .post(base).then().statusCode(201).extract().path("categoryId");
        given().auth().oauth2(admin).contentType("application/json").body(java.util.Map.of("code","A","name","Duplicado"))
                .post(base).then().statusCode(409);
        String otherRoot=given().auth().oauth2(admin).contentType("application/json").body(java.util.Map.of("code","B","name","Otra"))
                .post(base).then().statusCode(201).extract().path("categoryId");
        String child=given().auth().oauth2(admin).contentType("application/json").body(java.util.Map.of("code","C","name","Hija","parentCategoryId",root))
                .post(base).then().statusCode(201).extract().path("categoryId");
        given().auth().oauth2(admin).queryParam("search","hij").queryParam("limit",1).get(base).then().statusCode(200).body("items.size()",is(1));
        given().auth().oauth2(admin).get(base+"/"+child).then().statusCode(200).body("parentCategoryId",is(root));
        given().auth().oauth2(admin).contentType("application/json").body(java.util.Map.of("name","Hija editada","description","detalle"))
                .patch(base+"/"+child).then().statusCode(200).body("name",is("Hija editada"));
        given().auth().oauth2(admin).contentType("application/json").body(java.util.Map.of("code","NEW"))
                .patch(base+"/"+child).then().statusCode(400);
        given().auth().oauth2(admin).contentType("application/json").body(java.util.Map.of("parentCategoryId",root))
                .patch(base+"/"+root).then().statusCode(409);
        given().auth().oauth2(admin).contentType("application/json").body(java.util.Map.of("parentCategoryId",child))
                .patch(base+"/"+root).then().statusCode(409);
        given().auth().oauth2(admin).post(base+"/"+root+"/deactivation").then().statusCode(409);
        given().auth().oauth2(admin).post(base+"/"+child+"/deactivation").then().statusCode(200).body("status",is("INACTIVE"));
        given().auth().oauth2(admin).contentType("application/json").body(java.util.Map.of("code","D","name","No","parentCategoryId",child))
                .post(base).then().statusCode(409);
        given().auth().oauth2(admin).post(base+"/"+child+"/reactivation").then().statusCode(200).body("status",is("ACTIVE"));
        UUID foreign=UUID.randomUUID();
        try(var c=dataSource.getConnection();var q=c.prepareStatement("INSERT INTO category(category_id,tenant_id,code,name) VALUES (?,?,?,?)")) {
            q.setObject(1,foreign);q.setObject(2,UUID.fromString(other));q.setString(3,"FOREIGN");q.setString(4,"Foreign");q.executeUpdate();
        }
        given().auth().oauth2(admin).contentType("application/json").body(java.util.Map.of("parentCategoryId",foreign.toString()))
                .patch(base+"/"+root).then().statusCode(404);
        var latch=new java.util.concurrent.CountDownLatch(1); var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Callable<Integer> first=() -> { latch.await();return given().auth().oauth2(admin).contentType("application/json")
                    .body(java.util.Map.of("parentCategoryId",otherRoot)).patch(base+"/"+root).statusCode(); };
            java.util.concurrent.Callable<Integer> secondMove=() -> { latch.await();return given().auth().oauth2(admin).contentType("application/json")
                    .body(java.util.Map.of("parentCategoryId",root)).patch(base+"/"+otherRoot).statusCode(); };
            var one=pool.submit(first);var two=pool.submit(secondMove);latch.countDown();
            assertEquals(java.util.Set.of(200,409),java.util.Set.of(one.get(),two.get()));
        } finally {pool.shutdownNow();}
        String suffix=UUID.randomUUID().toString().replace("-","");String function="category_fail_"+suffix,trigger="category_trigger_"+suffix;
        try {
            try(var c=dataSource.getConnection();var st=c.createStatement()) {
                st.execute("CREATE FUNCTION "+function+"() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.tenant_id='"+tenant+"' AND NEW.entity_type='CATEGORY' THEN RAISE EXCEPTION 'audit unavailable'; END IF; RETURN NEW; END $$");
                st.execute("CREATE TRIGGER "+trigger+" BEFORE INSERT ON audit_event FOR EACH ROW EXECUTE FUNCTION "+function+"()");
            }
            var failure=given().auth().oauth2(admin).contentType("application/json").body(java.util.Map.of("name","No persistir"))
                    .patch(base+"/"+child); failure.then().statusCode(500);
            assertFalse(failure.asString().contains("audit unavailable"));
            given().auth().oauth2(admin).get(base+"/"+child).then().statusCode(200).body("name",is("Hija editada"));
        } finally {try(var c=dataSource.getConnection();var st=c.createStatement()) {
            st.execute("DROP TRIGGER IF EXISTS "+trigger+" ON audit_event");st.execute("DROP FUNCTION IF EXISTS "+function+"()");
        }}
        assertTrue(sqlCount("SELECT count(*) FROM audit_event WHERE tenant_id=? AND entity_type='CATEGORY' AND before_data IS NOT NULL AND after_data IS NOT NULL",UUID.fromString(tenant))>0);
        try(var c=dataSource.getConnection();var q=c.prepareStatement("UPDATE tenant SET status='SUSPENDED' WHERE tenant_id=?")) {q.setObject(1,UUID.fromString(tenant));q.executeUpdate();}
        given().auth().oauth2(admin).get(base).then().statusCode(404);
    }

    @Test
    void categoryReactivationRequiresActiveParentAndSerializesWithDeactivation() throws Exception {
        String admin=token(KeycloakRealmResource.ADMIN,"commercecore-api");
        UUID account=provision(admin);
        var body=java.util.Map.of("slug","category-state-"+UUID.randomUUID(),"legalName","Legal","displayName","Empresa",
                "currencyCode","PEN","timeZone","UTC","initialAdministratorAccountId",account.toString(),"commercialProfiles",java.util.List.of("CATALOG_MANAGER"));
        String tenant=given().auth().oauth2(admin).contentType("application/json").body(body)
                .post("/api/v1/platform/tenants").then().statusCode(201).extract().path("tenantId");
        String base="/api/v1/tenants/"+tenant+"/categories";
        String parent=given().auth().oauth2(admin).contentType("application/json").body(java.util.Map.of("code","P","name","Padre"))
                .post(base).then().statusCode(201).extract().path("categoryId");
        String child=given().auth().oauth2(admin).contentType("application/json").body(java.util.Map.of("code","C","name","Hija","parentCategoryId",parent))
                .post(base).then().statusCode(201).extract().path("categoryId");
        String other=given().auth().oauth2(admin).contentType("application/json").body(java.util.Map.of("code","O","name","Otra"))
                .post(base).then().statusCode(201).extract().path("categoryId");
        given().auth().oauth2(admin).post(base+"/"+parent+"/deactivation").then().statusCode(409);
        given().auth().oauth2(admin).post(base+"/"+child+"/deactivation").then().statusCode(200);
        given().auth().oauth2(admin).post(base+"/"+child+"/reactivation").then().statusCode(200).body("status",is("ACTIVE"));
        given().auth().oauth2(admin).post(base+"/"+child+"/deactivation").then().statusCode(200);
        given().auth().oauth2(admin).post(base+"/"+parent+"/deactivation").then().statusCode(200);
        UUID tenantId=UUID.fromString(tenant),childId=UUID.fromString(child),otherId=UUID.fromString(other);
        int auditBefore=sqlCount("SELECT count(*) FROM audit_event WHERE tenant_id=? AND entity_type='CATEGORY'",tenantId);
        var childBefore=categoryUpdatedAt(tenantId,childId); var otherBefore=categoryUpdatedAt(tenantId,otherId);
        given().auth().oauth2(admin).post(base+"/"+child+"/reactivation").then().statusCode(409);
        given().auth().oauth2(admin).contentType("application/json").body(java.util.Map.of("parentCategoryId",parent))
                .patch(base+"/"+other).then().statusCode(409);
        assertEquals(auditBefore,sqlCount("SELECT count(*) FROM audit_event WHERE tenant_id=? AND entity_type='CATEGORY'",tenantId));
        assertEquals(childBefore,categoryUpdatedAt(tenantId,childId)); assertEquals(otherBefore,categoryUpdatedAt(tenantId,otherId));
        assertEquals(1,sqlCount("SELECT count(*) FROM category WHERE tenant_id=? AND category_id=? AND status='INACTIVE'",tenantId,childId));
        assertEquals(1,sqlCount("SELECT count(*) FROM category WHERE tenant_id=? AND category_id=? AND status='ACTIVE' AND parent_category_id IS NULL",tenantId,otherId));
        given().auth().oauth2(admin).post(base+"/"+parent+"/reactivation").then().statusCode(200).body("status",is("ACTIVE"));
        int beforeRace=sqlCount("SELECT count(*) FROM audit_event WHERE tenant_id=? AND entity_type='CATEGORY'",tenantId);
        var latch=new java.util.concurrent.CountDownLatch(1);var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Callable<Integer> deactivate=() -> {latch.await();return given().auth().oauth2(admin).post(base+"/"+parent+"/deactivation").statusCode();};
            java.util.concurrent.Callable<Integer> reactivate=() -> {latch.await();return given().auth().oauth2(admin).post(base+"/"+child+"/reactivation").statusCode();};
            var one=pool.submit(deactivate);var two=pool.submit(reactivate);latch.countDown();
            assertEquals(java.util.Set.of(200,409),java.util.Set.of(one.get(),two.get()));
        } finally { pool.shutdownNow(); }
        assertEquals(beforeRace+1,sqlCount("SELECT count(*) FROM audit_event WHERE tenant_id=? AND entity_type='CATEGORY'",tenantId));
        assertEquals(0,sqlCount("SELECT count(*) FROM category c JOIN category p ON p.tenant_id=c.tenant_id AND p.category_id=c.parent_category_id WHERE c.tenant_id=? AND c.category_id=? AND c.status='ACTIVE' AND p.status='INACTIVE'",tenantId,childId));
    }

    private java.time.OffsetDateTime categoryUpdatedAt(UUID tenant,UUID category) throws Exception {
        try(var c=dataSource.getConnection(); var q=c.prepareStatement("SELECT updated_at FROM category WHERE tenant_id=? AND category_id=?")) {
            q.setObject(1,tenant);q.setObject(2,category);
            try(var rows=q.executeQuery()) { assertTrue(rows.next());return rows.getObject(1,java.time.OffsetDateTime.class); }
        }
    }

    @Test
    void simpleProductsHttpOptimisticLockAndTenantIsolation() throws Exception {
        String admin=token(KeycloakRealmResource.ADMIN,"commercecore-api");
        String outsider=token(KeycloakRealmResource.OTHER,"commercecore-api");
        UUID account=provision(admin), outsiderAccount=provision(outsider);
        var setup=java.util.Map.of("slug","products-"+UUID.randomUUID(),"legalName","Legal","displayName","Empresa",
                "currencyCode","PEN","timeZone","UTC","initialAdministratorAccountId",account.toString(),"commercialProfiles",java.util.List.of("CATALOG_MANAGER","CATALOG_VIEWER"));
        String tenant=given().auth().oauth2(admin).contentType("application/json").body(setup).post("/api/v1/platform/tenants").then().statusCode(201).extract().path("tenantId");
        var setup2=new java.util.HashMap<>(setup);setup2.put("slug","products-other-"+UUID.randomUUID());
        String tenant2=given().auth().oauth2(admin).contentType("application/json").body(setup2).post("/api/v1/platform/tenants").then().statusCode(201).extract().path("tenantId");
        String categories="/api/v1/tenants/"+tenant+"/categories",categories2="/api/v1/tenants/"+tenant2+"/categories";
        String category=given().auth().oauth2(admin).contentType("application/json").body(java.util.Map.of("code","A","name","Activa")).post(categories).then().statusCode(201).extract().path("categoryId");
        String inactive=given().auth().oauth2(admin).contentType("application/json").body(java.util.Map.of("code","I","name","Inactiva")).post(categories).then().statusCode(201).extract().path("categoryId");
        String foreign=given().auth().oauth2(admin).contentType("application/json").body(java.util.Map.of("code","F","name","Ajena")).post(categories2).then().statusCode(201).extract().path("categoryId");
        given().auth().oauth2(admin).post(categories+"/"+inactive+"/deactivation").then().statusCode(200);
        String base="/api/v1/tenants/"+tenant+"/products",base2="/api/v1/tenants/"+tenant2+"/products";
        given().get(base).then().statusCode(401);given().auth().oauth2("invalid").get(base).then().statusCode(401);
        given().auth().oauth2(outsider).get(base).then().statusCode(404);
        var product=java.util.Map.of("categoryId",category,"sku","sku-abc","name","Filtro","description","Inicial");
        var created=given().auth().oauth2(admin).contentType("application/json").body(product).post(base);
        created.then().statusCode(201).body("status",is("DRAFT")).body("version",is(0));
        assertFalse(created.asString().contains("salePrice"));String id=created.jsonPath().getString("productId");
        given().auth().oauth2(admin).contentType("application/json").body(java.util.Map.of("categoryId",category,"sku","  SKU-ABC  ","name","Otro")).post(base).then().statusCode(409);
        given().auth().oauth2(admin).contentType("application/json").body(java.util.Map.of("categoryId",foreign,"sku","SKU-ABC","name","Otro tenant")).post(base2).then().statusCode(201);
        for(String bad:java.util.List.of(foreign,UUID.randomUUID().toString()))
            given().auth().oauth2(admin).contentType("application/json").body(java.util.Map.of("categoryId",bad,"sku",UUID.randomUUID().toString(),"name","No" )).post(base).then().statusCode(404);
        given().auth().oauth2(admin).contentType("application/json").body(java.util.Map.of("categoryId",inactive,"sku","NO-ACTIVO","name","No")).post(base).then().statusCode(409);
        given().auth().oauth2(admin).contentType("application/json").body(java.util.Map.of("categoryId",category,"sku","PRICE","name","No","salePrice",1)).post(base).then().statusCode(400);
        given().auth().oauth2(admin).queryParam("sku"," SKU-AB ").queryParam("name","filt").queryParam("categoryId",category).queryParam("status","DRAFT").queryParam("limit",1)
                .get(base).then().statusCode(200).body("items.size()",is(1));
        given().auth().oauth2(admin).get(base+"/"+id).then().statusCode(200).body("sku",is("sku-abc"));
        String member=given().auth().oauth2(admin).contentType("application/json").body(java.util.Map.of("accountId",outsiderAccount.toString()))
                .post("/api/v1/tenants/"+tenant+"/memberships").then().statusCode(201).extract().path("membershipId");
        given().auth().oauth2(outsider).post("/api/v1/tenants/"+tenant+"/memberships/"+member+"/acceptance").then().statusCode(200);
        UUID viewer;
        try(var c=dataSource.getConnection();var q=c.prepareStatement("SELECT role_id FROM app_role WHERE tenant_id=? AND code='CATALOG_VIEWER'")){
            q.setObject(1,UUID.fromString(tenant));try(var rows=q.executeQuery()){assertTrue(rows.next());viewer=rows.getObject(1,UUID.class);}
        }
        given().auth().oauth2(admin).put("/api/v1/tenants/"+tenant+"/memberships/"+member+"/roles/"+viewer).then().statusCode(204);
        given().auth().oauth2(outsider).get(base+"/"+id).then().statusCode(200);
        given().auth().oauth2(outsider).contentType("application/json").body(java.util.Map.of("version",0,"name","No"))
                .patch(base+"/"+id).then().statusCode(404);
        given().auth().oauth2(outsider).contentType("application/json").body(java.util.Map.of("categoryId",category,"sku","NO-PERM","name","No"))
                .post(base).then().statusCode(404);
        given().auth().oauth2(admin).get(base2+"/"+id).then().statusCode(404);
        given().auth().oauth2(admin).contentType("application/json").body(java.util.Map.of("version",0,"sku","NEW")).patch(base+"/"+id).then().statusCode(400);
        var start=new java.util.concurrent.CountDownLatch(1);var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Callable<Integer> a=() -> {start.await();return given().auth().oauth2(admin).contentType("application/json").body(java.util.Map.of("version",0,"name","Primero")).patch(base+"/"+id).statusCode();};
            java.util.concurrent.Callable<Integer> b=() -> {start.await();return given().auth().oauth2(admin).contentType("application/json").body(java.util.Map.of("version",0,"name","Segundo")).patch(base+"/"+id).statusCode();};
            var one=pool.submit(a);var two=pool.submit(b);start.countDown();assertEquals(java.util.Set.of(200,409),java.util.Set.of(one.get(),two.get()));
        } finally {pool.shutdownNow();}
        given().auth().oauth2(admin).contentType("application/json").body(java.util.Map.of("version",0,"name","Obsoleto")).patch(base+"/"+id).then().statusCode(409);
        given().auth().oauth2(admin).contentType("application/json").body(java.util.Map.of("version",1,"categoryId",inactive)).patch(base+"/"+id).then().statusCode(409);
        UUID tenantId=UUID.fromString(tenant),productId=UUID.fromString(id);
        assertEquals(1,sqlCount("SELECT count(*) FROM audit_event WHERE tenant_id=? AND entity_id=? AND action='PRODUCT_UPDATED'",tenantId,productId));
        String publicationProduct=given().auth().oauth2(admin).contentType("application/json")
                .body(java.util.Map.of("categoryId",category,"sku","PUBLICATION-EDIT","name","Antes"))
                .post(base).then().statusCode(201).extract().path("productId");
        UUID publicationId=UUID.fromString(publicationProduct);
        try(var connection=dataSource.getConnection();var update=connection.prepareStatement(
                "UPDATE product SET status='ACTIVE',published=true,ever_published=true WHERE tenant_id=? AND product_id=?")) {
            update.setObject(1,tenantId);update.setObject(2,publicationId);assertEquals(1,update.executeUpdate());
        }
        given().auth().oauth2(admin).contentType("application/json").body(java.util.Map.of("version",0,"name","Publicado editado"))
                .patch(base+"/"+publicationProduct).then().statusCode(200).body("name",is("Publicado editado")).body("version",is(1));
        assertEquals(1,sqlCount("SELECT count(*) FROM product WHERE tenant_id=? AND product_id=? AND name='Publicado editado' AND version=1 AND status='ACTIVE' AND published AND ever_published",tenantId,publicationId));
        try(var connection=dataSource.getConnection();var update=connection.prepareStatement(
                "UPDATE product SET published=false WHERE tenant_id=? AND product_id=?")) {
            update.setObject(1,tenantId);update.setObject(2,publicationId);assertEquals(1,update.executeUpdate());
        }
        given().auth().oauth2(admin).contentType("application/json").body(java.util.Map.of("version",1,"name","Oculto editado"))
                .patch(base+"/"+publicationProduct).then().statusCode(200).body("name",is("Oculto editado")).body("version",is(2));
        assertEquals(1,sqlCount("SELECT count(*) FROM product WHERE tenant_id=? AND product_id=? AND name='Oculto editado' AND version=2 AND status='ACTIVE' AND NOT published AND ever_published",tenantId,publicationId));
        String suffix=UUID.randomUUID().toString().replace("-","");String function="product_fail_"+suffix,trigger="product_trigger_"+suffix;
        try {
            try(var c=dataSource.getConnection();var st=c.createStatement()){
                st.execute("CREATE FUNCTION "+function+"() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.tenant_id='"+tenant+"' AND NEW.entity_type='PRODUCT' THEN RAISE EXCEPTION 'audit failure'; END IF; RETURN NEW; END $$");
                st.execute("CREATE TRIGGER "+trigger+" BEFORE INSERT ON audit_event FOR EACH ROW EXECUTE FUNCTION "+function+"()");
            }
            given().auth().oauth2(admin).contentType("application/json").body(java.util.Map.of("version",1,"name","No persistir")).patch(base+"/"+id).then().statusCode(500);
            assertEquals(1,sqlCount("SELECT count(*) FROM product WHERE tenant_id=? AND product_id=? AND version=1 AND name<>'No persistir'",tenantId,productId));
        } finally {try(var c=dataSource.getConnection();var st=c.createStatement()){
            st.execute("DROP TRIGGER IF EXISTS "+trigger+" ON audit_event");st.execute("DROP FUNCTION IF EXISTS "+function+"()");
        }}
        String raceCategory=given().auth().oauth2(admin).contentType("application/json").body(java.util.Map.of("code","RACE","name","Carrera"))
                .post(categories).then().statusCode(201).extract().path("categoryId");
        var gate=new java.util.concurrent.CountDownLatch(1);var concurrent=java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Callable<Integer> make=() -> {gate.await();return given().auth().oauth2(admin).contentType("application/json")
                    .body(java.util.Map.of("categoryId",raceCategory,"sku","RACE-SKU","name","Carrera")).post(base).statusCode();};
            java.util.concurrent.Callable<Integer> disable=() -> {gate.await();return given().auth().oauth2(admin).post(categories+"/"+raceCategory+"/deactivation").statusCode();};
            var productResult=concurrent.submit(make);var categoryResult=concurrent.submit(disable);gate.countDown();
            int result=productResult.get();assertTrue(result==201 || result==409);assertEquals(200,categoryResult.get());
            assertEquals(result==201?1:0,sqlCount("SELECT count(*) FROM product WHERE tenant_id=? AND sku_normalized='RACE-SKU'",tenantId));
        } finally {concurrent.shutdownNow();}
        try(var c=dataSource.getConnection();var q=c.prepareStatement("UPDATE product SET status='ARCHIVED' WHERE tenant_id=? AND product_id=?")){q.setObject(1,tenantId);q.setObject(2,productId);q.executeUpdate();}
        given().auth().oauth2(admin).contentType("application/json").body(java.util.Map.of("version",1,"name","Archivado")).patch(base+"/"+id).then().statusCode(409);
        try(var c=dataSource.getConnection();var q=c.prepareStatement("UPDATE tenant SET status='SUSPENDED' WHERE tenant_id=?")){q.setObject(1,tenantId);q.executeUpdate();}
        given().auth().oauth2(admin).get(base).then().statusCode(404);
    }

    @Test
    void variantsHaveIndependentPricesAndScopedProductManagement() throws Exception {
        String admin=token(KeycloakRealmResource.ADMIN,"commercecore-api");
        UUID account=provision(admin);
        var setup=new java.util.HashMap<String,Object>(java.util.Map.of(
                "slug","variants-"+UUID.randomUUID(),"legalName","Legal","displayName","Catalog",
                "timeZone","UTC","initialAdministratorAccountId",account.toString(),
                "commercialProfiles",java.util.List.of("CATALOG_MANAGER","PRICE_MANAGER")));
        String tenant=given().auth().oauth2(admin).contentType("application/json").body(setup)
                .post("/api/v1/platform/tenants").then().statusCode(201).extract().path("tenantId");
        setup.put("slug","variants-other-"+UUID.randomUUID());
        String otherTenant=given().auth().oauth2(admin).contentType("application/json").body(setup)
                .post("/api/v1/platform/tenants").then().statusCode(201).extract().path("tenantId");
        UUID tenantId=UUID.fromString(tenant);
        try(var c=dataSource.getConnection();var statement=c.prepareStatement("""
                INSERT INTO role_permission(tenant_id,role_id,permission_code)
                SELECT tenant_id,role_id,p.code FROM app_role
                CROSS JOIN (VALUES ('PRODUCT_PRICE_TIER_READ'),('PRODUCT_COST_READ'),('PRODUCT_COST_UPDATE')) p(code)
                WHERE tenant_id=? AND app_role.code='PRICE_MANAGER'
                """)) {
            statement.setObject(1,tenantId);
            assertEquals(3,statement.executeUpdate());
        }
        String base="/api/v1/tenants/"+tenant, otherBase="/api/v1/tenants/"+otherTenant;
        String category=given().auth().oauth2(admin).contentType("application/json").body(java.util.Map.of("code","H","name","Helmets"))
                .post(base+"/categories").then().statusCode(201).extract().path("categoryId");
        String otherCategory=given().auth().oauth2(admin).contentType("application/json").body(java.util.Map.of("code","H","name","Helmets"))
                .post(otherBase+"/categories").then().statusCode(201).extract().path("categoryId");
        var groupRequest=java.util.Map.of("type","VARIABLE","categoryId",category,"name","Casco");
        String group=given().auth().oauth2(admin).contentType("application/json").body(groupRequest)
                .post(base+"/products").then().statusCode(201).body("sku",is(org.hamcrest.Matchers.nullValue()))
                .body("type",is("VARIABLE")).extract().path("productId");
        var variantRequest=new java.util.HashMap<String,Object>(java.util.Map.of(
                "type","VARIANT","parentId",group,"categoryId",category,"sku","CASCO-S","name","Casco S",
                "attributes",java.util.Map.of("color","negro","size","S")));
        String first=given().auth().oauth2(admin).contentType("application/json").body(variantRequest)
                .post(base+"/products").then().statusCode(201).body("parentId",is(group)).body("attributes.size",is("S"))
                .extract().path("productId");
        variantRequest.put("sku","CASCO-M");
        given().auth().oauth2(admin).contentType("application/json").body(variantRequest)
                .post(base+"/products").then().statusCode(409);
        variantRequest.put("name","Casco M");
        variantRequest.put("attributes",java.util.Map.of("color","negro","size","M"));
        String second=given().auth().oauth2(admin).contentType("application/json").body(variantRequest)
                .post(base+"/products").then().statusCode(201).extract().path("productId");
        given().auth().oauth2(admin).queryParam("type","VARIANT").queryParam("parentId",group).queryParam("sku","casco-m")
                .get(base+"/products").then().statusCode(200).body("items.productId",is(java.util.List.of(second)));
        given().auth().oauth2(admin).get(otherBase+"/products/"+first).then().statusCode(404);
        var foreignRequest=new java.util.HashMap<>(variantRequest);
        foreignRequest.put("categoryId",otherCategory);
        given().auth().oauth2(admin).contentType("application/json").body(foreignRequest)
                .post(otherBase+"/products").then().statusCode(404);
        variantRequest.put("sku","CASCO-NESTED");
        variantRequest.put("parentId",first);
        given().auth().oauth2(admin).contentType("application/json").body(variantRequest)
                .post(base+"/products").then().statusCode(409);

        String firstPath=base+"/products/"+first,secondPath=base+"/products/"+second;
        given().auth().oauth2(admin).contentType("application/json")
                .body(java.util.Map.of("version",0,"attributes",java.util.Map.of("color","negro","size","M")))
                .patch(firstPath).then().statusCode(409);
        given().auth().oauth2(admin).contentType("application/json")
                .body(java.util.Map.of("version",0,"attributes",java.util.Map.of("color","blanco","size","S")))
                .patch(firstPath).then().statusCode(200).body("version",is(1)).body("attributes.color",is("blanco"));
        given().auth().oauth2(admin).contentType("application/json")
                .body(java.util.Map.of("version",0,"attributes",java.util.Map.of("color","rojo","size","S")))
                .patch(firstPath).then().statusCode(409);
        for (var path:java.util.List.of(firstPath,secondPath)) {
            int price=path.equals(firstPath)?12:20;
            int version=path.equals(firstPath)?1:0;
            given().auth().oauth2(admin).contentType("application/json")
                    .body(java.util.Map.of("version",version,"retailPrice",price,"tiers",
                            java.util.List.of(java.util.Map.of("minimumQuantity",6,"unitPrice",price-2))))
                    .put(path+"/prices/sale").then().statusCode(204);
            given().auth().oauth2(admin).contentType("application/json").body(java.util.Map.of("version",version+1,"cost",price-5))
                    .put(path+"/prices/cost").then().statusCode(204);
            given().auth().oauth2(admin).get(path+"/prices/quote?quantity=6").then().statusCode(200)
                    .body("unitPrice",is((float)price-2)).body("quantity",is(6));
            given().auth().oauth2(admin).get(path+"/prices/cost").then().statusCode(200).body("cost",is((float)price-5));
        }
        given().auth().oauth2(admin).get(base+"/products/"+group+"/prices").then().statusCode(404);
        given().auth().oauth2(admin).contentType("application/json")
                .body(java.util.Map.of("version",0,"retailPrice",1,"tiers",java.util.List.of()))
                .put(base+"/products/"+group+"/prices/sale").then().statusCode(404);
        assertEquals(1,sqlCount("""
                SELECT count(*) FROM audit_event WHERE tenant_id=? AND entity_id=? AND action='PRODUCT_UPDATED'
                    AND before_data->'attributes'->>'color'='negro' AND after_data->'attributes'->>'color'='blanco'
                """,tenantId,UUID.fromString(first)));
    }

    @Test
    void productPricesUseTenantAuthorizationAndVersion() throws Exception {
        String admin=token(KeycloakRealmResource.ADMIN,"commercecore-api");
        UUID account=provision(admin);
        var setup=java.util.Map.of("slug","prices-"+UUID.randomUUID(),"legalName","Legal","displayName","Empresa",
                "currencyCode","PEN","timeZone","UTC","initialAdministratorAccountId",account.toString(),
                "commercialProfiles",java.util.List.of("CATALOG_MANAGER","PRICE_MANAGER"));
        String tenant=given().auth().oauth2(admin).contentType("application/json").body(setup).post("/api/v1/platform/tenants").then().statusCode(201).extract().path("tenantId");
        try(var c=dataSource.getConnection();var s=c.prepareStatement("INSERT INTO role_permission(tenant_id,role_id,permission_code) SELECT tenant_id,role_id,p.code FROM app_role CROSS JOIN (VALUES ('PRODUCT_PRICE_TIER_READ'),('PRODUCT_COST_READ'),('PRODUCT_COST_UPDATE')) AS p(code) WHERE tenant_id=? AND app_role.code='PRICE_MANAGER'")){
            s.setObject(1,UUID.fromString(tenant));s.executeUpdate();
        }
        String base="/api/v1/tenants/"+tenant;
        String category=given().auth().oauth2(admin).contentType("application/json").body(java.util.Map.of("code","P","name","Precios")).post(base+"/categories").then().statusCode(201).extract().path("categoryId");
        String product=given().auth().oauth2(admin).contentType("application/json").body(java.util.Map.of("categoryId",category,"sku","PRICES","name","Producto")).post(base+"/products").then().statusCode(201).extract().path("productId");
        String path=base+"/products/"+product+"/prices";
        String other=token(KeycloakRealmResource.OTHER,"commercecore-api");UUID otherAccount=provision(other);
        var otherSetup=new java.util.HashMap<>(setup);otherSetup.put("slug","other-prices-"+UUID.randomUUID());otherSetup.put("initialAdministratorAccountId",otherAccount.toString());
        String otherTenant=given().auth().oauth2(admin).contentType("application/json").body(otherSetup).post("/api/v1/platform/tenants").then().statusCode(201).extract().path("tenantId");
        try(var c=dataSource.getConnection();var s=c.prepareStatement("INSERT INTO role_permission(tenant_id,role_id,permission_code) SELECT tenant_id,role_id,p.code FROM app_role CROSS JOIN (VALUES ('PRODUCT_PRICE_TIER_READ'),('PRODUCT_COST_READ')) p(code) WHERE tenant_id=? AND app_role.code='PRICE_MANAGER'")){s.setObject(1,UUID.fromString(otherTenant));s.executeUpdate();}
        String foreignPath="/api/v1/tenants/"+otherTenant+"/products/"+product+"/prices";
        for(String suffix:java.util.List.of("/cost","/tiers","/quote?quantity=6")){
            var denied=given().auth().oauth2(other).get(path+suffix).then().statusCode(404).extract().response();
            assertFalse(denied.asString().contains("3.25"));given().auth().oauth2(other).get(foreignPath+suffix).then().statusCode(404);
        }
        given().auth().oauth2(other).contentType("application/json").body("{\"version\":0,\"retailPrice\":4,\"tiers\":[]}").put(path+"/sale").then().statusCode(404);
        given().auth().oauth2(other).contentType("application/json").body("{\"version\":0,\"cost\":4}").put(foreignPath+"/cost").then().statusCode(404);
        given().auth().oauth2(admin).get(path).then().statusCode(200).body("configured",is(false));
        given().auth().oauth2(admin).queryParam("quantity",1).get(path+"/quote").then().statusCode(409);
        given().auth().oauth2(admin).contentType("application/json").body("{\"version\":0,\"retailPrice\":12.00,\"tiers\":[{\"minimumQuantity\":6,\"unitPrice\":10.00}]}").put(path+"/sale").then().statusCode(204);
        given().auth().oauth2(admin).queryParam("quantity",6).get(path+"/quote").then().statusCode(200).body("unitPrice",is(10.0f));
        given().auth().oauth2(admin).contentType("application/json").body("{\"version\":0,\"retailPrice\":9,\"tiers\":[]}").put(path+"/sale").then().statusCode(409);
        given().auth().oauth2(admin).contentType("application/json").body("{\"version\":1,\"cost\":1.001}").put(path+"/cost").then().statusCode(400);
        given().auth().oauth2(admin).contentType("application/json").body("{\"version\":1,\"cost\":3.25}").put(path+"/cost").then().statusCode(204);
        given().auth().oauth2(admin).get(path+"/cost").then().statusCode(200).body("cost",is(3.25f));
        var hiddenCost=given().auth().oauth2(other).get(path+"/cost").then().statusCode(404).contentType("application/problem+json").extract().asString();
        assertFalse(hiddenCost.contains("3.25"));assertFalse(hiddenCost.contains("SQLException"));assertFalse(hiddenCost.contains("SELECT"));
        given().auth().oauth2(admin).contentType("application/json").body("{\"version\":2,\"retailPrice\":12.00,\"tiers\":[{\"minimumQuantity\":101,\"unitPrice\":6},{\"minimumQuantity\":6,\"unitPrice\":10},{\"minimumQuantity\":50,\"unitPrice\":7},{\"minimumQuantity\":20,\"unitPrice\":8}]}").put(path+"/sale").then().statusCode(204);
        int[] quantities={1,5,6,19,20,49,50,100,101}; int[] values={12,12,10,10,8,8,7,7,6};
        for(int i=0;i<quantities.length;i++){
            var response=given().auth().oauth2(admin).queryParam("quantity",quantities[i]).get(path+"/quote").then().statusCode(200).extract().response();
            var unit=new java.math.BigDecimal(response.jsonPath().get("unitPrice").toString());
            assertEquals(0,unit.compareTo(java.math.BigDecimal.valueOf(values[i])));
            assertEquals(0,unit.multiply(java.math.BigDecimal.valueOf(quantities[i])).compareTo(java.math.BigDecimal.valueOf((long)values[i]*quantities[i])));
        }
        for(int quantity:new int[]{0,-1})given().auth().oauth2(admin).queryParam("quantity",quantity).get(path+"/quote").then().statusCode(400);
        for(String bad:java.util.List.of("{\"version\":3,\"retailPrice\":-1,\"tiers\":[]}","{\"version\":3,\"retailPrice\":1.001,\"tiers\":[]}","{\"version\":3,\"retailPrice\":12,\"tiers\":[{\"minimumQuantity\":6,\"unitPrice\":10},{\"minimumQuantity\":6,\"unitPrice\":9}]}"))
            given().auth().oauth2(admin).contentType("application/json").body(bad).put(path+"/sale").then().statusCode(400);
        var gate=new java.util.concurrent.CountDownLatch(1);var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Callable<Integer> first=()->{gate.await();return given().auth().oauth2(admin).contentType("application/json").body("{\"version\":3,\"retailPrice\":0,\"tiers\":[]}").put(path+"/sale").statusCode();};
            java.util.concurrent.Callable<Integer> second=()->{gate.await();return given().auth().oauth2(admin).contentType("application/json").body("{\"version\":3,\"retailPrice\":0,\"tiers\":[]}").put(path+"/sale").statusCode();};
            var a=pool.submit(first);var b=pool.submit(second);gate.countDown();assertEquals(java.util.Set.of(204,409),java.util.Set.of(a.get(),b.get()));
        } finally {pool.shutdownNow();}
        given().auth().oauth2(admin).get(path).then().statusCode(200).body("configured",is(true)).body("version",is(4));
        given().auth().oauth2(admin).get(path+"/tiers").then().statusCode(200).body("size()",is(0));
        var zero=given().auth().oauth2(admin).queryParam("quantity",101).get(path+"/quote").then().statusCode(200).extract().path("unitPrice");
        assertEquals(0,new java.math.BigDecimal(zero.toString()).signum());
        given().auth().oauth2(admin).contentType("application/json").body("{\"version\":4,\"cost\":null}").put(path+"/cost").then().statusCode(204);
        given().auth().oauth2(admin).get(path+"/cost").then().statusCode(200).body("cost",org.hamcrest.Matchers.nullValue());
        var latch=new java.util.concurrent.CountDownLatch(1);var runners=java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var writer=runners.submit(()->{latch.await();return given().auth().oauth2(admin).contentType("application/json").body("{\"version\":5,\"retailPrice\":8,\"tiers\":[{\"minimumQuantity\":6,\"unitPrice\":7}]}").put(path+"/sale").statusCode();});
            var reader=runners.submit(()->{latch.await();return given().auth().oauth2(admin).queryParam("quantity",6).get(path+"/quote").then().statusCode(200).extract().path("unitPrice");});
            latch.countDown();assertEquals(204,writer.get());var observed=new java.math.BigDecimal(reader.get().toString());
            assertTrue(observed.compareTo(java.math.BigDecimal.ZERO)==0 || observed.compareTo(java.math.BigDecimal.valueOf(7))==0);
        } finally {runners.shutdownNow();}
        String suffix=UUID.randomUUID().toString().replace("-","");String function="price_audit_fail_"+suffix,trigger="price_audit_trigger_"+suffix;
        try {
            try(var c=dataSource.getConnection();var s=c.createStatement()){
                s.execute("CREATE FUNCTION "+function+"() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.tenant_id='"+tenant+"' AND NEW.action IN ('PRODUCT_SALE_PRICES_UPDATED','PRODUCT_COST_UPDATED') THEN RAISE EXCEPTION 'audit test failure'; END IF; RETURN NEW; END $$");
                s.execute("CREATE TRIGGER "+trigger+" BEFORE INSERT ON audit_event FOR EACH ROW EXECUTE FUNCTION "+function+"()");
            }
            given().auth().oauth2(admin).contentType("application/json").body("{\"version\":6,\"retailPrice\":4,\"tiers\":[]}").put(path+"/sale").then().statusCode(500);
            given().auth().oauth2(admin).contentType("application/json").body("{\"version\":6,\"cost\":99}").put(path+"/cost").then().statusCode(500);
            given().auth().oauth2(admin).get(path).then().statusCode(200).body("version",is(6)).body("retailPrice",is(8.0f));
            given().auth().oauth2(admin).get(path+"/tiers").then().statusCode(200).body("size()",is(1));
            given().auth().oauth2(admin).get(path+"/cost").then().statusCode(200).body("cost",org.hamcrest.Matchers.nullValue());
            given().auth().oauth2(admin).queryParam("quantity",6).get(path+"/quote").then().statusCode(200).body("unitPrice",is(7.0f));
        } finally {try(var c=dataSource.getConnection();var s=c.createStatement()){s.execute("DROP TRIGGER IF EXISTS "+trigger+" ON audit_event");s.execute("DROP FUNCTION IF EXISTS "+function+"()");}}
        int auditBefore=sqlCount("SELECT count(*) FROM audit_event WHERE tenant_id=? AND entity_id=? AND action='PRODUCT_SALE_PRICES_UPDATED'",UUID.fromString(tenant),UUID.fromString(product));
        var startDistinct=new java.util.concurrent.CountDownLatch(1);var writers=java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var a=writers.submit(()->{startDistinct.await();return given().auth().oauth2(admin).contentType("application/json").body("{\"version\":6,\"retailPrice\":30,\"tiers\":[{\"minimumQuantity\":4,\"unitPrice\":20}]}").put(path+"/sale").statusCode();});
            var b=writers.submit(()->{startDistinct.await();return given().auth().oauth2(admin).contentType("application/json").body("{\"version\":6,\"retailPrice\":40,\"tiers\":[{\"minimumQuantity\":9,\"unitPrice\":15},{\"minimumQuantity\":12,\"unitPrice\":10}]}").put(path+"/sale").statusCode();});
            startDistinct.countDown();int first=a.get(),second=b.get();assertEquals(java.util.Set.of(204,409),java.util.Set.of(first,second));
            var sale=given().auth().oauth2(admin).get(path).then().statusCode(200).body("version",is(7)).extract().response();
            var tiers=given().auth().oauth2(admin).get(path+"/tiers").then().statusCode(200).extract().response();
            if(first==204){assertEquals(0,new java.math.BigDecimal(sale.jsonPath().get("retailPrice").toString()).compareTo(new java.math.BigDecimal("30")));assertEquals(1,tiers.jsonPath().getList("").size());assertEquals(4,((Number)tiers.jsonPath().get("[0].minimumQuantity")).intValue());}
            else {assertEquals(0,new java.math.BigDecimal(sale.jsonPath().get("retailPrice").toString()).compareTo(new java.math.BigDecimal("40")));assertEquals(2,tiers.jsonPath().getList("").size());assertEquals(9,((Number)tiers.jsonPath().get("[0].minimumQuantity")).intValue());assertEquals(12,((Number)tiers.jsonPath().get("[1].minimumQuantity")).intValue());}
            assertEquals(0,new java.math.BigDecimal(tiers.jsonPath().get("[0].unitPrice").toString()).compareTo(new java.math.BigDecimal(first==204?"20":"15")));
            if(second==204)assertEquals(0,new java.math.BigDecimal(tiers.jsonPath().get("[1].unitPrice").toString()).compareTo(new java.math.BigDecimal("10")));
            assertEquals(auditBefore+1,sqlCount("SELECT count(*) FROM audit_event WHERE tenant_id=? AND entity_id=? AND action='PRODUCT_SALE_PRICES_UPDATED'",UUID.fromString(tenant),UUID.fromString(product)));
        } finally {writers.shutdownNow();}
        var beforeSale=given().auth().oauth2(admin).get(path).then().statusCode(200).extract().response();
        var beforeTiers=given().auth().oauth2(admin).get(path+"/tiers").then().statusCode(200).extract().asString();
        var beforeCost=given().auth().oauth2(admin).get(path+"/cost").then().statusCode(200).extract().response();
        int countBefore=sqlCount("SELECT count(*) FROM audit_event WHERE tenant_id=? AND entity_id=? AND action IN ('PRODUCT_SALE_PRICES_UPDATED','PRODUCT_COST_UPDATED')",UUID.fromString(tenant),UUID.fromString(product));
        var gateBoth=new java.util.concurrent.CountDownLatch(1);var both=java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var saleWrite=both.submit(()->{gateBoth.await();return given().auth().oauth2(admin).contentType("application/json").body("{\"version\":7,\"retailPrice\":50,\"tiers\":[{\"minimumQuantity\":5,\"unitPrice\":25}]}").put(path+"/sale").statusCode();});
            var costWrite=both.submit(()->{gateBoth.await();return given().auth().oauth2(admin).contentType("application/json").body("{\"version\":7,\"cost\":17}").put(path+"/cost").statusCode();});
            gateBoth.countDown();int saleResult=saleWrite.get(),costResult=costWrite.get();assertEquals(java.util.Set.of(204,409),java.util.Set.of(saleResult,costResult));
            var afterSale=given().auth().oauth2(admin).get(path).then().statusCode(200).body("version",is(8)).extract().response();
            var afterCost=given().auth().oauth2(admin).get(path+"/cost").then().statusCode(200).extract().response();
            var afterTiers=given().auth().oauth2(admin).get(path+"/tiers").then().statusCode(200).extract().asString();
            if(saleResult==204){assertEquals(0,new java.math.BigDecimal(afterSale.jsonPath().get("retailPrice").toString()).compareTo(new java.math.BigDecimal("50")));assertEquals((Object)beforeCost.jsonPath().get("cost"),(Object)afterCost.jsonPath().get("cost"));assertTrue(afterTiers.contains("25"));}
            else {assertEquals((Object)beforeSale.jsonPath().get("retailPrice"),(Object)afterSale.jsonPath().get("retailPrice"));assertEquals(beforeTiers,afterTiers);assertEquals(0,new java.math.BigDecimal(afterCost.jsonPath().get("cost").toString()).compareTo(new java.math.BigDecimal("17")));}
            assertEquals(countBefore+1,sqlCount("SELECT count(*) FROM audit_event WHERE tenant_id=? AND entity_id=? AND action IN ('PRODUCT_SALE_PRICES_UPDATED','PRODUCT_COST_UPDATED')",UUID.fromString(tenant),UUID.fromString(product)));
            assertEquals(1,sqlCount("SELECT count(*) FROM audit_event WHERE tenant_id=? AND entity_id=? AND action=? AND (after_data ->> ?)::numeric = ?",UUID.fromString(tenant),UUID.fromString(product),saleResult==204?"PRODUCT_SALE_PRICES_UPDATED":"PRODUCT_COST_UPDATED",saleResult==204?"retailPrice":"cost",new java.math.BigDecimal(saleResult==204?"50":"17")));
        } finally {both.shutdownNow();}
        try(var c=dataSource.getConnection();var s=c.prepareStatement("UPDATE product SET status='ARCHIVED' WHERE tenant_id=? AND product_id=?")){
            s.setObject(1,UUID.fromString(tenant));s.setObject(2,UUID.fromString(product));s.executeUpdate();
        }
        given().auth().oauth2(admin).contentType("application/json").body("{\"version\":6,\"cost\":1}").put(path+"/cost").then().statusCode(409);
        given().auth().oauth2(admin).contentType("application/json").body("{\"version\":6,\"retailPrice\":1,\"tiers\":[]}").put(path+"/sale").then().statusCode(409);
        UUID tenantId=UUID.fromString(tenant);
        String matrixProduct=given().auth().oauth2(admin).contentType("application/json").body(java.util.Map.of("categoryId",category,"sku","PERMISSION-MATRIX","name","Matrix")).post(base+"/products").then().statusCode(201).extract().path("productId");
        String matrixPath=base+"/products/"+matrixProduct+"/prices";
        given().auth().oauth2(admin).contentType("application/json").body("{\"version\":0,\"retailPrice\":12,\"tiers\":[]}").put(matrixPath+"/sale").then().statusCode(204);
        long matrixVersion=1;
        String[] permissions={"PRODUCT_READ","PRODUCT_PRICE_TIER_READ","PRODUCT_COST_READ","PRODUCT_PRICE_UPDATE","PRODUCT_COST_UPDATE"};
        for(String permission:permissions){
            try(var c=dataSource.getConnection()){
                try(var d=c.prepareStatement("DELETE FROM role_permission WHERE tenant_id=? AND role_id IN (SELECT role_id FROM app_role WHERE tenant_id=? AND code IN ('PRICE_MANAGER','CATALOG_MANAGER')) AND permission_code IN ('PRODUCT_READ','PRODUCT_PRICE_TIER_READ','PRODUCT_COST_READ','PRODUCT_PRICE_UPDATE','PRODUCT_COST_UPDATE')")){d.setObject(1,tenantId);d.setObject(2,tenantId);d.executeUpdate();}
                try(var s=c.prepareStatement("INSERT INTO role_permission(tenant_id,role_id,permission_code) SELECT tenant_id,role_id,? FROM app_role WHERE tenant_id=? AND code='PRICE_MANAGER'")){s.setString(1,permission);s.setObject(2,tenantId);s.executeUpdate();}
            }
            given().auth().oauth2(admin).get(matrixPath).then().statusCode(permission.equals("PRODUCT_READ")?200:404);
            given().auth().oauth2(admin).get(matrixPath+"/tiers").then().statusCode(permission.equals("PRODUCT_PRICE_TIER_READ")?200:404);
            given().auth().oauth2(admin).queryParam("quantity",1).get(matrixPath+"/quote").then().statusCode(permission.equals("PRODUCT_PRICE_TIER_READ")?200:404);
            given().auth().oauth2(admin).get(matrixPath+"/cost").then().statusCode(permission.equals("PRODUCT_COST_READ")?200:404);
            given().auth().oauth2(admin).contentType("application/json").body("{\"version\":"+matrixVersion+",\"retailPrice\":1,\"tiers\":[]}").put(matrixPath+"/sale").then().statusCode(permission.equals("PRODUCT_PRICE_UPDATE")?204:404);
            if(permission.equals("PRODUCT_PRICE_UPDATE"))matrixVersion++;
            given().auth().oauth2(admin).contentType("application/json").body("{\"version\":"+matrixVersion+",\"cost\":1}").put(matrixPath+"/cost").then().statusCode(permission.equals("PRODUCT_COST_UPDATE")?204:404);
            if(permission.equals("PRODUCT_COST_UPDATE"))matrixVersion++;
        }
        try(var c=dataSource.getConnection();var s=c.prepareStatement("UPDATE role_permission SET permission_code='PRODUCT_COST_READ' WHERE tenant_id=? AND permission_code='PRODUCT_COST_UPDATE'")){s.setObject(1,tenantId);s.executeUpdate();}
        given().auth().oauth2(admin).get(path+"/cost").then().statusCode(200);
        try(var c=dataSource.getConnection();var s=c.prepareStatement("UPDATE user_account SET status='DISABLED' WHERE user_id=?")){s.setObject(1,account);s.executeUpdate();}
        try {given().auth().oauth2(admin).get(path+"/cost").then().statusCode(404);}
        finally {try(var c=dataSource.getConnection();var s=c.prepareStatement("UPDATE user_account SET status='ACTIVE' WHERE user_id=?")){s.setObject(1,account);s.executeUpdate();}}
        try(var c=dataSource.getConnection();var s=c.prepareStatement("UPDATE tenant_membership SET status='SUSPENDED' WHERE tenant_id=? AND user_id=?")){s.setObject(1,tenantId);s.setObject(2,account);s.executeUpdate();}
        try {given().auth().oauth2(admin).get(path+"/cost").then().statusCode(404);}
        finally {try(var c=dataSource.getConnection();var s=c.prepareStatement("UPDATE tenant_membership SET status='ACTIVE' WHERE tenant_id=? AND user_id=?")){s.setObject(1,tenantId);s.setObject(2,account);s.executeUpdate();}}
        given().get(path).then().statusCode(401);
    }

    @Test
    void onboardingThroughCdiUsesVerifiedActorAndCreatesOnlySelectedProfiles() throws Exception {
        String admin=token(KeycloakRealmResource.ADMIN,"commercecore-api"), targetToken=token(KeycloakRealmResource.ONBOARD_TARGET,"commercecore-api");
        String tenantAdmin=token(KeycloakRealmResource.TENANT,"commercecore-api"), missingActor=token(KeycloakRealmResource.UNPROVISIONED,"commercecore-api");
        UUID actor=provision(admin), target=provision(targetToken), businessUser=provision(tenantAdmin);
        String first="onboard-"+UUID.randomUUID(), second="onboard-"+UUID.randomUUID();
        String base="/test-only-platform-authorization/onboard/";
        given().post(base+first+"/"+target).then().statusCode(401);
        given().auth().oauth2("invalid").post(base+first+"/"+target).then().statusCode(401);
        given().auth().oauth2(missingActor).post(base+first+"/"+target).then().statusCode(403);
        given().auth().oauth2(admin).post(base+first+"/"+UUID.randomUUID()).then().statusCode(403);
        given().auth().oauth2(admin).queryParam("profile","ALL").post(base+first+"/"+target).then().statusCode(400);
        try(var c=dataSource.getConnection(); var s=c.prepareStatement("UPDATE user_account SET status=? WHERE user_id=?")) {
            s.setString(1,"DISABLED"); s.setObject(2,target); s.executeUpdate();
            given().auth().oauth2(admin).post(base+first+"/"+target).then().statusCode(403);
            s.setString(1,"ACTIVE"); s.setObject(2,target); s.executeUpdate();
            s.setString(1,"DISABLED"); s.setObject(2,actor); s.executeUpdate();
            given().auth().oauth2(admin).post(base+first+"/"+target).then().statusCode(403);
            s.setString(1,"ACTIVE"); s.setObject(2,actor); s.executeUpdate();
        }
        UUID businessTenant=UUID.randomUUID(), businessRole=UUID.randomUUID(), businessMembership=UUID.randomUUID();
        try(var c=dataSource.getConnection(); var s=c.createStatement()) {
            c.setAutoCommit(false);
            s.execute("INSERT INTO tenant(tenant_id,slug,legal_name,display_name) VALUES ('"+businessTenant+"','business-"+businessTenant+"','Test','Test')");
            s.execute("INSERT INTO app_role(tenant_id,role_id,code,name) VALUES ('"+businessTenant+"','"+businessRole+"','TENANT_ADMIN','Admin')");
            s.execute("INSERT INTO role_permission(tenant_id,role_id,permission_code) VALUES ('"+businessTenant+"','"+businessRole+"','TENANT_READ'),('"+businessTenant+"','"+businessRole+"','TENANT_MANAGE'),('"+businessTenant+"','"+businessRole+"','USER_MANAGE')");
            s.execute("INSERT INTO tenant_membership(tenant_id,membership_id,user_id,status) VALUES ('"+businessTenant+"','"+businessMembership+"','"+businessUser+"','ACTIVE')");
            s.execute("INSERT INTO membership_role(tenant_id,membership_id,role_id) VALUES ('"+businessTenant+"','"+businessMembership+"','"+businessRole+"')");
            c.commit();
        }
        given().auth().oauth2(tenantAdmin).post(base+first+"/"+target).then().statusCode(403);
        UUID empty=UUID.fromString(given().auth().oauth2(admin).post(base+first+"/"+target).then().statusCode(200).extract().asString());
        given().auth().oauth2(admin).post(base+first+"/"+target).then().statusCode(409);
        UUID selected=UUID.fromString(given().auth().oauth2(admin).queryParam("profile","CATALOG_OPERATOR")
                .queryParam("profile","PRICE_MANAGER").post(base+second+"/"+target).then().statusCode(200).extract().asString());
        assertEquals(1,sqlCount("SELECT count(*) FROM app_role WHERE tenant_id=?",empty));
        assertEquals(3,sqlCount("SELECT count(*) FROM role_permission WHERE tenant_id=?",empty));
        assertEquals(3,sqlCount("SELECT count(*) FROM app_role WHERE tenant_id=?",selected));
        assertEquals(3,sqlCount("SELECT count(*) FROM role_permission p JOIN app_role r ON r.tenant_id=p.tenant_id AND r.role_id=p.role_id WHERE p.tenant_id=? AND r.code='TENANT_ADMIN' AND p.permission_code IN ('TENANT_READ','TENANT_MANAGE','USER_MANAGE')",selected));
        assertEquals(1,sqlCount("SELECT count(*) FROM tenant_membership WHERE tenant_id=? AND status='ACTIVE'",selected));
        assertEquals(3,sqlCount("SELECT count(*) FROM membership_role WHERE tenant_id=?",selected));
        assertEquals(8,sqlCount("SELECT count(*) FROM audit_event WHERE tenant_id=?",selected));
        assertEquals(0,sqlCount("SELECT count(*) FROM tenant WHERE slug=? AND tenant_id<>?",first,empty));
    }

    private int sqlCount(String sql, Object... args) throws Exception {
        try(var c=dataSource.getConnection(); var s=c.prepareStatement(sql)) {
            for(int i=0;i<args.length;i++) s.setObject(i+1,args[i]);
            try(var r=s.executeQuery()) { assertTrue(r.next()); return r.getInt(1); }
        }
    }

    @Test
    void onboardingRollsBackOnRoleMembershipAssignmentAuditAndDeferredFailure() throws Exception {
        String admin=token(KeycloakRealmResource.ADMIN,"commercecore-api");
        UUID actor=provision(admin), target=provision(token(KeycloakRealmResource.ONBOARD_TARGET,"commercecore-api"));
        String[] tables={"app_role","tenant_membership","membership_role","audit_event","role_permission"};
        for(String table:tables) {
            String slug="failure-"+UUID.randomUUID(), suffix=UUID.randomUUID().toString().replace("-","");
            String function="onboard_failure_"+suffix, trigger="onboard_trigger_"+suffix;
            String action=table.equals("role_permission")
                    ? "IF NEW.permission_code='USER_MANAGE' THEN RETURN NULL; END IF;"
                    : "RAISE EXCEPTION 'onboarding test failure';";
            try {
                try(var c=dataSource.getConnection(); var s=c.createStatement()) {
                    s.execute("CREATE FUNCTION "+function+"() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF EXISTS (SELECT 1 FROM tenant WHERE tenant_id=NEW.tenant_id AND slug='"+slug+"') THEN "+action+" END IF; RETURN NEW; END $$");
                    s.execute("CREATE TRIGGER "+trigger+" BEFORE INSERT ON "+table+" FOR EACH ROW EXECUTE FUNCTION "+function+"()");
                }
                given().auth().oauth2(admin).post("/test-only-platform-authorization/onboard/"+slug+"/"+target).then().statusCode(500);
                assertEquals(0,sqlCount("SELECT count(*) FROM tenant WHERE slug=?",slug));
                assertEquals(0,sqlCount("SELECT count(*) FROM audit_event WHERE actor_user_id=? AND tenant_id IN (SELECT tenant_id FROM tenant WHERE slug=?)",actor,slug));
            } finally {
                try(var c=dataSource.getConnection(); var s=c.createStatement()) {
                    s.execute("DROP TRIGGER IF EXISTS "+trigger+" ON "+table);
                    s.execute("DROP FUNCTION IF EXISTS "+function+"()");
                }
            }
        }
    }

    @Test
    void concurrentOnboardingWithSameSlugCreatesExactlyOneTenant() throws Exception {
        String admin=token(KeycloakRealmResource.ADMIN,"commercecore-api");
        provision(admin); UUID target=provision(token(KeycloakRealmResource.ONBOARD_TARGET,"commercecore-api"));
        String slug="concurrent-"+UUID.randomUUID(), path="/test-only-platform-authorization/onboard/"+slug+"/"+target;
        var start=new java.util.concurrent.CountDownLatch(1); var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Callable<Integer> create=() -> { start.await(); return given().auth().oauth2(admin).post(path).statusCode(); };
            var first=pool.submit(create); var second=pool.submit(create); start.countDown();
            assertEquals(java.util.Set.of(200,409),java.util.Set.of(first.get(),second.get()));
            assertEquals(1,sqlCount("SELECT count(*) FROM tenant WHERE slug=?",slug));
        } finally { pool.shutdownNow(); }
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
