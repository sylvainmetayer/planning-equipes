package dev.sylvain.planning.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.notNullValue;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

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

        JsonPath rapport = given().when()
                .get("/api/ouvertures-stands")
                .then()
                .statusCode(200)
                .body("jours.size()", greaterThan(0))
                .body("stands.size()", greaterThan(0))
                .body("postesTotal", notNullValue())
                .extract()
                .jsonPath();

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

        JsonPath rapport = given().when()
                .get("/api/ouvertures-stands")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath();

        List<String> etats = rapport.getList("stands.jours.etat.flatten()");
        List<String> sources = rapport.getList("stands.jours.source.flatten()");
        assertThat(etats)
                .isNotEmpty()
                .allSatisfy(etat -> assertThat(etat).isIn("OUVERT_TOTAL", "OUVERT_PARTIEL", "FERME"));
        assertThat(sources).isNotEmpty().allSatisfy(source -> assertThat(source).isIn("DEFAUT", "REGLE", "EXCEPTION"));
    }

    /**
     * The total of a row must be the sum of its cells: that is what the
     * administrator reads at the end of the row to check it at a glance.
     */
    @Test
    void leTotalDUneLigneEstLaSommeDeSesCellules() {
        seedScenario();

        JsonPath rapport = given().when()
                .get("/api/ouvertures-stands")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath();

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

    /**
     * The grid is also where the schedule is typed: what is written under each
     * créneau is what the next read shows, bounds derived from the cells.
     */
    @Test
    void laGrilleSaisieEstRelueTelleQuelle() {
        seedScenario();
        JsonPath avant = given().when()
                .get("/api/ouvertures-stands")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath();
        String standId = avant.getString("stands[0].standId");
        // A créneau cut into columns repeats its id: one whole cell per créneau here.
        List<Integer> creneauIds = avant.getList("jours.creneaux.id.flatten()", Integer.class).stream()
                .distinct()
                .toList();
        assertThat(creneauIds).hasSizeGreaterThan(1);

        // Closed on the first créneau, three people everywhere else.
        List<Map<String, Object>> cellules = new java.util.ArrayList<>();
        for (int index = 0; index < creneauIds.size(); index++) {
            Map<String, Object> cellule = new java.util.LinkedHashMap<>();
            cellule.put("creneauId", creneauIds.get(index));
            cellule.put("effectif", index == 0 ? null : 3);
            cellules.add(cellule);
        }
        given().contentType("application/json")
                .body(Map.of("stands", List.of(Map.of("standId", standId, "cellules", cellules))))
                .when()
                .put("/api/ouvertures-stands/grille")
                .then()
                .statusCode(200)
                .body("stands[0].standId", org.hamcrest.Matchers.equalTo(standId))
                .body("stands[0].effectifMin", org.hamcrest.Matchers.equalTo(3))
                .body("stands[0].effectifMax", org.hamcrest.Matchers.equalTo(3));

        JsonPath apres = given().when()
                .get("/api/ouvertures-stands")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath();
        List<Integer> effectifs = apres.getList("stands[0].jours.creneaux.effectif.flatten()", Integer.class);
        assertThat(effectifs.get(0)).isNull();
        assertThat(effectifs.subList(1, effectifs.size())).containsOnly(3);
        assertThat(apres.getList("stands[0].jours.creneaux.partiel.flatten()", Boolean.class))
                .containsOnly(false);
        assertThat(apres.getInt("stands[0].effectifMin")).isEqualTo(3);
    }

    /**
     * The grid rewrites a stand's whole schedule, so it carries the same
     * precondition as its fiche (issue #362): a stand written since the grid
     * was read is refused alone, and nothing of it is written.
     */
    @Test
    void unStandModifieDepuisLaLectureDeLaGrilleEstRefuse() {
        seedScenario();
        JsonPath grille = given().when()
                .get("/api/ouvertures-stands")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath();
        String standId = grille.getString("stands[0].standId");
        String luParLaGrille = grille.getString("stands[0].modifieLe");
        assertThat(luParLaGrille)
                .as("the grid reads the stamp it will send back")
                .isNotNull();
        int creneauId = given().when()
                .get("/api/ouvertures-stands")
                .then()
                .extract()
                .jsonPath()
                .getList("jours.creneaux.id.flatten()", Integer.class)
                .get(0);

        // Another session renames the stand: the grid's stamp is now out of date.
        Map<String, Object> stand = given().when()
                .get("/api/stands")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getList("findAll { it.id == '" + standId + "' }", Map.class)
                .get(0);
        stand.put("nom", "Renommé ailleurs");
        given().contentType("application/json")
                .body(stand)
                .when()
                .put("/api/stands/" + standId)
                .then()
                .statusCode(200);

        given().contentType("application/json")
                .body(Map.of(
                        "stands",
                        List.of(Map.of(
                                "standId",
                                standId,
                                "modifieLe",
                                luParLaGrille,
                                "cellules",
                                List.of(Map.of("creneauId", creneauId, "effectif", 2))))))
                .when()
                .put("/api/ouvertures-stands/grille")
                .then()
                .statusCode(409)
                .body("code", org.hamcrest.Matchers.equalTo("MODIFICATION_CONCURRENTE"));

        // Without a precondition the same save goes through, as an import does.
        given().contentType("application/json")
                .body(Map.of(
                        "stands",
                        List.of(Map.of(
                                "standId",
                                standId,
                                "cellules",
                                List.of(Map.of("creneauId", creneauId, "effectif", 2))))))
                .when()
                .put("/api/ouvertures-stands/grille")
                .then()
                .statusCode(200);
    }

    @Test
    void uneCelluleSurUnCreneauInconnuEstRefusee() {
        seedScenario();
        String standId = given().when()
                .get("/api/ouvertures-stands")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getString("stands[0].standId");

        given().contentType("application/json")
                .body(Map.of(
                        "stands",
                        List.of(Map.of(
                                "standId", standId, "cellules", List.of(Map.of("creneauId", 999999, "effectif", 1))))))
                .when()
                .put("/api/ouvertures-stands/grille")
                .then()
                .statusCode(400);
    }

    private static void seedScenario() {
        given().when().post("/api/planning/reset").then().statusCode(200);
        given().when()
                .post("/api/reference-data/import-scenario?name=scenario.yml")
                .then()
                .statusCode(200);
    }
}
