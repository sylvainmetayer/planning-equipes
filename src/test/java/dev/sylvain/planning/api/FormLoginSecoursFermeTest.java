package dev.sylvain.planning.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;

import dev.sylvain.planning.OidcTests;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

/** Behind a provider the break-glass door is closed by default (ADR 0093). */
@QuarkusTest
@TestProfile(OidcTests.Profil.class)
class FormLoginSecoursFermeTest {

    @Test
    void leFormulaireEstFermeParDefautEtUnPrefixeNeLeContournePas() {
        for (String chemin : new String[] {"/j_security_check", "/api/j_security_check"}) {
            given().contentType("application/x-www-form-urlencoded")
                    .formParam("j_username", "admin")
                    .formParam("j_password", "admin")
                    .redirects()
                    .follow(false)
                    .post(chemin)
                    .then()
                    .statusCode(409)
                    .header("Set-Cookie", nullValue())
                    .body("message", containsString("ADMIN_LOCAL_SECOURS"));
        }
    }

    @Test
    void sansSessionLApiRepond401() {
        given().get("/api/animateurs").then().statusCode(401);
    }
}
