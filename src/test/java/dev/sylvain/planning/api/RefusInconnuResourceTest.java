package dev.sylvain.planning.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

/**
 * An edit of a fiche that does not exist answers 404 with one sentence, the
 * one the screen shows as the title of its notification. #447 converted
 * seven JAX-RS {@code NotFoundException} into {@code BusinessError.NotFound}
 * and nothing observed the body: the seven sentences went on screen in
 * English, and no test moved. One refusal per referential, message included.
 */
@QuarkusTest
class RefusInconnuResourceTest {

    @Test
    void unStandInconnuRepondQuatreCentQuatreEtLeDit() {
        given().contentType(ContentType.JSON)
                .body("""
                        {"id":"INCONNU-STAND","nom":"X","typologiesProposees":["STRATEGIE"],"effectifMin":1,"effectifMax":1}""")
                .when()
                .put("/api/stands/INCONNU-STAND")
                .then()
                .statusCode(404)
                .body("message", equalTo("Stand inconnu : INCONNU-STAND"));
    }

    @Test
    void unCreneauInconnuRepondQuatreCentQuatreEtLeDit() {
        given().contentType(ContentType.JSON)
                .body("""
                        {"jour":1,"date":"2030-01-01","heureDebut":"09:00","heureFin":"12:00"}""")
                .when()
                .put("/api/creneaux/987654321")
                .then()
                .statusCode(404)
                .body("message", equalTo("Créneau inconnu : 987654321"));
    }

    @Test
    void unEmplacementInconnuRepondQuatreCentQuatreEtLeDit() {
        given().contentType(ContentType.JSON)
                .body("""
                        {"id":"INCONNU-EMP","nom":"X"}""")
                .when()
                .put("/api/emplacements/INCONNU-EMP")
                .then()
                .statusCode(404)
                .body("message", equalTo("Emplacement inconnu : INCONNU-EMP"));
    }

    @Test
    void uneTypologieInconnueRepondQuatreCentQuatreEtLeDit() {
        given().contentType(ContentType.JSON)
                .body("""
                        {"id":"INCONNU-TYPO","label":"X"}""")
                .when()
                .put("/api/typologies/INCONNU-TYPO")
                .then()
                .statusCode(404)
                .body("message", equalTo("Typologie inconnue : INCONNU-TYPO"));
    }

    @Test
    void uneEditionInconnueRepondQuatreCentQuatreEtLeDit() {
        given().contentType(ContentType.JSON)
                .body("""
                        {"nom":"X"}""")
                .when()
                .put("/api/editions/INCONNU-EDITION")
                .then()
                .statusCode(404)
                .body("message", equalTo("Édition inconnue : INCONNU-EDITION"));
    }
}
