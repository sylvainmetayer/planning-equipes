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
     * The fiche stand saves one stand's row alone, where Ouvertures › Saisir
     * sends every stand it shows: both must leave the same openings behind —
     * that stand's row as typed, and every other row as it was.
     */
    @Test
    void aStandSavedAloneFromItsFicheLeavesTheSameOpeningsAsTheWholeGrid() {
        seedScenario();
        JsonPath avant = readOpenings();
        String standId = avant.getString("stands[0].standId");
        // Ids are drawn anew by every import: a stand is followed by its name.
        String standNom = avant.getString("stands[0].nom");

        given().contentType("application/json")
                .body(Map.of("stands", List.of(Map.of("standId", standId, "cellules", cellsClosedOnFirst(avant)))))
                .when()
                .put("/api/ouvertures-stands/grille")
                .then()
                .statusCode(200);
        JsonPath seul = readOpenings();

        seedScenario();
        JsonPath relu = readOpenings();
        List<Map<String, Object>> tous = new java.util.ArrayList<>();
        int nombreStands = relu.getList("stands").size();
        for (int index = 0; index < nombreStands; index++) {
            boolean saisi = standNom.equals(relu.getString("stands[" + index + "].nom"));
            tous.add(Map.of(
                    "standId",
                    relu.getString("stands[" + index + "].standId"),
                    "cellules",
                    saisi ? cellsClosedOnFirst(relu) : cellsAsRead(relu, index)));
        }
        given().contentType("application/json")
                .body(Map.of("stands", tous))
                .when()
                .put("/api/ouvertures-stands/grille")
                .then()
                .statusCode(200);
        JsonPath grille = readOpenings();

        assertThat(openingsOf(seul)).isEqualTo(openingsOf(grille));
        assertThat(seul.getInt("postesTotal")).isEqualTo(grille.getInt("postesTotal"));
        // The stand's row moved, and the other rows are those its save found.
        Map<String, List<Object>> apresSeul = openingsOf(seul);
        assertThat(apresSeul.get(standNom)).isNotEqualTo(openingsOf(avant).get(standNom));
        openingsOf(avant).forEach((nom, cellules) -> {
            if (!nom.equals(standNom)) {
                assertThat(apresSeul.get(nom)).as(nom).isEqualTo(cellules);
            }
        });
    }

    private static JsonPath readOpenings() {
        return given().when()
                .get("/api/ouvertures-stands")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath();
    }

    /** Closed on the first timeslot, three people on every other one. */
    private static List<Map<String, Object>> cellsClosedOnFirst(JsonPath rapport) {
        List<Integer> creneauIds = rapport.getList("jours.creneaux.id.flatten()", Integer.class).stream()
                .distinct()
                .toList();
        List<Map<String, Object>> cellules = new java.util.ArrayList<>();
        for (int index = 0; index < creneauIds.size(); index++) {
            Map<String, Object> cellule = new java.util.LinkedHashMap<>();
            cellule.put("creneauId", creneauIds.get(index));
            cellule.put("effectif", index == 0 ? null : 3);
            cellules.add(cellule);
        }
        return cellules;
    }

    /**
     * One stand's cells as the grid read them, the way the Saisir screen sends
     * a row: each with its column's bounds, since a timeslot a stand's windows
     * cut is several columns.
     */
    private static List<Map<String, Object>> cellsAsRead(JsonPath rapport, int standIndex) {
        Map<String, Map<String, Object>> colonnes = new java.util.HashMap<>();
        List<Map<String, Object>> joursGrille = rapport.getList("jours");
        for (Map<String, Object> jour : joursGrille) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> creneaux = (List<Map<String, Object>>) jour.get("creneaux");
            for (Map<String, Object> colonne : creneaux) {
                colonnes.put(jour.get("date") + "#" + colonne.get("id") + "#" + colonne.get("tranche"), colonne);
            }
        }
        List<Map<String, Object>> cellules = new java.util.ArrayList<>();
        List<Map<String, Object>> jours = rapport.getList("stands[" + standIndex + "].jours");
        for (Map<String, Object> jour : jours) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> creneaux = (List<Map<String, Object>>) jour.get("creneaux");
            for (Map<String, Object> cellule : creneaux) {
                Map<String, Object> colonne =
                        colonnes.get(jour.get("date") + "#" + cellule.get("creneauId") + "#" + cellule.get("tranche"));
                Map<String, Object> saisie = new java.util.LinkedHashMap<>();
                saisie.put("creneauId", cellule.get("creneauId"));
                saisie.put(
                        "heureDebut", String.valueOf(colonne.get("heureDebut")).substring(0, 5));
                saisie.put("heureFin", String.valueOf(colonne.get("heureFin")).substring(0, 5));
                saisie.put("effectif", cellule.get("effectif"));
                cellules.add(saisie);
            }
        }
        return cellules;
    }

    /** Each stand's effective headcounts, cell by cell and by name: what a solve would be given. */
    private static Map<String, List<Object>> openingsOf(JsonPath rapport) {
        Map<String, List<Object>> parStand = new java.util.TreeMap<>();
        int nombreStands = rapport.getList("stands").size();
        for (int index = 0; index < nombreStands; index++) {
            parStand.put(
                    rapport.getString("stands[" + index + "].nom"),
                    rapport.getList("stands[" + index + "].jours.creneaux.effectif.flatten()"));
        }
        return parStand;
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

    /**
     * {@code GET /api/ouvertures-stands/couches}: the combined calendar answers
     * on an edition never solved, with one cell per stand and day of the report.
     */
    @Test
    void theCombinedCalendarAnswersBeforeAnySolveWithOneCellPerStandAndDay() {
        seedScenario();
        JsonPath rapport = given().when()
                .get("/api/ouvertures-stands")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath();
        String premier = rapport.getString("jours[0].date");

        JsonPath couches = given().queryParam("du", premier)
                .queryParam("au", premier)
                .when()
                .get("/api/ouvertures-stands/couches")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath();

        assertThat(couches.getList("jours.date")).containsExactly(premier);
        assertThat(couches.getList("stands.standId")).isEqualTo(rapport.getList("stands.standId"));
        assertThat(couches.getList("stands[0].jours")).hasSize(1);
        assertThat(couches.getString("stands[0].jours[0].source")).isIn("DEFAUT", "REGLE", "EXCEPTION");
        assertThat(couches.getList("jours[0].vacations")).isNotEmpty();
    }

    @Test
    void theCombinedCalendarRefusesAnInvertedOrUnreadableRange() {
        given().queryParam("du", "2026-07-10")
                .queryParam("au", "2026-07-01")
                .when()
                .get("/api/ouvertures-stands/couches")
                .then()
                .statusCode(400);
        given().queryParam("du", "demain")
                .when()
                .get("/api/ouvertures-stands/couches")
                .then()
                .statusCode(400);
    }

    /**
     * The screen pages by seven <em>event</em> days, which may lie months
     * apart: a range wider than any calendar cap must still answer, with the
     * event days inside it and nothing in between.
     */
    @Test
    void theCombinedCalendarServesEventDaysMonthsApart() {
        given().when().post("/api/planning/reset").then().statusCode(200);
        for (String creneau : List.of(
                "{\"jour\":1,\"date\":\"2026-01-10\",\"heureDebut\":\"10:00:00\",\"heureFin\":\"12:00:00\"}",
                "{\"jour\":2,\"date\":\"2026-06-20\",\"heureDebut\":\"10:00:00\",\"heureFin\":\"12:00:00\"}")) {
            given().contentType("application/json")
                    .body(creneau)
                    .when()
                    .post("/api/creneaux")
                    .then()
                    .statusCode(200);
        }

        JsonPath couches = given().queryParam("du", "2026-01-10")
                .queryParam("au", "2026-06-20")
                .when()
                .get("/api/ouvertures-stands/couches")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath();

        assertThat(couches.getList("jours.date")).containsExactly("2026-01-10", "2026-06-20");

        // Leave a coherent dataset behind for the other test classes.
        seedScenario();
    }

    private static void seedScenario() {
        given().when().post("/api/planning/reset").then().statusCode(200);
        given().when()
                .post("/api/reference-data/import-scenario?name=scenario.yml")
                .then()
                .statusCode(200);
    }
}
