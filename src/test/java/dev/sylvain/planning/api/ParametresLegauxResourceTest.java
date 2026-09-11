package dev.sylvain.planning.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The evening hour of the legal parameters (issue #497): defaulted, stored
 * whole with the record, and refused when absent — an evening that starts
 * « never » would silently empty a column of the Équité screen.
 */
@QuarkusTest
class ParametresLegauxResourceTest {

    /**
     * The parameters row outlives {@code clearDatabase()}, and the suite's
     * other classes write it: every test here starts by putting the evening
     * back where a fresh edition has it.
     */
    @BeforeEach
    void eveningBackToDefault() {
        declarer("\"20:00:00\"").statusCode(200).body("heureDebutSoiree", equalTo("20:00:00"));
    }

    @Test
    void theEveningIsStoredWithTheRecordAndReadBack() {
        declarer("\"21:30:00\"").statusCode(200).body("heureDebutSoiree", equalTo("21:30:00"));

        given().when()
                .get("/api/parametres-legaux")
                .then()
                .statusCode(200)
                .body("heureDebutSoiree", equalTo("21:30:00"));
    }

    @Test
    void anAbsentEveningHourIsRefusedWithAnExplanation() {
        declarer("null").statusCode(400).body("message", containsString("heureDebutSoiree"));
    }

    @Test
    void aMalformedEveningHourIsRefusedRatherThanStoredAsSomethingElse() {
        declarer("\"vingt heures\"").statusCode(400);
    }

    /** PUTs the record as it stands, with {@code heureDebutSoiree} replaced by the JSON given. */
    private static ValidatableResponse declarer(String heureJson) {
        String courant = given().when()
                .get("/api/parametres-legaux")
                .then()
                .statusCode(200)
                .extract()
                .asString();
        String modifie =
                courant.replaceAll("\"heureDebutSoiree\":(\"[^\"]*\"|null)", "\"heureDebutSoiree\":" + heureJson);
        return given().contentType(ContentType.JSON)
                .body(modifie)
                .when()
                .put("/api/parametres-legaux")
                .then();
    }
}
