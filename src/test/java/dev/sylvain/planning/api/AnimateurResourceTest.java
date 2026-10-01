package dev.sylvain.planning.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * A fiche without a prénom, a nom or a date de naissance is refused with a
 * 400 naming every missing field — not written, and not a 500 from the
 * database's {@code NOT NULL} (the Sentry event behind this test). The date
 * matters most: without it an animateur is neither minor nor adult to the
 * legal constraints.
 */
@QuarkusTest
class AnimateurResourceTest {

    /** The id the application generated for the fiche a test created, if any. */
    private String createdId;

    @AfterEach
    void removeFixture() {
        if (createdId != null) {
            given().header("X-Edition-Id", "E1").when().delete("/api/animateurs/" + createdId);
        }
    }

    @Test
    void aCreationWithoutBirthDateIsRefusedNamingTheDate() {
        given().header("X-Edition-Id", "E1")
                .contentType(ContentType.JSON)
                .body("""
                        {"prenom":"Alice","nom":"AR-Incomplet"}""")
                .when()
                .post("/api/animateurs")
                .then()
                .statusCode(400)
                .body("message", containsString("date de naissance"))
                .body("message", not(containsString("prénom")))
                .body("message", not(containsString("le nom")));

        // Nothing was written: no fiche carries that name.
        given().header("X-Edition-Id", "E1")
                .when()
                .get("/api/animateurs")
                .then()
                .statusCode(200)
                .body("nom", not(hasItem("AR-Incomplet")));
    }

    @Test
    void aCreationWithABlankFirstNameIsRefused() {
        given().header("X-Edition-Id", "E1")
                .contentType(ContentType.JSON)
                .body("""
                        {"prenom":"   ","nom":"Martin","dateNaissance":"1990-01-01"}""")
                .when()
                .post("/api/animateurs")
                .then()
                .statusCode(400)
                .body("message", containsString("prénom"))
                .body("message", not(containsString("date de naissance")));
    }

    @Test
    void aCreationMissingEverythingIsRefusedOnceNamingAllThreeFields() {
        given().header("X-Edition-Id", "E1")
                .contentType(ContentType.JSON)
                .body("""
                        {"prenom":"","nom":""}""")
                .when()
                .post("/api/animateurs")
                .then()
                .statusCode(400)
                .body("message", containsString("prénom"))
                .body("message", containsString("nom"))
                .body("message", containsString("date de naissance"))
                .body(
                        "message",
                        equalTo(
                                "Fiche incomplète : le prénom, le nom et la date de naissance sont "
                                        + "obligatoires ; sans date de naissance, tout le régime mineur / majeur est indéterminé."));
    }

    @Test
    void anEditCannotBlankTheNameNorDropTheBirthDate() {
        createdId = given().header("X-Edition-Id", "E1")
                .contentType(ContentType.JSON)
                .body("""
                        {"prenom":"Alice","nom":"Martin","dateNaissance":"1990-01-01"}""")
                .when()
                .post("/api/animateurs")
                .then()
                .statusCode(200)
                .extract()
                .path("animateur.id");

        given().header("X-Edition-Id", "E1")
                .contentType(ContentType.JSON)
                .body("""
                        {"prenom":"Alice","nom":"","dateNaissance":null}""")
                .when()
                .put("/api/animateurs/" + createdId)
                .then()
                .statusCode(400)
                .body("message", containsString("nom"))
                .body("message", containsString("date de naissance"))
                .body("message", not(containsString("prénom")));

        // The stored fiche is untouched by the refused edit.
        given().header("X-Edition-Id", "E1")
                .when()
                .get("/api/animateurs")
                .then()
                .statusCode(200)
                .body("find { it.id == '" + createdId + "' }.nom", equalTo("Martin"))
                .body("find { it.id == '" + createdId + "' }.dateNaissance", equalTo("1990-01-01"));
    }

    /**
     * The phone number is written with the fiche and read back with it,
     * trimmed; past its 32 characters the write is refused in 400 — never the
     * database's 500 — without quoting the number back, and the fiche keeps
     * the one it had.
     */
    @Test
    void thePhoneNumberIsReadBackAndATooLongOneRefused() {
        createdId = given().header("X-Edition-Id", "E1")
                .contentType(ContentType.JSON)
                .body("""
                        {"prenom":"Alice","nom":"AR-Telephone","dateNaissance":"1990-01-01",\
                        "telephone":" 06 12 34 56 78 "}""")
                .when()
                .post("/api/animateurs")
                .then()
                .statusCode(200)
                .extract()
                .path("animateur.id");
        String tropLong = "+33 6 12 34 56 78 poste 1234567890";

        given().header("X-Edition-Id", "E1")
                .contentType(ContentType.JSON)
                .body("{\"prenom\":\"Alice\",\"nom\":\"AR-Telephone\",\"dateNaissance\":\"1990-01-01\","
                        + "\"telephone\":\"" + tropLong + "\"}")
                .when()
                .put("/api/animateurs/" + createdId)
                .then()
                .statusCode(400)
                .body("message", containsString("32 caractères"))
                .body("message", not(containsString(tropLong)));

        given().header("X-Edition-Id", "E1")
                .when()
                .get("/api/animateurs")
                .then()
                .statusCode(200)
                .body("find { it.id == '" + createdId + "' }.telephone", equalTo("06 12 34 56 78"));
    }
}
