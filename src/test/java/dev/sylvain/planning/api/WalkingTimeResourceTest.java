package dev.sylvain.planning.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The three walking settings persist per edition with the other quality
 * thresholds, are refused when they could not mean anything, and the read-out
 * of the tight walks answers on whatever the edition holds.
 */
@QuarkusTest
class WalkingTimeResourceTest {

    private static final Map<String, Object> PAR_DEFAUT = Map.of(
            "maxEmplacementsDistinctsParJour", 3,
            "heureServiceTardif", "22:00:00",
            "heureServiceMatinal", "10:00:00",
            "reposSouhaiteApresServiceTardifMinutes", 720,
            "typologiesDistinctesMax", 2,
            "joursConsecutifsMax", 8,
            "vitesseMarcheKmH", 4.0,
            "facteurDetour", 1.3,
            "toleranceTrajetMinutes", 5);

    /** The row outlives a test: the next class finds the defaults again. */
    @AfterEach
    void backToDefaults() {
        save(PAR_DEFAUT).statusCode(200);
    }

    @Test
    void theWalkingSettingsAreStoredAndReadBack() {
        Map<String, Object> reglee = new java.util.HashMap<>(PAR_DEFAUT);
        reglee.put("vitesseMarcheKmH", 4.5);
        reglee.put("facteurDetour", 1.6);
        reglee.put("toleranceTrajetMinutes", 8);
        save(reglee).statusCode(200);

        given().when()
                .get("/api/parametres-qualite")
                .then()
                .statusCode(200)
                .body("vitesseMarcheKmH", equalTo(4.5f))
                .body("facteurDetour", equalTo(1.6f))
                .body("toleranceTrajetMinutes", equalTo(8));
    }

    @Test
    void aZeroWalkingSpeedOrADetourUnderOneIsRefused() {
        Map<String, Object> vitesseNulle = new java.util.HashMap<>(PAR_DEFAUT);
        vitesseNulle.put("vitesseMarcheKmH", 0);
        save(vitesseNulle).statusCode(400);

        Map<String, Object> raccourci = new java.util.HashMap<>(PAR_DEFAUT);
        raccourci.put("facteurDetour", 0.8);
        save(raccourci).statusCode(400);
    }

    @Test
    void theTightWalksReadOutAnswersWithItsSettings() {
        given().when()
                .get("/api/planning/enchainements")
                .then()
                .statusCode(200)
                .body("toleranceMinutes", equalTo(5))
                .body("geolocated", notNullValue())
                .body("walks", notNullValue());
    }

    private static io.restassured.response.ValidatableResponse save(Map<String, Object> parametres) {
        return given().contentType(ContentType.JSON)
                .body(parametres)
                .when()
                .put("/api/parametres-qualite")
                .then();
    }
}
