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
 * value that could drift from it. And only a session reads it: a visitor gets
 * what the login page and the espace need, nothing that says which version to
 * attack.
 */
@QuarkusTest
class ConfigResourceTest {

    @Test
    @TestSecurity(user = "admin", roles = "admin")
    void aSessionReadsTheBackendVersion() {
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
                .body("$", not(hasKey("version")))
                .body("$", not(hasKey("devMode")))
                .body("$", not(hasKey("mailMock")))
                .body("authOidc", equalTo(false))
                .body("authSecours", equalTo(true))
                .body("$", hasKey("sentryDsn"))
                .body("$", hasKey("cloudflareWebAnalyticsToken"));
    }
}
