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
import java.util.ArrayList;
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
        String id = given().header("X-Edition-Id", "E1")
                .contentType(ContentType.JSON)
                .body("""
                        {"prenom":"Alice","nom":"Martin","dateNaissance":"1990-01-01"}""")
                .when()
                .post("/api/animateurs")
                .then()
                .statusCode(200)
                .extract()
                .path("animateur.id");

        given().header("X-Edition-Id", "E1")
                .contentType(ContentType.JSON)
                .body("""
                        {"prenom":"Alice","nom":"Durand","dateNaissance":"1990-01-01",
                         "email":"alice@example.org"}""")
                .when()
                .put("/api/animateurs/" + id)
                .then()
                .statusCode(200);

        given().header("X-Edition-Id", "E1")
                .when()
                .get("/api/historique")
                .then()
                .statusCode(200)
                // The creation and the edit, newest first.
                .body("find { it.action == 'ANIMATEUR_MODIFIE' }.entiteId", equalTo(id))
                .body("find { it.action == 'ANIMATEUR_MODIFIE' }.libelle", equalTo("Fiche animateur modifiée"))
                .body("find { it.action == 'ANIMATEUR_MODIFIE' }.resultat", equalTo("SUCCES"))
                .body("find { it.action == 'ANIMATEUR_MODIFIE' }.champs", hasItem("nom"))
                .body("find { it.action == 'ANIMATEUR_MODIFIE' }.champs", hasItem("email"))
                // Untouched fields stay out: the screen re-sends the whole fiche.
                .body("find { it.action == 'ANIMATEUR_MODIFIE' }.champs", not(hasItem("prenom")))
                .body("find { it.action == 'ANIMATEUR_CREE' }.entiteId", equalTo(id));

        given().header("X-Edition-Id", "E1")
                .when()
                .delete("/api/animateurs/" + id)
                .then()
                .statusCode(204);
    }

    /**
     * The rule the whole table is built on: a line carries identifiers, and the
     * identity is joined when it is read. So a fiche deleted since leaves a
     * line that still says what happened and no longer says to whom.
     */
    @Test
    void nothingNominativeIsStoredAndTheNameIsJoinedOnRead() {
        String id = given().header("X-Edition-Id", "E1")
                .contentType(ContentType.JSON)
                .body("""
                        {"prenom":"Bérénice","nom":"Dupont","dateNaissance":"1990-01-01"}""")
                .when()
                .post("/api/animateurs")
                .then()
                .statusCode(200)
                .extract()
                .path("animateur.id");

        given().header("X-Edition-Id", "E1")
                .when()
                .get("/api/historique")
                .then()
                .statusCode(200)
                .body("find { it.entiteId == '" + id + "' }.entiteNom", equalTo("Bérénice Dupont"));

        given().header("X-Edition-Id", "E1")
                .when()
                .delete("/api/animateurs/" + id)
                .then()
                .statusCode(204);

        // The line survives; the name does not.
        given().header("X-Edition-Id", "E1")
                .when()
                .get("/api/historique")
                .then()
                .statusCode(200)
                .body(
                        "find { it.action == 'ANIMATEUR_SUPPRIME' && it.entiteId == '" + id + "' }.entiteNom",
                        equalTo(null));
    }

    /** A refused action is a fact worth keeping — often the one being looked for. */
    @Test
    void aRefusedActionIsRecordedAsRefused() {
        given().header("X-Edition-Id", "E1")
                .contentType(ContentType.JSON)
                .body("""
                        {"id":"HIST-INCONNU","prenom":"X","nom":"Y","dateNaissance":"1990-01-01"}""")
                .when()
                .put("/api/animateurs/HIST-INCONNU")
                .then()
                .statusCode(404)
                // The contract of a refusal: a status and one sentence, the one
                // the screen shows. A 404 used to come back empty (#447).
                .body("message", equalTo("Animateur inconnu : HIST-INCONNU"));

        given().header("X-Edition-Id", "E1")
                .when()
                .get("/api/historique")
                .then()
                .statusCode(200)
                .body("find { it.entiteId == 'HIST-INCONNU' }.resultat", equalTo("REFUS"))
                .body("find { it.entiteId == 'HIST-INCONNU' }.statut", equalTo(404));
    }

    /** A read changes nothing and leaves nothing: the history is not an access log. */
    @Test
    void plainReadsLeaveNoTrace() {
        given().header("X-Edition-Id", "E1")
                .when()
                .get("/api/historique")
                .then()
                .statusCode(200);
        given().header("X-Edition-Id", "E1")
                .when()
                .get("/api/animateurs")
                .then()
                .statusCode(200);

        given().header("X-Edition-Id", "E1")
                .when()
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
        given().header("X-Edition-Id", "E1")
                .contentType(ContentType.JSON)
                .body("{\"actif\":false}")
                .when()
                .put("/api/constraints/equilibrerCharge")
                .then()
                .statusCode(200);

        given().header("X-Edition-Id", "E1")
                .when()
                .get("/api/historique")
                .then()
                .statusCode(200)
                .body("find { it.entiteId == 'equilibrerCharge' }.action", equalTo("CONTRAINTE_DESACTIVEE"))
                .body("find { it.entiteId == 'equilibrerCharge' }.libelle", equalTo("Contrainte désactivée"));

        given().header("X-Edition-Id", "E1")
                .contentType(ContentType.JSON)
                .body("{\"actif\":true}")
                .when()
                .put("/api/constraints/equilibrerCharge")
                .then()
                .statusCode(200);

        given().header("X-Edition-Id", "E1")
                .when()
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

        String id = given().header("X-Edition-Id", "E1")
                .contentType(ContentType.JSON)
                .body("""
                        {"prenom":"Chloé","nom":"Bernard","dateNaissance":"1990-01-01"}""")
                .when()
                .post("/api/animateurs")
                .then()
                .statusCode(200)
                .extract()
                .path("animateur.id");
        // Reading changes nothing, and an export leaves with a copy of the plan
        // without moving it: neither belongs in this summary.
        given().header("X-Edition-Id", "E1")
                .when()
                .get("/api/animateurs")
                .then()
                .statusCode(200);

        given().header("X-Edition-Id", "E1")
                .queryParam("depuis", avant)
                .when()
                .get("/api/historique/changements")
                .then()
                .statusCode(200)
                .body("total", greaterThan(0))
                .body("parEntite.find { it.entite == 'ANIMATEUR' }.nombre", greaterThan(0))
                .body("dernieres.find { it.entiteId == '" + id + "' }.libelle", equalTo("Animateur ajouté"))
                .body("dernieres.find { it.entiteId == '" + id + "' }.entiteNom", equalTo("Chloé Bernard"))
                .body("dernieres.action", everyItem(not(equalTo("ANIMATEURS_LUS"))));

        // Asked from now on, the same creation is behind us.
        given().header("X-Edition-Id", "E1")
                .queryParam("depuis", Instant.now().toString())
                .when()
                .get("/api/historique/changements")
                .then()
                .statusCode(200)
                .body("total", equalTo(0))
                .body("parEntite", empty())
                .body("dernieres", empty());

        given().header("X-Edition-Id", "E1")
                .when()
                .delete("/api/animateurs/" + id)
                .then()
                .statusCode(204);
    }

    /**
     * Every download that leaves with people's data writes a line (« qui a
     * sorti la liste des bénévoles, et quand ? »): the admin's, with the
     * status it ended on — and none of them counts as a change since the
     * résolution, since a copy leaving moves nothing a solve is given.
     */
    @Test
    void everyAdminDownloadLeavesAnExportLineThatChangesNoData() {
        // The scenario export refuses an empty edition, and a class run
        // before this one may have cleared it: bring the referential along
        // rather than depend on the order the suite runs in.
        given().header("X-Edition-Id", "E1")
                .when()
                .post("/api/reference-data/import-scenario?name=scenario.yml")
                .then()
                .statusCode(200);
        String avant = Instant.now().toString();
        Map<String, String> telechargements = new LinkedHashMap<>();
        telechargements.put("/api/reference-data/export-csv?typologies=true&animateurs=true", "EXPORT_REFERENTIELS");
        telechargements.put("/api/planning/export-scenario", "EXPORT_SCENARIO");
        telechargements.put("/api/planning/export/pdf/global", "EXPORT_PDF_GLOBAL");
        telechargements.put("/api/planning/equite/export", "EXPORT_EQUITE");
        telechargements.put("/api/planning/publication/export", "EXPORT_RELECTURE");
        telechargements.put("/api/pauses/intendance/export", "EXPORT_INTENDANCE");
        telechargements.put("/api/formation/export", "EXPORT_FORMATION");
        telechargements.put("/api/database/export", "EXPORT_BASE");

        Map<String, Integer> statuts = new LinkedHashMap<>();
        telechargements.forEach((chemin, action) -> statuts.put(
                action,
                given().header("X-Edition-Id", "E1")
                        .when()
                        .get(chemin)
                        .then()
                        .extract()
                        .statusCode()));
        assertThat(statuts)
                .as("les téléchargements eux-mêmes")
                .allSatisfy((action, statut) -> assertThat(statut).as(action).isEqualTo(200));

        JsonPath historique = given().header("X-Edition-Id", "E1")
                .queryParam("limite", 500)
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

        given().header("X-Edition-Id", "E1")
                .queryParam("depuis", avant)
                .when()
                .get("/api/historique/changements")
                .then()
                .statusCode(200)
                .body("total", equalTo(0))
                .body("dernieres", empty());
    }

    /**
     * The list the Animateurs screen builds in the browser never reaches the
     * server: the screen announces it first, and that call alone writes the
     * export line — naming nothing of what left, changing no data.
     */
    @Test
    void theBrowserBuiltAnimateurListLeavesAnExportLine() {
        String avant = Instant.now().toString();
        given().header("X-Edition-Id", "E1")
                .contentType(ContentType.JSON)
                .when()
                .post("/api/animateurs/export-liste")
                .then()
                .statusCode(204);

        given().header("X-Edition-Id", "E1")
                .queryParam("nature", "exports")
                .when()
                .get("/api/historique")
                .then()
                .statusCode(200)
                .body("find { it.action == 'EXPORT_LISTE_ANIMATEURS' }.acteur", equalTo("ADMIN"))
                .body("find { it.action == 'EXPORT_LISTE_ANIMATEURS' }.resultat", equalTo("SUCCES"))
                .body("find { it.action == 'EXPORT_LISTE_ANIMATEURS' }.statut", equalTo(204))
                .body("find { it.action == 'EXPORT_LISTE_ANIMATEURS' }.champs", empty());
        given().header("X-Edition-Id", "E1")
                .queryParam("depuis", avant)
                .when()
                .get("/api/historique/changements")
                .then()
                .statusCode(200)
                .body("total", equalTo(0));
    }

    /** A refused export is written too, as refused: asking for nothing is a 400. */
    @Test
    void aRefusedExportIsRecordedAsRefused() {
        given().header("X-Edition-Id", "E1")
                .when()
                .get("/api/reference-data/export-csv")
                .then()
                .statusCode(400);

        given().header("X-Edition-Id", "E1")
                .when()
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
        given().header("X-Edition-Id", "E1")
                .when()
                .get("/api/reference-data/export-csv?stands=true")
                .then()
                .statusCode(200);
        // More recent lines than the page asked for below, none of them an export.
        List<String> crees = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            given().header("X-Edition-Id", "E1")
                    .when()
                    .get("/api/reference-data/export-csv")
                    .then()
                    .statusCode(400);
            crees.add(given().header("X-Edition-Id", "E1")
                    .contentType(ContentType.JSON)
                    .body("{\"prenom\":\"X" + i + "\",\"nom\":\"Y\",\"dateNaissance\":\"1990-01-01\"}")
                    .when()
                    .post("/api/animateurs")
                    .then()
                    .statusCode(200)
                    .extract()
                    .path("animateur.id"));
        }

        List<String> exportCodes = given().header("X-Edition-Id", "E1")
                .when()
                .get("/api/historique/actions")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getList("findAll { it.export }.code", String.class);
        List<Map<String, Object>> page = given().header("X-Edition-Id", "E1")
                .queryParam("nature", "exports")
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

        for (String id : crees) {
            given().header("X-Edition-Id", "E1")
                    .when()
                    .delete("/api/animateurs/" + id)
                    .then()
                    .statusCode(204);
        }
    }

    /**
     * A period is selected by the server, bounds as {@code /changements}
     * reads them: « depuis » exclusive, « jusqua » inclusive — and nothing
     * outside them comes back, whatever the page size.
     */
    @Test
    void aPeriodKeepsOnlyTheLinesBetweenItsBounds() {
        String avantA = Instant.now().toString();
        String a = createAnimateur("PeriodeA");
        String apresA = Instant.now().toString();
        String b = createAnimateur("PeriodeB");
        String apresB = Instant.now().toString();
        String c = createAnimateur("PeriodeC");

        List<String> entites = given().header("X-Edition-Id", "E1")
                .queryParam("depuis", apresA)
                .queryParam("jusqua", apresB)
                .when()
                .get("/api/historique")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getList("entiteId", String.class);
        assertThat(entites).contains(b).doesNotContain(a, c);

        List<String> depuisA = given().header("X-Edition-Id", "E1")
                .queryParam("depuis", avantA)
                .when()
                .get("/api/historique")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getList("entiteId", String.class);
        assertThat(depuisA).as("sans fin, jusqu'à maintenant").contains(a, b, c);

        deleteAnimateurs(a, b, c);
    }

    /**
     * The solver screen's « N modifications depuis la dernière résolution »
     * opens {@code nature=donnees&depuis=<the solve>}: the list must hold
     * exactly the lines the count counted — no refusal, no export, no read.
     */
    @Test
    void theDataChangesSinceAMomentAreExactlyTheLinesTheCountCounts() {
        String depuis = Instant.now().toString();
        String a = createAnimateur("DonneesA");
        String b = createAnimateur("DonneesB");
        // A refused write and an export: lines of the history, changes of nothing.
        given().header("X-Edition-Id", "E1")
                .contentType(ContentType.JSON)
                .body("""
                        {"id":"DONNEES-INCONNU","prenom":"X","nom":"Y","dateNaissance":"1990-01-01"}""")
                .when()
                .put("/api/animateurs/DONNEES-INCONNU")
                .then()
                .statusCode(404);
        given().header("X-Edition-Id", "E1")
                .when()
                .get("/api/reference-data/export-csv?stands=true")
                .then()
                .statusCode(200);

        int total = given().header("X-Edition-Id", "E1")
                .queryParam("depuis", depuis)
                .when()
                .get("/api/historique/changements")
                .then()
                .statusCode(200)
                .extract()
                .path("total");
        List<Map<String, Object>> lignes = given().header("X-Edition-Id", "E1")
                .queryParam("depuis", depuis)
                .queryParam("nature", "donnees")
                .when()
                .get("/api/historique")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getList("$");

        assertThat(total).isGreaterThanOrEqualTo(2);
        assertThat(lignes)
                .hasSize(total)
                .allSatisfy(ligne -> assertThat(ligne).containsEntry("resultat", "SUCCES"))
                .noneSatisfy(ligne -> assertThat(ligne.get("entiteId")).isEqualTo("DONNEES-INCONNU"))
                .noneSatisfy(ligne -> assertThat(ligne.get("action")).isEqualTo("EXPORT_REFERENTIELS"));
        assertThat(lignes).extracting(ligne -> ligne.get("entiteId")).contains(a, b);

        deleteAnimateurs(a, b);
    }

    /**
     * Paged by a keyset cursor: the next page starts after the last id shown,
     * the pages never overlap, and together they are the whole period.
     */
    @Test
    void thePagesFollowOneAnotherByCursorWithoutOverlap() {
        String depuis = Instant.now().toString();
        String a = createAnimateur("PageA");
        String b = createAnimateur("PageB");
        String c = createAnimateur("PageC");

        List<Integer> tout = idsOfPage(depuis, null, 500);
        List<Integer> premiere = idsOfPage(depuis, null, 2);
        List<Integer> seconde = idsOfPage(depuis, premiere.get(1), 500);

        assertThat(tout).hasSizeGreaterThanOrEqualTo(3);
        assertThat(premiere).containsExactlyElementsOf(tout.subList(0, 2));
        assertThat(seconde).containsExactlyElementsOf(tout.subList(2, tout.size()));
        // A cursor naming no line of the edition — one the purge took — ends the list.
        assertThat(idsOfPage(depuis, Integer.MAX_VALUE, 500)).isEmpty();

        deleteAnimateurs(a, b, c);
    }

    @Test
    void aPeriodThatEndsBeforeItStartsOrCannotBeReadIsRefused() {
        given().header("X-Edition-Id", "E1")
                .queryParam("depuis", "2026-09-02T10:00:00Z")
                .queryParam("jusqua", "2026-09-01T10:00:00Z")
                .when()
                .get("/api/historique")
                .then()
                .statusCode(400);
        given().header("X-Edition-Id", "E1")
                .queryParam("jusqua", "demain")
                .when()
                .get("/api/historique")
                .then()
                .statusCode(400);
    }

    private static List<Integer> idsOfPage(String depuis, Integer avant, int limite) {
        var requete = given().header("X-Edition-Id", "E1")
                .queryParam("depuis", depuis)
                .queryParam("limite", limite);
        if (avant != null) {
            requete = requete.queryParam("avant", avant);
        }
        return requete.when()
                .get("/api/historique")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getList("id", Integer.class);
    }

    private static String createAnimateur(String nom) {
        return given().header("X-Edition-Id", "E1")
                .contentType(ContentType.JSON)
                .body("{\"prenom\":\"Histo\",\"nom\":\"" + nom + "\",\"dateNaissance\":\"1990-01-01\"}")
                .when()
                .post("/api/animateurs")
                .then()
                .statusCode(200)
                .extract()
                .path("animateur.id");
    }

    private static void deleteAnimateurs(String... ids) {
        for (String id : ids) {
            given().header("X-Edition-Id", "E1")
                    .when()
                    .delete("/api/animateurs/" + id)
                    .then()
                    .statusCode(204);
        }
    }

    @Test
    void anUnknownNatureIsRefused() {
        given().header("X-Edition-Id", "E1")
                .queryParam("nature", "toutes")
                .when()
                .get("/api/historique")
                .then()
                .statusCode(400);
    }

    @Test
    void theChangesRefuseAMomentTheyCannotRead() {
        given().header("X-Edition-Id", "E1")
                .when()
                .get("/api/historique/changements")
                .then()
                .statusCode(400);
        given().header("X-Edition-Id", "E1")
                .queryParam("depuis", "hier")
                .when()
                .get("/api/historique/changements")
                .then()
                .statusCode(400);
    }

    @Test
    void theActionInventoryIsServedForTheScreensFilter() {
        given().header("X-Edition-Id", "E1")
                .when()
                .get("/api/historique/actions")
                .then()
                .statusCode(200)
                .body("size()", greaterThan(50))
                .body("libelle", everyItem(not(empty())))
                .body("find { it.code == 'ANIMATEUR_CREE' }.libelle", equalTo("Animateur ajouté"))
                .body("find { it.code == 'ANIMATEUR_CREE' }.export", equalTo(false))
                .body("find { it.code == 'EXPORT_REFERENTIELS' }.export", equalTo(true))
                .body("find { it.code == 'TELECHARGEMENT_ESPACE_PDF' }.export", equalTo(true))
                .body("find { it.code == 'EXPORT_EQUITE' }.entite", equalTo("PLANNING"));
    }
}
