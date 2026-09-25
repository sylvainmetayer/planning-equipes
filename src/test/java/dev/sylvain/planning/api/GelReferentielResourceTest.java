package dev.sylvain.planning.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The freeze of the referential over HTTP (ADR 0052): each family refuses its
 * writes in {@code 409 REFERENTIEL_FIGE} by every path — the form, the bulk
 * edit (the same {@code PUT}, once per row), the CSV and grid imports — while
 * what it does not cover stays open.
 *
 * <p>Everything happens in an edition of its own, created and deleted around
 * each test: a freeze left behind in the default edition would refuse the
 * reset every other test starts with.</p>
 */
@QuarkusTest
class GelReferentielResourceTest {

    /** The edition each test works in, drawn by the server when created (ids are generated). */
    private static String edition;

    private static final String FIGE = "REFERENTIEL_FIGE";

    @BeforeEach
    void anEditionOfItsOwnHoldingTheScenario() {
        edition = given().contentType("application/json")
                .body(Map.of("nom", "Gel du référentiel"))
                .when()
                .post("/api/editions")
                .then()
                .statusCode(200)
                .extract()
                .path("id");
        edition()
                .when()
                .post("/api/reference-data/import-scenario?name=scenario.yml")
                .then()
                .statusCode(200);
    }

    @AfterEach
    void liftEverythingAndDropTheEdition() {
        for (String famille : List.of("STANDS", "CRENEAUX", "TYPOLOGIES_EMPLACEMENTS", "COMPETENCES")) {
            edition().when().delete("/api/editions/courant/gel/" + famille);
        }
        given().when().delete("/api/editions/" + edition);
    }

    private static RequestSpecification edition() {
        return given().header("X-Edition-Id", edition).contentType("application/json");
    }

    private static void freeze(String famille) {
        edition()
                .when()
                .put("/api/editions/courant/gel/" + famille)
                .then()
                .statusCode(200)
                .body("famille", equalTo(famille))
                .body("fige", equalTo(true))
                .body("figeLe", notNullValue());
    }

    private static void refused(ValidatableResponse response, String famille) {
        response.statusCode(409).body("code", equalTo(FIGE)).body("familles", hasItem(famille));
    }

    /**
     * The row the scenario named {@code key}: by its code for a stand, a
     * typologie or an emplacement, by their e-mail for the animateurs: the
     * edition draws every id itself.
     */
    private static Map<String, Object> row(String path, String key) {
        List<Map<String, Object>> rows = edition()
                .when()
                .get(path)
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getList("$");
        return new HashMap<>(rows.stream()
                .filter(row -> key.equals(row.get("code")) || key.equals(row.get("email")))
                .findFirst()
                .orElseThrow());
    }

    private static String id(String path, String key) {
        return (String) row(path, key).get("id");
    }

    @Test
    void freezingAndLiftingAreReadBackAndJournalled() {
        edition()
                .when()
                .get("/api/editions/courant/gel")
                .then()
                .statusCode(200)
                .body("", hasSize(4))
                .body("fige", equalTo(List.of(false, false, false, false)));

        freeze("STANDS");
        // Freezing twice is one freeze: the date stays the first one.
        String figeLe = edition()
                .when()
                .get("/api/editions/courant/gel/STANDS")
                .then()
                .statusCode(200)
                .extract()
                .path("figeLe");
        edition()
                .when()
                .put("/api/editions/courant/gel/STANDS")
                .then()
                .statusCode(200)
                .body("figeLe", equalTo(figeLe));
        freeze("CRENEAUX");

        edition()
                .when()
                .get("/api/editions/courant/etat")
                .then()
                .statusCode(200)
                .body("gel.familles.find { it.famille == 'STANDS' }.fige", equalTo(true))
                .body("gel.familles.find { it.famille == 'COMPETENCES' }.fige", equalTo(false))
                .body("gel.statut", equalTo("FAIT"));

        edition()
                .when()
                .delete("/api/editions/courant/gel/STANDS")
                .then()
                .statusCode(200)
                .body("fige", equalTo(false))
                .body("figeLe", nullValue());
        edition().when().get("/api/editions/courant/etat").then().body("gel.statut", equalTo("INFO"));

        edition()
                .when()
                .get("/api/historique")
                .then()
                .statusCode(200)
                .body("find { it.action == 'GEL_POSE' }.champs", hasItem("CRENEAUX"))
                .body("find { it.action == 'GEL_LEVE' }.champs", hasItem("STANDS"))
                .body("find { it.action == 'GEL_LEVE' }.libelle", equalTo("Gel du référentiel levé"));
    }

    @Test
    void anUnknownFamilyIsNotFound() {
        edition().when().put("/api/editions/courant/gel/HORAIRES").then().statusCode(404);
    }

    @Test
    void frozenStandsRefuseEveryPathButLeaveTheNameOpen() {
        freeze("STANDS");
        String strat = id("/api/stands", "STAND-STRAT");
        String hommeJeu = id("/api/stands", "HOMME-JEU");

        refused(
                edition()
                        .body(Map.of("nom", "Nouveau", "effectifMin", 1, "effectifMax", 1))
                        .when()
                        .post("/api/stands")
                        .then(),
                "STANDS");

        // The bulk edit is this same PUT, once per row.
        Map<String, Object> stand = row("/api/stands", "STAND-STRAT");
        stand.put("effectifMax", 3);
        refused(edition().body(stand).when().put("/api/stands/" + strat).then(), "STANDS");

        Map<String, Object> renomme = row("/api/stands", "STAND-STRAT");
        renomme.put("nom", "Stratégie (renommé)");
        edition().body(renomme).when().put("/api/stands/" + strat).then().statusCode(200);

        refused(edition().when().delete("/api/stands/" + hommeJeu).then(), "STANDS");
        refused(
                edition()
                        .body(Map.of("fileName", "stands.csv", "content", "nom\nX"))
                        .when()
                        .post("/api/stands/import-csv")
                        .then(),
                "STANDS");
        refused(
                edition()
                        .body(Map.of("fileName", "grille.csv", "content", "stand\nX"))
                        .when()
                        .post("/api/stands/import-grille")
                        .then(),
                "STANDS");
        refused(
                edition()
                        .body(Map.of("stands", List.of(Map.of("standId", strat, "cellules", List.of()))))
                        .when()
                        .put("/api/ouvertures-stands/grille")
                        .then(),
                "STANDS");
        refused(
                edition()
                        .when()
                        .post("/api/stands/compactage-horaires?appliquer=true")
                        .then(),
                "STANDS");

        // The message names the family to lift, and where.
        edition()
                .when()
                .delete("/api/stands/" + hommeJeu)
                .then()
                .body("message", containsString("« Stands »"))
                .body("message", containsString("Levez le gel"));
    }

    @Test
    void frozenCreneauxRefuseEveryPath() {
        freeze("CRENEAUX");

        refused(
                edition()
                        .body(Map.of("date", "2026-07-09", "heureDebut", "09:00:00", "heureFin", "12:00:00"))
                        .when()
                        .post("/api/creneaux")
                        .then(),
                "CRENEAUX");
        Map<String, Object> creneau = edition()
                .when()
                .get("/api/creneaux")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getMap("[0]");
        Object id = creneau.get("id");
        Map<String, Object> modifie = new HashMap<>(creneau);
        modifie.put("couverturePause", true);
        refused(edition().body(modifie).when().put("/api/creneaux/" + id).then(), "CRENEAUX");
        refused(edition().when().delete("/api/creneaux/" + id).then(), "CRENEAUX");
        refused(edition().body("""
                                {"jours":"TOUS","dateDebut":"2031-03-03","dateFin":"2031-03-04",
                                 "fenetres":[{"heureDebut":"09:00:00","heureFin":"12:00:00"}]}""").when().post("/api/creneaux/recurrence").then(), "CRENEAUX");
        refused(
                edition()
                        .body(Map.of("fileName", "creneaux.csv", "content", "date;debut;fin"))
                        .when()
                        .post("/api/creneaux/import-csv")
                        .then(),
                "CRENEAUX");
        refused(edition().when().post("/api/journees-types/application").then(), "CRENEAUX");
    }

    @Test
    void frozenTypologiesAndEmplacementsRefuseCreationDeletionAndTheCap() {
        freeze("TYPOLOGIES_EMPLACEMENTS");
        String strategie = id("/api/typologies", "STRATEGIE");
        String centre = id("/api/emplacements", "CENTRE-VILLE");

        refused(
                edition()
                        .body(Map.of("code", "NOUVELLE", "label", "Nouvelle"))
                        .when()
                        .post("/api/typologies")
                        .then(),
                "TYPOLOGIES_EMPLACEMENTS");
        Map<String, Object> typologie = row("/api/typologies", "STRATEGIE");
        typologie.put("label", "Stratégie (renommée)");
        typologie.put("description", "Une note");
        edition()
                .body(typologie)
                .when()
                .put("/api/typologies/" + strategie)
                .then()
                .statusCode(200);
        Map<String, Object> plafonnee = row("/api/typologies", "STRATEGIE");
        plafonnee.put("maxCreneauxParAnimateur", 2);
        refused(
                edition()
                        .body(plafonnee)
                        .when()
                        .put("/api/typologies/" + strategie)
                        .then(),
                "TYPOLOGIES_EMPLACEMENTS");

        refused(
                edition()
                        .body(Map.of("nom", "Parc"))
                        .when()
                        .post("/api/emplacements")
                        .then(),
                "TYPOLOGIES_EMPLACEMENTS");
        refused(edition().when().delete("/api/emplacements/" + centre).then(), "TYPOLOGIES_EMPLACEMENTS");
        Map<String, Object> emplacement = row("/api/emplacements", "CENTRE-VILLE");
        emplacement.put("nom", "Centre historique");
        edition()
                .body(emplacement)
                .when()
                .put("/api/emplacements/" + centre)
                .then()
                .statusCode(200);
        refused(
                edition()
                        .body(Map.of("fileName", "typologies.csv", "content", "libelle"))
                        .when()
                        .post("/api/typologies/import-csv")
                        .then(),
                "TYPOLOGIES_EMPLACEMENTS");
    }

    @Test
    void frozenCompetencesRefuseARetouchButNotANewFicheNorThePersonsOwnData() {
        freeze("COMPETENCES");
        String strategie = id("/api/typologies", "STRATEGIE");

        Map<String, Object> animateur = row("/api/animateurs", "A1@example.org");
        animateur.put("competences", Map.of(strategie, "DEBUTANT"));
        String a1 = (String) animateur.get("id");
        refused(edition().body(animateur).when().put("/api/animateurs/" + a1).then(), "COMPETENCES");

        // The person's own data stay open: e-mail, days off.
        Map<String, Object> indispo = row("/api/animateurs", "A1@example.org");
        indispo.put("email", "alice.nouvelle@example.org");
        indispo.put("joursIndisponibles", List.of("2026-07-08"));
        edition().body(indispo).when().put("/api/animateurs/" + a1).then().statusCode(200);

        // A new fiche is not a retouch.
        edition()
                .body(Map.of(
                        "prenom",
                        "Nina",
                        "nom",
                        "Nouvelle",
                        "dateNaissance",
                        "1990-01-01",
                        "email",
                        "nina@example.org",
                        "competences",
                        Map.of(strategie, "AUTONOME")))
                .when()
                .post("/api/animateurs")
                .then()
                .statusCode(200);

        refused(
                edition()
                        .body(Map.of(
                                "animateurs",
                                List.of(Map.of(
                                        "animateurId",
                                        id("/api/animateurs", "A2@example.org"),
                                        "competences",
                                        Map.of(strategie, "REFERENT")))))
                        .when()
                        .put("/api/animateurs/competences/grille")
                        .then(),
                "COMPETENCES");

        String fichier = """
                prénom;nom;email;date de naissance;compétences
                Bruno;Autonome;A2@example.org;2007-07-19;HOMME_JEU:REFERENT
                """;
        refused(
                edition()
                        .body(Map.of("fileName", "animateurs.csv", "content", fichier))
                        .when()
                        .post("/api/animateurs/import-csv")
                        .then(),
                "COMPETENCES");
        // The same file naming a newcomer only goes through.
        String nouveau = """
                prénom;nom;email;date de naissance;compétences
                Hugo;Nouveau;hugo@example.org;1990-01-01;HOMME_JEU:REFERENT
                """;
        edition()
                .body(Map.of("fileName", "animateurs.csv", "content", nouveau))
                .when()
                .post("/api/animateurs/import-csv")
                .then()
                .statusCode(200)
                .body("applied", equalTo(true));
    }

    /**
     * A stand's game categories and an animateur's competences may be named
     * by their code: the freeze compares them once resolved to ids, so a
     * rename sending codes is not taken for a retouch — and a real one still
     * is.
     */
    @Test
    @SuppressWarnings("unchecked")
    void gameCategoriesNamedByTheirCodeAreComparedAsIds() {
        freeze("STANDS");
        freeze("COMPETENCES");
        Map<String, String> codeParId = new HashMap<>();
        edition().when().get("/api/typologies").then().statusCode(200).extract().jsonPath().getList("$").stream()
                .map(row -> (Map<String, Object>) row)
                .forEach(row -> codeParId.put((String) row.get("id"), (String) row.get("code")));

        Map<String, Object> stand = row("/api/stands", "STAND-STRAT");
        List<String> proposees = (List<String>) stand.get("typologiesProposees");
        assertThat(proposees).isNotEmpty();
        stand.put("nom", "Stratégie (renommé)");
        stand.put("typologiesProposees", proposees.stream().map(codeParId::get).toList());
        edition()
                .body(stand)
                .when()
                .put("/api/stands/" + stand.get("id"))
                .then()
                .statusCode(200);
        stand.put("typologiesProposees", List.of("HOMME_JEU"));
        refused(
                edition()
                        .body(stand)
                        .when()
                        .put("/api/stands/" + stand.get("id"))
                        .then(),
                "STANDS");

        Map<String, Object> animateur = row("/api/animateurs", "A1@example.org");
        Map<String, Object> competences = (Map<String, Object>) animateur.get("competences");
        assertThat(competences).isNotEmpty();
        Map<String, Object> parCode = new HashMap<>();
        competences.forEach((id, niveau) -> parCode.put(codeParId.get(id), niveau));
        animateur.put("competences", parCode);
        animateur.put("email", "alice.codes@example.org");
        edition()
                .body(animateur)
                .when()
                .put("/api/animateurs/" + animateur.get("id"))
                .then()
                .statusCode(200);
        parCode.put("STRATEGIE", "DEBUTANT");
        animateur.put("competences", parCode);
        refused(
                edition()
                        .body(animateur)
                        .when()
                        .put("/api/animateurs/" + animateur.get("id"))
                        .then(),
                "COMPETENCES");
    }

    /**
     * A solve whose problem the caller supplies lands it over the stands,
     * timeslots and competences it carries: refused under any freeze, on both
     * routes, naming the frozen families — the asynchronous one at submission,
     * before any job exists.
     */
    @Test
    void aSolveOnAProblemTheCallerSuppliesIsRefusedOnBothRoutes() {
        freeze("CRENEAUX");

        for (String route : List.of("/api/solve", "/api/solve/async")) {
            edition()
                    .body(Map.of())
                    .when()
                    .post(route + "?seconds=1")
                    .then()
                    .statusCode(409)
                    .body("code", equalTo(FIGE))
                    .body("familles", equalTo(List.of("CRENEAUX")))
                    .body("message", containsString("« Créneaux »"));
        }
    }

    @Test
    void adjustmentsAndLocksStayOpenUnderEveryFreeze() {
        for (String famille : List.of("STANDS", "CRENEAUX", "TYPOLOGIES_EMPLACEMENTS", "COMPETENCES")) {
            freeze(famille);
        }
        edition()
                .body("""
                        {"type":"INDISPONIBILITE_FORCEE","animateursConcernes":[{"id":"%s"}],"raison":"Absent"}""".formatted(id("/api/animateurs", "A3@example.org")))
                .when()
                .post("/api/contraintes-ad-hoc")
                .then()
                .statusCode(200);
        edition()
                .body(Map.of("type", "ANIMATEUR", "animateurId", id("/api/animateurs", "A1@example.org")))
                .when()
                .post("/api/verrouillages")
                .then()
                .statusCode(200);
    }

    @Test
    void aScenarioImportAndTheResetAreRefusedNamingTheFamiliesToLift() {
        freeze("STANDS");
        freeze("COMPETENCES");

        edition()
                .when()
                .post("/api/reference-data/import-scenario?name=scenario.yml")
                .then()
                .statusCode(409)
                .body("code", equalTo(FIGE))
                .body("familles", equalTo(List.of("STANDS", "COMPETENCES")))
                .body("message", containsString("« Stands », « Compétences des animateurs »"));
        edition().when().post("/api/planning/reset").then().statusCode(409).body("code", equalTo(FIGE));

        // Nothing was written: the stands the scenario carries are still there.
        assertThat(edition()
                        .when()
                        .get("/api/stands")
                        .then()
                        .extract()
                        .jsonPath()
                        .getList("code"))
                .contains("STAND-STRAT", "HOMME-JEU");
    }

    @Test
    void aFreezeStaysInItsEditionAndADuplicateStartsOpen() {
        freeze("STANDS");

        given().when()
                .get("/api/editions/courant/gel/STANDS")
                .then()
                .statusCode(200)
                .body("fige", equalTo(false));

        String copie = given().contentType("application/json")
                .body(Map.of("nom", "Copie"))
                .when()
                .post("/api/editions/" + edition + "/dupliquer")
                .then()
                .statusCode(200)
                .extract()
                .path("id");
        try {
            given().header("X-Edition-Id", copie)
                    .when()
                    .get("/api/editions/courant/gel")
                    .then()
                    .statusCode(200)
                    .body("fige", equalTo(List.of(false, false, false, false)));
        } finally {
            given().when().delete("/api/editions/" + copie);
        }
    }
}
