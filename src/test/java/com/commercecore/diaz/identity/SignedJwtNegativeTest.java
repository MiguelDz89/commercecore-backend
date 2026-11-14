package com.commercecore.diaz.identity;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import static io.restassured.RestAssured.given;

@QuarkusTest
@QuarkusTestResource(value = SignedJwtIssuerResource.class, restrictToAnnotatedClass = true)
class SignedJwtNegativeTest {
    private static final String PATH = "/test-only-platform-authorization";

    @Test
    void validSignedTokenIsAccepted() {
        long now = Instant.now().getEpochSecond();
        request(SignedJwtIssuerResource.token(SignedJwtIssuerResource.issuer, true, now + 300, now), 200);
    }

    @Test
    void signedTokenWithoutSubjectIsRejected() {
        long now = Instant.now().getEpochSecond();
        request(SignedJwtIssuerResource.token(SignedJwtIssuerResource.issuer, false, now + 300, now), 401);
    }

    @Test
    void expiredSignedTokenIsRejected() {
        long now = Instant.now().getEpochSecond();
        request(SignedJwtIssuerResource.token(SignedJwtIssuerResource.issuer, true, now - 3600, now - 7200), 401);
    }

    @Test
    void wrongIssuerWithSameTrustedKeyAndClaimsIsRejected() {
        long now = Instant.now().getEpochSecond();
        request(SignedJwtIssuerResource.token(SignedJwtIssuerResource.issuer, true, now + 300, now), 200);
        request(SignedJwtIssuerResource.token(SignedJwtIssuerResource.issuer + "/wrong", true, now + 300, now), 401);
    }

    private static void request(String token, int expected) {
        given().auth().oauth2(token).when().get(PATH).then().statusCode(expected);
    }
}
