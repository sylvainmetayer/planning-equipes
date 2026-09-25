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
 * {@code PUT /api/animateurs/competences/grille}: the appreciation grid saved
 * row by row with its own precondition — the only way appreciations are
 * entered in bulk, the grid being no longer exchanged as a file.
 *
 * <p>Ids are generated (ADR 0050): the scenario's animateurs are found by
 * their e-mail and its typologies by their code, and the tests speak the ids
 * the API gives them — which is also what the grid carries.</p>
 */
@QuarkusTest
class CompetencesGrilleResourceTest {

    // Ids of the scenario's three animateurs and of its two typologies, as this run's import gave them.
    private String a1;
    private String a2;
    private String a3;
    private String strategie;
    private String hommeJeu;

    private void seedScenario() {
        given().when().post("/api/planning/reset").then().statusCode(200);
        given().when()
                .post("/api/reference-data/import-scenario?name=scenario.yml")
                .then()
                .statusCode(200);
        a1 = animateurIdOfEmail("A1@example.org");
        a2 = animateurIdOfEmail("A2@example.org");
        a3 = animateurIdOfEmail("A3@example.org");
        strategie = typologieIdOfCode("STRATEGIE");
        hommeJeu = typologieIdOfCode("HOMME_JEU");
    }

    private static String animateurIdOfEmail(String email) {
        String id = animateurs().getString("find { it.email == '" + email + "' }.id");
        assertThat(id).as("animateur of e-mail %s", email).isNotNull();
        return id;
    }

    private static String typologieIdOfCode(String code) {
        String id = given().when()
                .get("/api/typologies")
                .then()
                .statusCode(200)
                .extract()
                .path("find { it.code == '" + code + "' }.id");
        assertThat(id).as("typologie of code %s", code).isNotNull();
        return id;
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
        String stamp = stampOf(a1);
        assertThat(stamp).as("the grid reads the stamp it will send back").isNotNull();

        given().contentType("application/json")
                .body(Map.of("animateurs", List.of(row(a1, stamp, Map.of(hommeJeu, "REFERENT")))))
                .when()
                .put("/api/animateurs/competences/grille")
                .then()
                .statusCode(200)
                .body("animateurs.size()", equalTo(1))
                .body("animateurs[0].animateurId", equalTo(a1))
                .body("animateurs[0].resultat", equalTo("WRITTEN"))
                .body("animateurs[0].modifieLe", notNullValue())
                .body("animateurs[0].message", nullValue());

        assertThat(competencesOf(a1)).containsExactlyEntriesOf(Map.of(hommeJeu, "REFERENT"));
        // The identity travelled untouched.
        assertThat(animateurs().getString("find { it.id == '" + a1 + "' }.nom")).isEqualTo("Referente");
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
        String stampA1 = stampOf(a1);
        String stampA2 = stampOf(a2);

        Map<String, Object> fiche = animateurs().getMap("find { it.id == '" + a2 + "' }");
        fiche.put("nom", "Renommé ailleurs");
        fiche.put("modifieLe", null);
        given().contentType("application/json")
                .body(fiche)
                .when()
                .put("/api/animateurs/" + a2)
                .then()
                .statusCode(200);

        JsonPath rapport = given().contentType("application/json")
                .body(Map.of(
                        "animateurs",
                        List.of(
                                row(a1, stampA1, Map.of(strategie, "AUTONOME")),
                                row(a2, stampA2, Map.of(strategie, "REFERENT")))))
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
        assertThat(competencesOf(a1)).containsExactlyEntriesOf(Map.of(strategie, "AUTONOME"));
        assertThat(competencesOf(a2))
                .as("nothing of the stale row is written")
                .containsEntry(strategie, "AUTONOME")
                .containsEntry(hommeJeu, "DEBUTANT");
        assertThat(rapport.getString("animateurs[1].modifieLe")).isEqualTo(stampOf(a2));

        given().contentType("application/json")
                .body(Map.of("animateurs", List.of(row(a2, null, Map.of(strategie, "REFERENT")))))
                .when()
                .put("/api/animateurs/competences/grille")
                .then()
                .statusCode(200)
                .body("animateurs[0].resultat", equalTo("WRITTEN"));
        assertThat(competencesOf(a2)).containsExactlyEntriesOf(Map.of(strategie, "REFERENT"));
    }

    /** An unknown typologie or an unknown animateur costs its own row, not the request. */
    @Test
    void anUnknownTypologieOrAnimateurIsRefusedOnItsRowOnly() {
        seedScenario();

        given().contentType("application/json")
                .body(Map.of(
                        "animateurs",
                        List.of(
                                row(a3, stampOf(a3), Map.of("INCONNUE", "REFERENT")),
                                row("ZZ", null, Map.of(strategie, "REFERENT")),
                                row(a1, stampOf(a1), Map.of()))))
                .when()
                .put("/api/animateurs/competences/grille")
                .then()
                .statusCode(200)
                .body("animateurs[0].resultat", equalTo("REJECTED"))
                .body("animateurs[0].message", containsString("INCONNUE"))
                .body("animateurs[1].resultat", equalTo("REJECTED"))
                .body("animateurs[1].message", containsString("ZZ"))
                .body("animateurs[2].resultat", equalTo("WRITTEN"));

        assertThat(competencesOf(a3)).containsExactlyEntriesOf(Map.of(strategie, "DEBUTANT"));
        assertThat(competencesOf(a1)).isEmpty();
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
}
