package com.commercecore.diaz.identity;

import io.quarkus.test.junit.QuarkusIntegrationTest;
import org.junit.jupiter.api.Test;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

@QuarkusIntegrationTest
class AdministrativeApiIT {
    @Test
    void packagedAdministrativeEndpointsRequireBearer() {
        given().post("/api/v1/accounts/me/provisioning").then().statusCode(401)
                .header("WWW-Authenticate",containsString("Bearer"));
        given().get("/api/v1/tenants/00000000-0000-4000-8000-000000000001/memberships").then().statusCode(401)
                .header("WWW-Authenticate",containsString("Bearer"));
        given().get("/api/v1/tenants/00000000-0000-4000-8000-000000000001/roles").then().statusCode(401)
                .header("WWW-Authenticate",containsString("Bearer"));
        given().put("/api/v1/platform/tenants/00000000-0000-4000-8000-000000000001/administrators/00000000-0000-4000-8000-000000000002")
                .then().statusCode(401).header("WWW-Authenticate",containsString("Bearer"));
        given().contentType("application/json").body("{}").post("/api/v1/platform/tenants")
                .then().statusCode(401).header("WWW-Authenticate",containsString("Bearer"));
    }
}
