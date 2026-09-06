package dev.sylvain.planning.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The {@code edition} scope end-to-end: creating an edition, working in it
 * through {@code X-Edition-Id}, duplicating one into another, and the guards
 * around deletion. See {@code docs/decisions/0001-cloisonnement-par-edition.md}.
 */
@QuarkusTest
class EditionResourceTest {

    private static final String HEADER = "X-Edition-Id";
    private static final String DEFAUT = "DEFAUT";

    /**
     * Every test creates its own edition(s); dropping them afterwards keeps the
     * shared dev-services database as this class found it. The default edition
     * is never touched, so an unrelated test never sees a leftover edition.
     */
    @AfterEach
    void supprimerLesEditionsCreees() {
        for (Map<String, Object> edition : listEditions()) {
            String id = (String) edition.get("id");
            if (!DEFAUT.equals(id)) {
                given().when().delete("/api/editions/" + id);
            }
        }
    }

    private List<Map<String, Object>> listEditions() {
        return given().when().get("/api/editions").then().statusCode(200).extract().jsonPath().getList("$");
    }

    private void createEdition(String id, String nom) {
        given().contentType("application/json")
                .body("{\"id\":\"" + id + "\",\"nom\":\"" + nom + "\"}")
                .when().post("/api/editions")
                .then().statusCode(200);
    }

    /** A stand always carries a typologie (issue #343): one is created in the edition first, idempotently. */
    private void createStand(String editionId, String standId) {
        given().header(HEADER, editionId)
                .contentType("application/json")
                .body("{\"id\":\"TYPO-" + editionId + "\",\"label\":\"Typologie\"}")
                .when().post("/api/typologies")
                .then().statusCode(200);
        given().header(HEADER, editionId)
                .contentType("application/json")
                .body("{\"id\":\"" + standId + "\",\"nom\":\"" + standId + "\",\"typologiesProposees\":[\"TYPO-"
                        + editionId + "\"],\"effectifMin\":1,\"effectifMax\":2}")
                .when().post("/api/stands")
                .then().statusCode(200);
    }

    private void createTypologie(String editionId, String typologieId, boolean ninja) {
        given().header(HEADER, editionId)
                .contentType("application/json")
                .body("{\"id\":\"" + typologieId + "\",\"label\":\"" + typologieId + "\",\"ninja\":" + ninja + "}")
                .when().post("/api/typologies")
                .then().statusCode(200);
    }

    private boolean isNinja(String editionId, String typologieId) {
        return given().header(HEADER, editionId)
                .when().get("/api/typologies")
                .then().statusCode(200)
                .extract().jsonPath()
                .getList("findAll { it.id == '" + typologieId + "' }.ninja", Boolean.class)
                .getFirst();
    }

    private List<String> listStandIds(String editionId) {
        return given().header(HEADER, editionId)
                .when().get("/api/stands")
                .then().statusCode(200)
                .extract().jsonPath().getList("id");
    }

    @Test
    void uneBaseNeuveTientUneSeuleEditionParDefaut() {
        assertThat(listEditions())
                .filteredOn(edition -> DEFAUT.equals(edition.get("id")))
                .singleElement()
                .satisfies(edition -> assertThat(edition.get("defaut")).isEqualTo(true));
    }

    @Test
    void lesDonneesDuneEditionSontInvisiblesDepuisUneAutre() {
        createEdition("ANNEE-2026", "Année 2026");
        createStand("ANNEE-2026", "STAND-2026");

        assertThat(listStandIds("ANNEE-2026")).contains("STAND-2026");
        assertThat(listStandIds(DEFAUT)).doesNotContain("STAND-2026");
    }

    @Test
    void deuxEditionsPeuventPorterLeMemeIdentifiantMetier() {
        createEdition("ANNEE-2026", "Année 2026");
        createStand(DEFAUT, "TIR-A-LA-CORDE");
        // Same business id in another edition: this is exactly what the
        // composite (edition_id, id) primary keys of V32 make possible.
        createStand("ANNEE-2026", "TIR-A-LA-CORDE");

        assertThat(listStandIds(DEFAUT)).contains("TIR-A-LA-CORDE");
        assertThat(listStandIds("ANNEE-2026")).contains("TIR-A-LA-CORDE");

        given().header(HEADER, "ANNEE-2026").when().delete("/api/stands/TIR-A-LA-CORDE").then().statusCode(204);

        assertThat(listStandIds("ANNEE-2026")).doesNotContain("TIR-A-LA-CORDE");
        assertThat(listStandIds(DEFAUT)).contains("TIR-A-LA-CORDE");
    }

    @Test
    void unEnteteInconnuRetombeSurLEditionParDefautSansEchouer() {
        createStand(DEFAUT, "STAND-REPLI");

        // A tab left open on a since-deleted edition must keep working.
        assertThat(listStandIds("EDITION-QUI-NEXISTE-PAS")).contains("STAND-REPLI");

        given().header(HEADER, "EDITION-QUI-NEXISTE-PAS")
                .when().get("/api/editions/courant")
                .then().statusCode(200)
                .body("id", org.hamcrest.Matchers.equalTo(DEFAUT));
    }

    @Test
    void dupliquerUneEditionRecopieSonReferentielMaisPasSesAffectations() {
        createStand(DEFAUT, "STAND-A-COPIER");

        given().contentType("application/json")
                .body("{\"id\":\"COPIE-2026\",\"nom\":\"Copie 2026\"}")
                .when().post("/api/editions/" + DEFAUT + "/dupliquer")
                .then().statusCode(200);

        assertThat(listStandIds("COPIE-2026")).contains("STAND-A-COPIER");
        given().header(HEADER, "COPIE-2026")
                .when().get("/api/planning/persisted/count")
                .then().statusCode(200)
                .body("assignments", org.hamcrest.Matchers.equalTo(0));
    }

    /**
     * The V41 columns and the V37 recurring-hours tables postdated the
     * duplication column list: a duplicated edition silently lost every
     * animateur email (muting « Envoyer à all », step 5 of the issue #172
     * switch ritual) and every recurring opening rule (stands falling back to
     * « open on every slot »). The access token, on the other hand, must NOT
     * travel: each edition mints its own, so an espace link keeps designating
     * exactly one edition.
     */
    @Test
    void dupliquerRecopieEmailEtHorairesMaisFrappeUnJetonNeuf() {
        given().header(HEADER, DEFAUT).contentType("application/json")
                .body("{\"id\":\"ANIM-COPIE\",\"prenom\":\"Ada\",\"nom\":\"Lovelace\","
                        + "\"dateNaissance\":\"1990-01-01\",\"email\":\"ada@example.org\"}")
                .when().post("/api/animateurs")
                .then().statusCode(200);
        given().header(HEADER, DEFAUT).contentType("application/json")
                .body("{\"id\":\"STAND-HORAIRE\",\"nom\":\"Stand à règles\",\"typologiesProposees\":[\"STRATEGIE\"],\"effectifMin\":1,\"effectifMax\":2,"
                        + "\"horaires\":[{\"mode\":\"FERMETURE\",\"typeJours\":\"TOUS\","
                        + "\"fenetres\":[{\"heureDebut\":\"09:00:00\",\"heureFin\":\"10:00:00\"}]}]}")
                .when().post("/api/stands")
                .then().statusCode(200);
        String sourceToken = given().header(HEADER, DEFAUT)
                .when().get("/api/animateurs")
                .then().statusCode(200)
                .extract().jsonPath().getString("find { it.id == 'ANIM-COPIE' }.accessToken");

        given().contentType("application/json")
                .body("{\"id\":\"COPIE-2026\",\"nom\":\"Copie 2026\"}")
                .when().post("/api/editions/" + DEFAUT + "/dupliquer")
                .then().statusCode(200);

        given().header(HEADER, "COPIE-2026")
                .when().get("/api/animateurs")
                .then().statusCode(200)
                .body("find { it.id == 'ANIM-COPIE' }.email", org.hamcrest.Matchers.equalTo("ada@example.org"))
                .body("find { it.id == 'ANIM-COPIE' }.accessToken",
                        org.hamcrest.Matchers.allOf(
                                org.hamcrest.Matchers.notNullValue(),
                                org.hamcrest.Matchers.not(sourceToken)));
        given().header(HEADER, "COPIE-2026")
                .when().get("/api/stands")
                .then().statusCode(200)
                .body("find { it.id == 'STAND-HORAIRE' }.horaires.size()", org.hamcrest.Matchers.equalTo(1))
                .body("find { it.id == 'STAND-HORAIRE' }.horaires[0].fenetres[0].heureDebut",
                        org.hamcrest.Matchers.equalTo("09:00:00"));
    }

    @Test
    void uneEditionDupliqueeEstIndependanteDeSaSource() {
        createStand(DEFAUT, "STAND-PARTAGE");
        given().contentType("application/json")
                .body("{\"id\":\"COPIE-2026\",\"nom\":\"Copie 2026\"}")
                .when().post("/api/editions/" + DEFAUT + "/dupliquer")
                .then().statusCode(200);

        given().header(HEADER, "COPIE-2026").when().delete("/api/stands/STAND-PARTAGE").then().statusCode(204);

        assertThat(listStandIds("COPIE-2026")).doesNotContain("STAND-PARTAGE");
        assertThat(listStandIds(DEFAUT)).contains("STAND-PARTAGE");
    }

    @Test
    void supprimerLEditionParDefautEstRefuse() {
        createEdition("ANNEE-2026", "Année 2026");

        given().header(HEADER, "ANNEE-2026")
                .when().delete("/api/editions/" + DEFAUT)
                .then().statusCode(400);
    }

    @Test
    void supprimerLEditionCouranteEstRefuse() {
        createEdition("ANNEE-2026", "Année 2026");

        given().header(HEADER, "ANNEE-2026")
                .when().delete("/api/editions/ANNEE-2026")
                .then().statusCode(400);
    }

    @Test
    void creerDeuxFoisLeMemeIdentifiantEstRefuse() {
        createEdition("ANNEE-2026", "Année 2026");

        given().contentType("application/json")
                .body("{\"id\":\"ANNEE-2026\",\"nom\":\"Doublon\"}")
                .when().post("/api/editions")
                .then().statusCode(400);
    }

    /**
     * Writing in one edition must never reach across into another — the
     * invariant every business table's {@code edition_id} exists for. The
     * "one ninja typologie" rule is where it is easiest to break: the write
     * demotes the previous holder, and the unique index it protects has been
     * scoped per edition since {@code V33}, so the demotion must be scoped
     * too. Ninja drives {@code Animateur#hasCompetenceFor}, hence the
     * construction-heuristic ordering and the polyvalent-buffer constraint:
     * losing it silently changes what the next solve of the other edition
     * explores.
     */
    @Test
    void marquerUneTypologieNinjaNeDeflaguePasCelleDuneAutreEdition() {
        createEdition("ANNEE-2025", "Année 2025");
        createEdition("ANNEE-2026", "Année 2026");
        createTypologie("ANNEE-2025", "NINJA_2025", true);

        createTypologie("ANNEE-2026", "NINJA_2026", true);

        assertThat(isNinja("ANNEE-2026", "NINJA_2026")).isTrue();
        assertThat(isNinja("ANNEE-2025", "NINJA_2025")).isTrue();
    }
}
