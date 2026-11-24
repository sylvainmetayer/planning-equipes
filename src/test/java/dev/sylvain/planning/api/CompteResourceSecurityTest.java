package dev.sylvain.planning.api;

import static io.restassured.RestAssured.given;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

/** The accounts are the administrators' alone, even where the %test profile opens the rest of the API. */
@QuarkusTest
class CompteResourceSecurityTest {

    @Test
    void anAnonymousCallerIsRefused() {
        given().header("X-Edition-Id", "E1").get("/api/comptes").then().statusCode(401);
    }
}
