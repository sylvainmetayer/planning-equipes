package dev.sylvain.planning.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.Test;

/**
 * The version {@code scripts/verifier-deploiement.sh} compares with the one the
 * operator meant to deploy is the one the startup line prints — never a second
 * value that could drift from it, and it reads it without a session. A
 * visitor gets nothing else the admin interface alone reads.
 */
@QuarkusTest
class ConfigResourceTest {

    @Test
    void aVisitorReadsTheBackendVersion() {
        String expected = ConfigProvider.getConfig().getValue("quarkus.application.version", String.class);
        given().when().get("/api/config").then().statusCode(200).body("version", equalTo(expected));
    }

    @Test
    @TestSecurity(user = "admin", roles = "admin")
    void aSessionReadsTheAdminSwitches() {
        String expected = ConfigProvider.getConfig().getValue("quarkus.application.version", String.class);
        given().header("X-Edition-Id", "E1")
                .when()
                .get("/api/config")
                .then()
                .statusCode(200)
                .body("version", equalTo(expected))
                .body("devMode", equalTo(false))
                .body("mailMock", equalTo(true));
    }

    @Test
    void aVisitorReadsOnlyWhatTheSignInScreensNeed() {
        given().when()
                .get("/api/config")
                .then()
                .statusCode(200)
                .body("$", not(hasKey("devMode")))
                .body("$", not(hasKey("mailMock")))
                .body("authOidc", equalTo(false))
                .body("authSecours", equalTo(true))
                .body("$", hasKey("sentryDsn"))
                .body("$", hasKey("cloudflareWebAnalyticsToken"));
    }
}
