package dev.sylvain.planning.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

/** The four sections under {@code /parametres}, and the 3.x roots that stay as aliases of them. */
@QuarkusTest
class ParametresSectionsResourceTest {

    @Test
    void theWholeAnswerCarriesTheFourSections() {
        given().header("X-Edition-Id", "E1")
                .when()
                .get("/api/parametres")
                .then()
                .statusCode(200)
                .body("legal.dureePauseMinutes", notNullValue())
                .body("organisation.joursConsecutifsMax", notNullValue())
                .body("notifications.delaiRelanceHeures", notNullValue())
                .body("calcul.instance", notNullValue());
    }

    @Test
    void eachFormerRootAnswersWhatItsSectionAnswers() {
        String[][] roots = {
            {"/api/parametres-legaux", "/api/parametres/legal"},
            {"/api/parametres-qualite", "/api/parametres/organisation"},
            {"/api/parametres-notifications", "/api/parametres/notifications"},
            {"/api/parametres-solveur", "/api/parametres/calcul"},
        };
        for (String[] pair : roots) {
            String old = given().header("X-Edition-Id", "E1")
                    .get(pair[0])
                    .then()
                    .statusCode(200)
                    .extract()
                    .asString();
            String section = given().header("X-Edition-Id", "E1")
                    .get(pair[1])
                    .then()
                    .statusCode(200)
                    .extract()
                    .asString();
            org.assertj.core.api.Assertions.assertThat(section).as(pair[1]).isEqualTo(old);
        }
    }

    @Test
    void aWriteThroughTheSectionIsReadBackThroughTheFormerRoot() {
        String courant = given().header("X-Edition-Id", "E1")
                .get("/api/parametres/notifications")
                .then()
                .statusCode(200)
                .extract()
                .asString();
        given().header("X-Edition-Id", "E1")
                .contentType(ContentType.JSON)
                .body(courant.replaceAll("\"anciennete[A-Za-z]*\":\\d+", "\"ancienneteEchangeJours\":4"))
                .put("/api/parametres/notifications")
                .then()
                .statusCode(200);
        given().header("X-Edition-Id", "E1")
                .get("/api/parametres-notifications")
                .then()
                .statusCode(200)
                .body("ancienneteEchangeJours", equalTo(4));
    }

    /** The meal-break placement (ADR 0123): shipped at its default, written under the stamp, read back. */
    @Test
    void theMealPlacementShipsItsDefaultAndIsWrittenThroughTheOrganisationSection() {
        String courant = given().header("X-Edition-Id", "E1")
                .get("/api/parametres/organisation")
                .then()
                .statusCode(200)
                .body("placementRepas", equalTo("FIN_MIDI_DEBUT_SOIR"))
                .extract()
                .asString();
        given().header("X-Edition-Id", "E1")
                .contentType(ContentType.JSON)
                .body(courant.replace("\"FIN_MIDI_DEBUT_SOIR\"", "\"AUCUN\""))
                .put("/api/parametres/organisation")
                .then()
                .statusCode(200)
                .body("placementRepas", equalTo("AUCUN"));
        given().header("X-Edition-Id", "E1")
                .get("/api/parametres-qualite")
                .then()
                .statusCode(200)
                .body("placementRepas", equalTo("AUCUN"));
        // The edition is shared with the other classes: back to the shipped value, under the stamp just written.
        String ecrit = given().header("X-Edition-Id", "E1")
                .get("/api/parametres/organisation")
                .then()
                .extract()
                .asString();
        given().header("X-Edition-Id", "E1")
                .contentType(ContentType.JSON)
                .body(ecrit.replace("\"AUCUN\"", "\"FIN_MIDI_DEBUT_SOIR\""))
                .put("/api/parametres/organisation")
                .then()
                .statusCode(200);
    }

    @Test
    void aFieldOfAnotherSectionIsRefusedBySectionButStillIgnoredByTheFormerRoot() {
        String courant = given().header("X-Edition-Id", "E1")
                .get("/api/parametres/notifications")
                .then()
                .statusCode(200)
                .extract()
                .asString();
        String avecIntrus = courant.replaceFirst("\\{", "{\"dureePauseMinutes\":25,");
        given().header("X-Edition-Id", "E1")
                .contentType(ContentType.JSON)
                .body(avecIntrus)
                .put("/api/parametres/notifications")
                .then()
                .statusCode(400)
                .body("message", containsString("dureePauseMinutes"));
        given().header("X-Edition-Id", "E1")
                .contentType(ContentType.JSON)
                .body(avecIntrus)
                .put("/api/parametres-notifications")
                .then()
                .statusCode(200);
    }

    /**
     * One refusal per section: the {@code modifieLe} a GET gave is the precondition of the PUT, a stale one is a
     * 409 {@code MODIFICATION_CONCURRENTE}, a body without it checks nothing, and the former roots ignore it.
     */
    @Test
    void aStaleReadIsRefusedOnEachSectionAndTheFormerRootsIgnoreTheStamp() {
        String[][] sections = {
            {"/api/parametres/legal", "/api/parametres-legaux"},
            {"/api/parametres/organisation", "/api/parametres-qualite"},
            {"/api/parametres/notifications", "/api/parametres-notifications"},
            {"/api/parametres/calcul", "/api/parametres-solveur"},
        };
        for (String[] pair : sections) {
            // No stamp: the first write checks nothing, and gives the row the stamp a reader will send back.
            put(pair[0], without(read(pair[0]), "modifieLe")).statusCode(200);
            String relu = read(pair[0]);
            // Another session writes through the 3.x root, which drops the stamp it is sent.
            put(pair[1], relu).statusCode(200);
            put(pair[0], relu)
                    .statusCode(409)
                    .body("code", equalTo("MODIFICATION_CONCURRENTE"))
                    .body("modifieLe", notNullValue());
            put(pair[0], read(pair[0])).statusCode(200);
        }
    }

    private static String read(String path) {
        return given().header("X-Edition-Id", "E1")
                .get(path)
                .then()
                .statusCode(200)
                .extract()
                .asString();
    }

    /** A section's answer minus {@code instance}, which a PUT never carries, and minus {@code champ} when given. */
    private static String without(String json, String champ) {
        return json.replaceAll(",?\"" + champ + "\":(null|\"[^\"]*\")", "");
    }

    private static io.restassured.response.ValidatableResponse put(String path, String json) {
        return given().header("X-Edition-Id", "E1")
                .contentType(ContentType.JSON)
                .body(json.replaceAll(",?\"instance\":\\{[^}]*}", ""))
                .put(path)
                .then();
    }
}
