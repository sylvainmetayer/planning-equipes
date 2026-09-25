package dev.sylvain.planning.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The history end to end (issue #406): an action leaves a line, a line says
 * which fields moved, and nothing nominative reaches the table.
 */
@QuarkusTest
class HistoriqueResourceTest {

    @Test
    void aWriteLeavesALineNamingWhatItTouched() {
        given().contentType(ContentType.JSON)
                .body("""
                        {"id":"HIST-A1","prenom":"Alice","nom":"Martin","dateNaissance":"1990-01-01"}""")
                .when()
                .post("/api/animateurs")
                .then()
                .statusCode(200);

        given().contentType(ContentType.JSON)
                .body("""
                        {"id":"HIST-A1","prenom":"Alice","nom":"Durand","dateNaissance":"1990-01-01",
                         "email":"alice@example.org"}""")
                .when()
                .put("/api/animateurs/HIST-A1")
                .then()
                .statusCode(200);

        given().when()
                .get("/api/historique")
                .then()
                .statusCode(200)
                // The creation and the edit, newest first.
                .body("find { it.action == 'ANIMATEUR_MODIFIE' }.entiteId", equalTo("HIST-A1"))
                .body("find { it.action == 'ANIMATEUR_MODIFIE' }.libelle", equalTo("Fiche animateur modifiée"))
                .body("find { it.action == 'ANIMATEUR_MODIFIE' }.resultat", equalTo("SUCCES"))
                .body("find { it.action == 'ANIMATEUR_MODIFIE' }.champs", hasItem("nom"))
                .body("find { it.action == 'ANIMATEUR_MODIFIE' }.champs", hasItem("email"))
                // Untouched fields stay out: the screen re-sends the whole fiche.
                .body("find { it.action == 'ANIMATEUR_MODIFIE' }.champs", not(hasItem("prenom")))
                .body("find { it.action == 'ANIMATEUR_CREE' }.entiteId", equalTo("HIST-A1"));

        given().when().delete("/api/animateurs/HIST-A1").then().statusCode(204);
    }

    /**
     * The rule the whole table is built on: a line carries identifiers, and the
     * identity is joined when it is read. So a fiche deleted since leaves a
     * line that still says what happened and no longer says to whom.
     */
    @Test
    void nothingNominativeIsStoredAndTheNameIsJoinedOnRead() {
        given().contentType(ContentType.JSON)
                .body("""
                        {"id":"HIST-A2","prenom":"Bérénice","nom":"Dupont","dateNaissance":"1990-01-01"}""")
                .when()
                .post("/api/animateurs")
                .then()
                .statusCode(200);

        given().when()
                .get("/api/historique")
                .then()
                .statusCode(200)
                .body("find { it.entiteId == 'HIST-A2' }.entiteNom", equalTo("Bérénice Dupont"));

        given().when().delete("/api/animateurs/HIST-A2").then().statusCode(204);

        // The line survives; the name does not.
        given().when()
                .get("/api/historique")
                .then()
                .statusCode(200)
                .body(
                        "find { it.action == 'ANIMATEUR_SUPPRIME' && it.entiteId == 'HIST-A2' }.entiteNom",
                        equalTo(null));
    }

    /** A refused action is a fact worth keeping — often the one being looked for. */
    @Test
    void aRefusedActionIsRecordedAsRefused() {
        given().contentType(ContentType.JSON)
                .body("""
                        {"id":"HIST-INCONNU","prenom":"X","nom":"Y","dateNaissance":"1990-01-01"}""")
                .when()
                .put("/api/animateurs/HIST-INCONNU")
                .then()
                .statusCode(404)
                // The contract of a refusal: a status and one sentence, the one
                // the screen shows. A 404 used to come back empty (#447).
                .body("message", equalTo("Animateur inconnu : HIST-INCONNU"));

        given().when()
                .get("/api/historique")
                .then()
                .statusCode(200)
                .body("find { it.entiteId == 'HIST-INCONNU' }.resultat", equalTo("REFUS"))
                .body("find { it.entiteId == 'HIST-INCONNU' }.statut", equalTo(404));
    }

    /** A read changes nothing and leaves nothing: the history is not an access log. */
    @Test
    void plainReadsLeaveNoTrace() {
        given().when().get("/api/historique").then().statusCode(200);
        given().when().get("/api/animateurs").then().statusCode(200);

        given().when()
                .get("/api/historique")
                .then()
                .statusCode(200)
                .body("findAll { it.action == 'ANIMATEURS_LUS' }", empty());
    }

    /**
     * One route, two actions. The catalogue keys on the method, so without the
     * resource stating the direction the journal would record « Contrainte
     * activée » for a deactivation — asserting the opposite of what happened.
     */
    @Test
    void aToggleRecordsTheDirectionItWentIn() {
        given().contentType(ContentType.JSON)
                .body("{\"actif\":false}")
                .when()
                .put("/api/constraints/equilibrerCharge")
                .then()
                .statusCode(200);

        given().when()
                .get("/api/historique")
                .then()
                .statusCode(200)
                .body("find { it.entiteId == 'equilibrerCharge' }.action", equalTo("CONTRAINTE_DESACTIVEE"))
                .body("find { it.entiteId == 'equilibrerCharge' }.libelle", equalTo("Contrainte désactivée"));

        given().contentType(ContentType.JSON)
                .body("{\"actif\":true}")
                .when()
                .put("/api/constraints/equilibrerCharge")
                .then()
                .statusCode(200);

        given().when()
                .get("/api/historique")
                .then()
                .statusCode(200)
                .body("find { it.entiteId == 'equilibrerCharge' }.action", equalTo("CONTRAINTE_ACTIVEE"));
    }

    /**
     * The summary the solver screen shows under « des données de référence ont
     * été modifiées depuis cette résolution » : how much moved, of what kind,
     * and the last lines — everything before {@code depuis} left out, and so is
     * everything that changes no data.
     */
    @Test
    void theChangesSinceAMomentAreCountedPerFamilyAndDated() {
        String avant = Instant.now().toString();

        given().contentType(ContentType.JSON)
                .body("""
                        {"id":"HIST-C1","prenom":"Chloé","nom":"Bernard","dateNaissance":"1990-01-01"}""")
                .when()
                .post("/api/animateurs")
                .then()
                .statusCode(200);
        // Reading changes nothing, and an export leaves with a copy of the plan
        // without moving it: neither belongs in this summary.
        given().when().get("/api/animateurs").then().statusCode(200);

        given().queryParam("depuis", avant)
                .when()
                .get("/api/historique/changements")
                .then()
                .statusCode(200)
                .body("total", greaterThan(0))
                .body("parEntite.find { it.entite == 'ANIMATEUR' }.nombre", greaterThan(0))
                .body("dernieres.find { it.entiteId == 'HIST-C1' }.libelle", equalTo("Animateur ajouté"))
                .body("dernieres.find { it.entiteId == 'HIST-C1' }.entiteNom", equalTo("Chloé Bernard"))
                .body("dernieres.action", everyItem(not(equalTo("ANIMATEURS_LUS"))));

        // Asked from now on, the same creation is behind us.
        given().queryParam("depuis", Instant.now().toString())
                .when()
                .get("/api/historique/changements")
                .then()
                .statusCode(200)
                .body("total", equalTo(0))
                .body("parEntite", empty())
                .body("dernieres", empty());

        given().when().delete("/api/animateurs/HIST-C1").then().statusCode(204);
    }

    /**
     * Every download that leaves with people's data writes a line (« qui a
     * sorti la liste des bénévoles, et quand ? »): the admin's, with the
     * status it ended on — and none of them counts as a change since the
     * résolution, since a copy leaving moves nothing a solve is given.
     */
    @Test
    void everyAdminDownloadLeavesAnExportLineThatChangesNoData() {
        String avant = Instant.now().toString();
        Map<String, String> telechargements = new LinkedHashMap<>();
        telechargements.put("/api/reference-data/export-csv?typologies=true&animateurs=true", "EXPORT_REFERENTIELS");
        telechargements.put("/api/planning/export-scenario", "EXPORT_SCENARIO");
        telechargements.put("/api/planning/export/pdf/global", "EXPORT_PDF_GLOBAL");
        telechargements.put("/api/planning/equite/export", "EXPORT_EQUITE");
        telechargements.put("/api/planning/publication/export", "EXPORT_RELECTURE");
        telechargements.put("/api/animateurs/competences/export", "EXPORT_COMPETENCES");
        telechargements.put("/api/pauses/intendance/export", "EXPORT_INTENDANCE");
        telechargements.put("/api/formation/export", "EXPORT_FORMATION");
        telechargements.put("/api/database/export", "EXPORT_BASE");

        Map<String, Integer> statuts = new LinkedHashMap<>();
        telechargements.forEach((chemin, action) ->
                statuts.put(action, given().when().get(chemin).then().extract().statusCode()));
        assertThat(statuts)
                .as("les téléchargements eux-mêmes")
                .allSatisfy((action, statut) -> assertThat(statut).as(action).isEqualTo(200));

        JsonPath historique = given().queryParam("limite", 500)
                .when()
                .get("/api/historique")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath();
        statuts.forEach((action, statut) -> {
            // Newest first: the first line of that action is the one just written.
            Map<String, Object> ligne = historique.getMap("find { it.action == '" + action + "' }");
            assertThat(ligne)
                    .as("ligne d'historique de %s", action)
                    .isNotNull()
                    .as(action)
                    .containsEntry("acteur", "ADMIN")
                    .containsEntry("resultat", "SUCCES")
                    .containsEntry("statut", statut);
            // What left, and when — never what was in it. The referentials'
            // archive names which ones it carried, the others name nothing.
            assertThat((List<?>) ligne.get("champs"))
                    .as(action)
                    .isEqualTo(action.equals("EXPORT_REFERENTIELS") ? List.of("typologies", "animateurs") : List.of());
        });

        given().queryParam("depuis", avant)
                .when()
                .get("/api/historique/changements")
                .then()
                .statusCode(200)
                .body("total", equalTo(0))
                .body("dernieres", empty());
    }

    /** A refused export is written too, as refused: asking for nothing is a 400. */
    @Test
    void aRefusedExportIsRecordedAsRefused() {
        given().when().get("/api/reference-data/export-csv").then().statusCode(400);

        given().when()
                .get("/api/historique")
                .then()
                .statusCode(200)
                .body("find { it.action == 'EXPORT_REFERENTIELS' }.resultat", equalTo("REFUS"))
                .body("find { it.action == 'EXPORT_REFERENTIELS' }.statut", equalTo(400));
    }

    /**
     * The « Exports » filter is applied by the server, over the whole
     * retention: an export buried under more recent edits than one page holds
     * is still found, and nothing but exports comes back.
     */
    @Test
    void theExportsFilterSearchesTheDatabaseNotTheLastPage() {
        given().when().get("/api/reference-data/export-csv?stands=true").then().statusCode(200);
        // More recent lines than the page asked for below, none of them an export.
        for (int i = 0; i < 3; i++) {
            given().when().get("/api/reference-data/export-csv").then().statusCode(400);
            given().contentType(ContentType.JSON)
                    .body("{\"id\":\"HIST-X" + i
                            + "\",\"prenom\":\"X\",\"nom\":\"Y\",\"dateNaissance\":\"1990-01-01\"}")
                    .when()
                    .post("/api/animateurs")
                    .then()
                    .statusCode(200);
        }

        List<String> exportCodes = given().when()
                .get("/api/historique/actions")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getList("findAll { it.export }.code", String.class);
        List<Map<String, Object>> page = given().queryParam("nature", "exports")
                .queryParam("limite", 4)
                .when()
                .get("/api/historique")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getList("$");
        assertThat(page)
                .hasSize(4)
                .allSatisfy(ligne -> assertThat(exportCodes).contains((String) ligne.get("action")))
                .as("l'export réussi, derrière des lignes plus récentes que la page")
                .anySatisfy(ligne -> assertThat(ligne).containsEntry("champs", List.of("stands")));

        for (int i = 0; i < 3; i++) {
            given().when().delete("/api/animateurs/HIST-X" + i).then().statusCode(204);
        }
    }

    @Test
    void anUnknownNatureIsRefused() {
        given().queryParam("nature", "toutes")
                .when()
                .get("/api/historique")
                .then()
                .statusCode(400);
    }

    @Test
    void theChangesRefuseAMomentTheyCannotRead() {
        given().when().get("/api/historique/changements").then().statusCode(400);
        given().queryParam("depuis", "hier")
                .when()
                .get("/api/historique/changements")
                .then()
                .statusCode(400);
    }

    @Test
    void theActionInventoryIsServedForTheScreensFilter() {
        given().when()
                .get("/api/historique/actions")
                .then()
                .statusCode(200)
                .body("size()", greaterThan(50))
                .body("libelle", everyItem(not(empty())))
                .body("find { it.code == 'ANIMATEUR_CREE' }.libelle", equalTo("Animateur ajouté"))
                .body("find { it.code == 'ANIMATEUR_CREE' }.export", equalTo(false))
                .body("find { it.code == 'EXPORT_REFERENTIELS' }.export", equalTo(true))
                .body("find { it.code == 'TELECHARGEMENT_ESPACE_PDF' }.export", equalTo(true))
                .body("find { it.code == 'EXPORT_COMPETENCES' }.entite", equalTo("PLANNING"));
    }
}
