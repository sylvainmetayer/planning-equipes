package dev.sylvain.planning.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;

import io.quarkus.test.junit.QuarkusIntegrationTest;
import io.restassured.RestAssured;
import io.restassured.builder.RequestSpecBuilder;

/**
 * Same tests as {@link PlanningResourceTest}, but against the packaged
 * application — which runs the real {@code %prod} configuration, admin
 * authentication included (issue #165): unlike the JVM tests (whose
 * {@code %test} profile opens the API), every call here must carry a valid
 * session. The form login below is part of what this IT verifies.
 *
 * <p>The session is opened lazily from {@code @BeforeEach}, not
 * {@code @BeforeAll}: the packaged application (and RestAssured's port) is
 * only guaranteed to be up once tests start executing.</p>
 */
@QuarkusIntegrationTest
class PlanningResourceIT extends PlanningResourceTest {

    /**
     * The password failsafe hands to the packaged application
     * ({@code it.admin.password} in the pom). These run under the {@code %prod}
     * profile, where {@code DefaultSecrets} refuses the shipped value.
     */
    private static final String MOT_DE_PASSE_ADMIN = "it-admin-Hs3vQ9zR";

    private static String cookieSession;

    /**
     * Logs in once (default dev credentials, see {@code ADMIN_PASSWORD}) and
     * attaches the session cookie to every request the inherited tests make.
     */
    @BeforeEach
    void ouvrirSessionAdmin() {
        if (cookieSession == null) {
            cookieSession = RestAssured.given()
                    .contentType("application/x-www-form-urlencoded")
                    .formParam("j_username", "admin")
                    .formParam("j_password", MOT_DE_PASSE_ADMIN)
                    .redirects().follow(false)
                    .when().post("/j_security_check")
                    .then()
                    .extract().cookie("planning-session");
            assertThat(cookieSession).as("form login should issue the session cookie").isNotBlank();
        }
        RestAssured.requestSpecification = new RequestSpecBuilder()
                .addCookie("planning-session", cookieSession)
                .build();
    }

    @AfterAll
    static void fermerSessionAdmin() {
        RestAssured.requestSpecification = null;
        cookieSession = null;
    }
}
