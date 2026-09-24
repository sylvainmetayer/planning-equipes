package dev.sylvain.planning.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.emptyOrNullString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;

import io.quarkus.test.junit.QuarkusTest;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.Test;

/**
 * The version {@code scripts/verifier-deploiement.sh} compares with the one the
 * operator meant to deploy is the one the startup line prints — never a second
 * value that could drift from it.
 */
@QuarkusTest
class ConfigResourceTest {

    @Test
    void exposesTheBackendVersion() {
        String expected = ConfigProvider.getConfig().getValue("quarkus.application.version", String.class);
        given().when()
                .get("/api/config")
                .then()
                .statusCode(200)
                .body("version", not(emptyOrNullString()))
                .body("version", equalTo(expected));
    }
}
