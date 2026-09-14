package dev.sylvain.planning.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * A staging server launched with {@code HORLOGE_SIMULEE_AUTORISEE=true}: not in
 * {@code quarkus:dev}, and allowed to freeze its clock all the same.
 *
 * <p>{@link DateJourJResourceTest} proves the refusal on a bare instance. What
 * is proven here is the other half: that the variable is really read — a
 * permission whose property name drifted would pass every refusal test ever
 * written, and leave the staging server as locked as production.</p>
 */
@QuarkusTest
@TestProfile(HorlogeSimuleeRecetteTest.Recette.class)
class HorlogeSimuleeRecetteTest {

    private static final String JOUR = "2026-07-08";

    public static class Recette implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("planning.horloge-simulee.autorisee", "true");
        }
    }

    @AfterEach
    void rendreLHorloge() {
        given().contentType("application/json")
                .body("{\"dateDuJour\":null}")
                .when()
                .put("/api/debug/date-du-jour")
                .then()
                .statusCode(200);
    }

    @Test
    void laRecetteAnnonceQueLeChampPeutServir() {
        given().when().get("/api/debug/date-du-jour").then().statusCode(200).body("modifiable", equalTo(true));
    }

    @Test
    void laRecetteFigeLaDateEtLHeureHorsModeDeveloppement() {
        given().contentType("application/json")
                .body("{\"dateDuJour\":\"" + JOUR + "\",\"heureDuJour\":\"14:30\"}")
                .when()
                .put("/api/debug/date-du-jour")
                .then()
                .statusCode(200)
                .body("dateDuJour", equalTo(JOUR))
                .body("heureDuJour", equalTo("14:30"));

        // Read back too: the guard sits on the read as well as on the write.
        given().when()
                .get("/api/debug/date-du-jour")
                .then()
                .statusCode(200)
                .body("dateDuJour", equalTo(JOUR))
                .body("heureDuJour", equalTo("14:30"));
    }
}
