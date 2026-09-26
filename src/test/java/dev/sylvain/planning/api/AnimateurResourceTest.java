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
            given().when().delete("/api/animateurs/" + createdId);
        }
    }

    @Test
    void aCreationWithoutBirthDateIsRefusedNamingTheDate() {
        given().contentType(ContentType.JSON)
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
        given().when().get("/api/animateurs").then().statusCode(200).body("nom", not(hasItem("AR-Incomplet")));
    }

    @Test
    void aCreationWithABlankFirstNameIsRefused() {
        given().contentType(ContentType.JSON)
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
        given().contentType(ContentType.JSON)
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
        createdId = given().contentType(ContentType.JSON)
                .body("""
                        {"prenom":"Alice","nom":"Martin","dateNaissance":"1990-01-01"}""")
                .when()
                .post("/api/animateurs")
                .then()
                .statusCode(200)
                .extract()
                .path("animateur.id");

        given().contentType(ContentType.JSON)
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
        given().when()
                .get("/api/animateurs")
                .then()
                .statusCode(200)
                .body("find { it.id == '" + createdId + "' }.nom", equalTo("Martin"))
                .body("find { it.id == '" + createdId + "' }.dateNaissance", equalTo("1990-01-01"));
    }

    /**
     * Without provisioning the screen shows no invitation button, and pressing
     * « send » anyway is refused rather than answered « nothing to send ».
     */
    @Test
    void withoutProvisioningThereIsNoInvitationToSend() {
        given().when()
                .get("/api/animateurs/invitations")
                .then()
                .statusCode(200)
                .body("actif", equalTo(false))
                .body("enAttente", equalTo(0));
        given().contentType(ContentType.JSON)
                .body("{}")
                .when()
                .post("/api/animateurs/invitations")
                .then()
                .statusCode(409);
    }
}
