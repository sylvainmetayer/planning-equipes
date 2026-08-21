package dev.sylvain.planning.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.notNullValue;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;

/**
 * {@code GET /api/ouvertures-stands}: the grid behind the "Ouvertures des
 * stands" screen. Like the feasibility diagnostic, it must answer <em>before</em>
 * any solve — none of these tests calls {@code /api/solve}.
 */
@QuarkusTest
class OuvertureStandsResourceTest {

    @Test
    void rendUneGrilleStandParJourAvecSesPostes() {
        seedScenario();

        JsonPath rapport = given()
                .when().get("/api/ouvertures-stands")
                .then()
                .statusCode(200)
                .body("jours.size()", greaterThan(0))
                .body("stands.size()", greaterThan(0))
                .body("postesTotal", notNullValue())
                .extract().jsonPath();

        int nombreJours = rapport.getList("jours").size();
        // Every row carries exactly one cell per day: that is what makes the
        // grid readable without the client having to fill in holes.
        int nombreStands = rapport.getList("stands").size();
        for (int index = 0; index < nombreStands; index++) {
            assertThat(rapport.getList("stands[" + index + "].jours"))
                    .as(rapport.getString("stands[" + index + "].standId"))
                    .hasSize(nombreJours);
        }

        assertThat(rapport.getInt("postesTotal")).isPositive();
        for (int index = 0; index < nombreJours; index++) {
            assertThat(rapport.getInt("jours[" + index + "].minutes")).isPositive();
            assertThat(rapport.getString("jours[" + index + "].date")).isNotBlank();
        }
    }

    /** The exposed enum values must stay the ones the frontend types. */
    @Test
    void chaqueCelluleExposeUnEtatEtUneSourceConnus() {
        seedScenario();

        JsonPath rapport = given()
                .when().get("/api/ouvertures-stands")
                .then().statusCode(200)
                .extract().jsonPath();

        List<String> etats = rapport.getList("stands.jours.etat.flatten()");
        List<String> sources = rapport.getList("stands.jours.source.flatten()");
        assertThat(etats).isNotEmpty().allSatisfy(etat ->
                assertThat(etat).isIn("OUVERT_TOTAL", "OUVERT_PARTIEL", "FERME"));
        assertThat(sources).isNotEmpty().allSatisfy(source ->
                assertThat(source).isIn("DEFAUT", "REGLE", "EXCEPTION"));
    }

    /**
     * The total of a row must be the sum of its cells: that is what the
     * administrator reads at the end of the row to check it at a glance.
     */
    @Test
    void leTotalDUneLigneEstLaSommeDeSesCellules() {
        seedScenario();

        JsonPath rapport = given()
                .when().get("/api/ouvertures-stands")
                .then().statusCode(200)
                .extract().jsonPath();

        int nombreStands = rapport.getList("stands").size();
        for (int index = 0; index < nombreStands; index++) {
            String prefixe = "stands[" + index + "]";
            List<Integer> minutes = rapport.getList(prefixe + ".jours.minutesOuvertes");
            List<Integer> postes = rapport.getList(prefixe + ".jours.postes");
            assertThat(rapport.getInt(prefixe + ".minutesOuvertes"))
                    .as(rapport.getString(prefixe + ".standId") + " minutes")
                    .isEqualTo(minutes.stream().mapToInt(Integer::intValue).sum());
            assertThat(rapport.getInt(prefixe + ".postes"))
                    .as(rapport.getString(prefixe + ".standId") + " postes")
                    .isEqualTo(postes.stream().mapToInt(Integer::intValue).sum());
        }
    }

    private static void seedScenario() {
        given().when().post("/api/planning/reset").then().statusCode(200);
        given()
                .when().post("/api/reference-data/import-scenario?name=scenario.yml")
                .then()
                .statusCode(200);
    }
}
