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
        String path="/api/v1/platform/tenants";
        given().contentType("application/json").body(request).post(path).then().statusCode(401);
        for(String t:java.util.List.of(member,other,tenantAdmin,unprovisioned))
            given().auth().oauth2(t).contentType("application/json").body(request).post(path).then().statusCode(403);
        UUID id=UUID.fromString(given().auth().oauth2(admin).contentType("application/json").body(request).post(path)
                .then().statusCode(201).extract().path("tenantId"));
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
        var document=new ObjectMapper().readTree(json);
        assertTrue(document.path("paths").has("/api/v1/accounts/me/provisioning"));
        assertTrue(document.path("paths").has("/api/v1/platform/tenants"));
        assertTrue(document.path("components").path("securitySchemes").has("bearerAuth"));
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
