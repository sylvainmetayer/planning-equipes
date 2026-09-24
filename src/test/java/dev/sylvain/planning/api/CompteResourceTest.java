package dev.sylvain.planning.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Accounts and rights through the API (issues #294, #295): created, deactivated
 * and reactivated, rights granted and withdrawn — never deleted.
 */
@QuarkusTest
class CompteResourceTest {

    @Test
    void unCompteSeCreeSeDesactiveEtSeReactive() {
        String id = create("compte-cycle");

        given().when()
                .post("/api/comptes/" + id + "/desactivation")
                .then()
                .statusCode(200)
                .body("desactiveLe", notNullValue());
        given().when()
                .post("/api/comptes/" + id + "/reactivation")
                .then()
                .statusCode(200)
                .body("desactiveLe", nullValue());
        given().when().get("/api/comptes").then().statusCode(200).body("id", hasItem(id));
    }

    @Test
    void uneAdresseNeSertQuUneFoisQuelleQueSoitSaCasse() {
        String email = unique("compte-unique");
        createWith(email);
        given().contentType(ContentType.JSON)
                .body("{\"email\":\"" + email.toUpperCase(java.util.Locale.ROOT) + "\"}")
                .when()
                .post("/api/comptes")
                .then()
                .statusCode(409);
    }

    @Test
    void uneHabilitationSAccordePuisSeRetireSansDisparaitre() {
        String id = create("compte-rh");
        String habilitation = given().contentType(ContentType.JSON)
                .body("{\"role\":\"RH\"}")
                .when()
                .post("/api/comptes/" + id + "/habilitations")
                .then()
                .statusCode(200)
                .body("habilitations[0].role", equalTo("RH"))
                .extract()
                .path("habilitations[0].id");

        given().when()
                .delete("/api/comptes/" + id + "/habilitations/" + habilitation)
                .then()
                .statusCode(200)
                .body("habilitations[0].retireeLe", notNullValue());
        // Withdrawn once: a second withdrawal has nothing left to withdraw.
        given().when()
                .delete("/api/comptes/" + id + "/habilitations/" + habilitation)
                .then()
                .statusCode(404);
    }

    @Test
    void unResponsableDeStandExigeUneEditionEtDesStands() {
        String id = create("compte-responsable");
        given().contentType(ContentType.JSON)
                .body("{\"role\":\"RESPONSABLE_STAND\"}")
                .when()
                .post("/api/comptes/" + id + "/habilitations")
                .then()
                .statusCode(400)
                .body("message", containsString("responsable de stand"));
        given().contentType(ContentType.JSON)
                .body("{\"role\":\"RH\",\"standIds\":[\"S1\"]}")
                .when()
                .post("/api/comptes/" + id + "/habilitations")
                .then()
                .statusCode(400);
    }

    /** A stand the edition does not have is a 400 naming it, not a constraint violation. */
    @Test
    void unStandInconnuDeLEditionEstRefuseEnLeNommant() {
        String id = create("compte-stand-inconnu");
        String edition =
                given().when().get("/api/editions/courant").then().extract().path("id");
        given().contentType(ContentType.JSON)
                .body("{\"role\":\"RESPONSABLE_STAND\",\"editionId\":\"" + edition
                        + "\",\"standIds\":[\"stand-qui-n-existe-pas\"]}")
                .when()
                .post("/api/comptes/" + id + "/habilitations")
                .then()
                .statusCode(400)
                .body("message", containsString("stand-qui-n-existe-pas"));
    }

    @Test
    void uneExpirationDejaPasseeEstRefusee() {
        String id = create("compte-expire");
        given().contentType(ContentType.JSON)
                .body("{\"role\":\"RH\",\"expireLe\":\"2000-01-01T00:00:00Z\"}")
                .when()
                .post("/api/comptes/" + id + "/habilitations")
                .then()
                .statusCode(400);
    }

    @Test
    void unCompteInconnuRepond404() {
        given().when().post("/api/comptes/inconnu/desactivation").then().statusCode(404);
    }

    /** A fresh address per call: the accounts are never deleted, and the suite may run twice on one base. */
    private static String unique(String prefixe) {
        return prefixe + "-" + UUID.randomUUID() + "@example.org";
    }

    private static String create(String prefixe) {
        return createWith(unique(prefixe));
    }

    private static String createWith(String email) {
        return given().contentType(ContentType.JSON)
                .body("{\"email\":\"" + email + "\",\"nom\":\"Test\"}")
                .when()
                .post("/api/comptes")
                .then()
                .statusCode(200)
                .extract()
                .path("id");
    }
}
