package dev.sylvain.planning.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * {@code /api/animateurs/competences/*}: the appreciation grid saved row by
 * row with its own precondition, exported as a CSV, and imported under the
 * partial contract — a blank cell leaves the stored appreciation alone.
 */
@QuarkusTest
class CompetencesGrilleResourceTest {

    private static void seedScenario() {
        given().when().post("/api/planning/reset").then().statusCode(200);
        given().when()
                .post("/api/reference-data/import-scenario?name=scenario.yml")
                .then()
                .statusCode(200);
    }

    private static Map<String, Object> request(String contenu) {
        return Map.of("fileName", "grille-competences.csv", "content", contenu);
    }

    private static JsonPath animateurs() {
        return given().when()
                .get("/api/animateurs")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath();
    }

    private static String stampOf(String id) {
        return animateurs().getString("find { it.id == '" + id + "' }.modifieLe");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> competencesOf(String id) {
        return animateurs().getMap("find { it.id == '" + id + "' }.competences", String.class, String.class);
    }

    private static Map<String, Object> row(String id, String modifieLe, Map<String, String> competences) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("animateurId", id);
        row.put("modifieLe", modifieLe);
        row.put("competences", competences);
        return row;
    }

    /* --------------------------------- grid --------------------------------- */

    /** The row replaces the fiche's whole map: the appreciation left out is gone, the one typed is there. */
    @Test
    void aSavedRowIsReadBackAsTyped() {
        seedScenario();
        String stamp = stampOf("A1");
        assertThat(stamp).as("the grid reads the stamp it will send back").isNotNull();

        given().contentType("application/json")
                .body(Map.of("animateurs", List.of(row("A1", stamp, Map.of("HOMME_JEU", "REFERENT")))))
                .when()
                .put("/api/animateurs/competences/grille")
                .then()
                .statusCode(200)
                .body("animateurs.size()", equalTo(1))
                .body("animateurs[0].animateurId", equalTo("A1"))
                .body("animateurs[0].resultat", equalTo("WRITTEN"))
                .body("animateurs[0].modifieLe", notNullValue())
                .body("animateurs[0].message", nullValue());

        assertThat(competencesOf("A1")).containsExactlyEntriesOf(Map.of("HOMME_JEU", "REFERENT"));
        // The identity travelled untouched.
        assertThat(animateurs().getString("find { it.id == 'A1' }.nom")).isEqualTo("Referente");
    }

    /**
     * Two rows, one of them written by another session since the grid was
     * read: that one is refused alone, as the per-row form of the fiche's
     * {@code 409}, and the other is written. Sent back without its
     * precondition, the stale row then goes through — a knowing overwrite.
     */
    @Test
    void aFicheWrittenSinceTheReadIsRefusedAloneAndTheOthersAreWritten() {
        seedScenario();
        String stampA1 = stampOf("A1");
        String stampA2 = stampOf("A2");

        Map<String, Object> fiche = animateurs().getMap("find { it.id == 'A2' }");
        fiche.put("nom", "Renommé ailleurs");
        fiche.put("modifieLe", null);
        given().contentType("application/json")
                .body(fiche)
                .when()
                .put("/api/animateurs/A2")
                .then()
                .statusCode(200);

        JsonPath rapport = given().contentType("application/json")
                .body(Map.of(
                        "animateurs",
                        List.of(
                                row("A1", stampA1, Map.of("STRATEGIE", "AUTONOME")),
                                row("A2", stampA2, Map.of("STRATEGIE", "REFERENT")))))
                .when()
                .put("/api/animateurs/competences/grille")
                .then()
                .statusCode(200)
                .body("animateurs[0].resultat", equalTo("WRITTEN"))
                .body("animateurs[1].resultat", equalTo("STALE"))
                .body("animateurs[1].message", containsString("autre session"))
                .body("animateurs[1].modifieLe", notNullValue())
                .extract()
                .jsonPath();
        assertThat(competencesOf("A1")).containsExactlyEntriesOf(Map.of("STRATEGIE", "AUTONOME"));
        assertThat(competencesOf("A2"))
                .as("nothing of the stale row is written")
                .containsEntry("STRATEGIE", "AUTONOME")
                .containsEntry("HOMME_JEU", "DEBUTANT");
        assertThat(rapport.getString("animateurs[1].modifieLe")).isEqualTo(stampOf("A2"));

        given().contentType("application/json")
                .body(Map.of("animateurs", List.of(row("A2", null, Map.of("STRATEGIE", "REFERENT")))))
                .when()
                .put("/api/animateurs/competences/grille")
                .then()
                .statusCode(200)
                .body("animateurs[0].resultat", equalTo("WRITTEN"));
        assertThat(competencesOf("A2")).containsExactlyEntriesOf(Map.of("STRATEGIE", "REFERENT"));
    }

    /** An unknown typologie or an unknown animateur costs its own row, not the request. */
    @Test
    void anUnknownTypologieOrAnimateurIsRefusedOnItsRowOnly() {
        seedScenario();

        given().contentType("application/json")
                .body(Map.of(
                        "animateurs",
                        List.of(
                                row("A3", stampOf("A3"), Map.of("INCONNUE", "REFERENT")),
                                row("ZZ", null, Map.of("STRATEGIE", "REFERENT")),
                                row("A1", stampOf("A1"), Map.of()))))
                .when()
                .put("/api/animateurs/competences/grille")
                .then()
                .statusCode(200)
                .body("animateurs[0].resultat", equalTo("REJECTED"))
                .body("animateurs[0].message", containsString("INCONNUE"))
                .body("animateurs[1].resultat", equalTo("REJECTED"))
                .body("animateurs[1].message", containsString("ZZ"))
                .body("animateurs[2].resultat", equalTo("WRITTEN"));

        assertThat(competencesOf("A3")).containsExactlyEntriesOf(Map.of("STRATEGIE", "DEBUTANT"));
        assertThat(competencesOf("A1")).isEmpty();
    }

    @Test
    void anEmptyBodyWritesNothingAndAnswersAnEmptyReport() {
        seedScenario();
        given().contentType("application/json")
                .body("{}")
                .when()
                .put("/api/animateurs/competences/grille")
                .then()
                .statusCode(200)
                .body("animateurs.size()", equalTo(0));
    }

    /**
     * The grid has one row per animateur: a body longer than the import's own
     * ceiling is not a grid, and answering it row by row would mean writing
     * thousands of fiches before saying so.
     */
    @Test
    void aBodyLongerThanTheImportCeilingIsRefusedAsAWhole() {
        seedScenario();
        List<Map<String, Object>> lignes = new java.util.ArrayList<>();
        for (int index = 0; index <= 2_000; index++) {
            Map<String, Object> ligne = new LinkedHashMap<>();
            ligne.put("animateurId", "A" + index);
            ligne.put("competences", Map.of());
            lignes.add(ligne);
        }
        given().contentType("application/json")
                .body(Map.of("animateurs", lignes))
                .when()
                .put("/api/animateurs/competences/grille")
                .then()
                .statusCode(400)
                .body("message", containsString("Trop de lignes"));
    }

    /* ---------------------------------- CSV --------------------------------- */

    /** The export re-imports as is: every row is known and states nothing new, nothing is written by the preview. */
    @Test
    void theExportReimportsAsUnchanged() {
        seedScenario();
        String export = given().when()
                .get("/api/animateurs/competences/export")
                .then()
                .statusCode(200)
                .contentType(containsString("text/csv"))
                .header("Content-Disposition", containsString("grille-competences.csv"))
                .extract()
                .asString();
        // The byte order mark, so Excel reads the file as UTF-8 once on disk;
        // the parser strips it, which the clean re-import below proves.
        assertThat(export).startsWith("\uFEFFanimateur;");
        assertThat(export.lines().findFirst().orElseThrow())
                .contains("STRATEGIE")
                .contains("HOMME_JEU");
        assertThat(export).contains("\nA1;").doesNotContain("Alice").doesNotContain("Referente");

        JsonPath rapport = given().contentType("application/json")
                .body(request(export))
                .when()
                .post("/api/animateurs/competences/import-grille/analyse")
                .then()
                .statusCode(200)
                .body("applied", equalTo(false))
                .body("rejected", equalTo(0))
                .body("accepted", equalTo(0))
                .extract()
                .jsonPath();
        assertThat(rapport.getInt("unchanged"))
                .isEqualTo(rapport.getInt("total"))
                .isPositive();
        assertThat(rapport.getList("columns.typologieId", String.class)).doesNotContainNull();
    }

    /**
     * The contract of the import: a blank cell leaves the stored appreciation
     * alone, a level adds or replaces, an unknown animateur costs its row, an
     * unknown column is ignored and listed — and the preview writes nothing.
     */
    @Test
    void theImportAddsAndUpdatesWithoutRemoving() {
        seedScenario();
        String csv = "animateur;STRATEGIE;HOMME_JEU;montage\nA2;;REFERENT;x\nINCONNU;REFERENT;;\n";

        given().contentType("application/json")
                .body(request(csv))
                .when()
                .post("/api/animateurs/competences/import-grille/analyse")
                .then()
                .statusCode(200)
                .body("applied", equalTo(false))
                .body("accepted", equalTo(1))
                .body("rejected", equalTo(1))
                .body("columns[2].typologieId", nullValue())
                .body("columns[2].reason", containsString("ignorée"))
                .body("rows[0].action", equalTo("UPDATED"))
                .body("rows[0].cellules", equalTo(1))
                .body("rows[1].action", equalTo("REJECTED"))
                .body("rows[1].reasons[0]", containsString("INCONNU"));
        assertThat(competencesOf("A2")).as("the preview writes nothing").containsEntry("HOMME_JEU", "DEBUTANT");

        given().contentType("application/json")
                .body(request(csv))
                .when()
                .post("/api/animateurs/competences/import-grille")
                .then()
                .statusCode(200)
                .body("applied", equalTo(true))
                .body("accepted", equalTo(1));

        assertThat(competencesOf("A2"))
                .as("the blank cell left STRATEGIE as it was")
                .containsExactlyInAnyOrderEntriesOf(Map.of("STRATEGIE", "AUTONOME", "HOMME_JEU", "REFERENT"));
        assertThat(animateurs().getList("id", String.class)).doesNotContain("INCONNU");
    }

    @Test
    void anUnknownLevelRefusesTheRowAndAnEmptyAcceptedSetRefusesTheWrite() {
        seedScenario();
        String csv = "animateur;STRATEGIE\nA1;expert\n";

        given().contentType("application/json")
                .body(request(csv))
                .when()
                .post("/api/animateurs/competences/import-grille/analyse")
                .then()
                .statusCode(200)
                .body("rejected", equalTo(1))
                .body("rows[0].action", equalTo("REJECTED"))
                .body("rows[0].reasons[0]", containsString("n'est pas un niveau"));

        given().contentType("application/json")
                .body(request(csv))
                .when()
                .post("/api/animateurs/competences/import-grille")
                .then()
                .statusCode(400)
                .body("message", containsString("Aucune ligne acceptée"));
        assertThat(competencesOf("A1")).containsEntry("STRATEGIE", "REFERENT");
    }

    @Test
    void aFileNamingNoTypologieAndASpreadsheetAreRefused() {
        seedScenario();
        given().contentType("application/json")
                .body(request("animateur;commentaire\nA1;bonjour\n"))
                .when()
                .post("/api/animateurs/competences/import-grille/analyse")
                .then()
                .statusCode(400)
                .body("message", containsString("Aucune colonne"));

        given().contentType("application/json")
                .body(Map.of("fileName", "grille.xlsx", "content", "PK not a csv"))
                .when()
                .post("/api/animateurs/competences/import-grille/analyse")
                .then()
                .statusCode(400)
                .body("message", containsString("seul le CSV est lu"));
    }
}
