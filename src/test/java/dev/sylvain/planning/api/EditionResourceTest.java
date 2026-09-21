package dev.sylvain.planning.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.junit.QuarkusTest;
import java.util.List;
import java.util.Map;
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
        return given().when()
                .get("/api/editions")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getList("$");
    }

    private void createEdition(String id, String nom) {
        given().contentType("application/json")
                .body("{\"id\":\"" + id + "\",\"nom\":\"" + nom + "\"}")
                .when()
                .post("/api/editions")
                .then()
                .statusCode(200);
    }

    /**
     * A stand always carries a typologie (issue #343): one is created in the
     * edition first. A creation whose id is taken is a 409 since the write
     * carries its own precondition (issue #362), so a second call is expected
     * to bounce — the typologie is there either way.
     */
    private void createStand(String editionId, String standId) {
        given().header(HEADER, editionId)
                .contentType("application/json")
                .body("{\"id\":\"TYPO-" + editionId + "\",\"label\":\"Typologie\"}")
                .when()
                .post("/api/typologies")
                .then()
                .statusCode(org.hamcrest.Matchers.anyOf(
                        org.hamcrest.Matchers.equalTo(200), org.hamcrest.Matchers.equalTo(409)));
        given().header(HEADER, editionId)
                .contentType("application/json")
                .body("{\"id\":\"" + standId + "\",\"nom\":\"" + standId + "\",\"typologiesProposees\":[\"TYPO-"
                        + editionId + "\"],\"effectifMin\":1,\"effectifMax\":2}")
                .when()
                .post("/api/stands")
                .then()
                .statusCode(200);
    }

    private void createTypologie(String editionId, String typologieId, boolean ninja) {
        given().header(HEADER, editionId)
                .contentType("application/json")
                .body("{\"id\":\"" + typologieId + "\",\"label\":\"" + typologieId + "\",\"ninja\":" + ninja + "}")
                .when()
                .post("/api/typologies")
                .then()
                .statusCode(200);
    }

    private boolean isNinja(String editionId, String typologieId) {
        return given().header(HEADER, editionId)
                .when()
                .get("/api/typologies")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getList("findAll { it.id == '" + typologieId + "' }.ninja", Boolean.class)
                .getFirst();
    }

    private List<String> listStandIds(String editionId) {
        return given().header(HEADER, editionId)
                .when()
                .get("/api/stands")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getList("id");
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

        given().header(HEADER, "ANNEE-2026")
                .when()
                .delete("/api/stands/TIR-A-LA-CORDE")
                .then()
                .statusCode(204);

        assertThat(listStandIds("ANNEE-2026")).doesNotContain("TIR-A-LA-CORDE");
        assertThat(listStandIds(DEFAUT)).contains("TIR-A-LA-CORDE");
    }

    @Test
    void unEnteteInconnuRetombeSurLEditionParDefautSansEchouer() {
        createStand(DEFAUT, "STAND-REPLI");

        // A tab left open on a since-deleted edition must keep working.
        assertThat(listStandIds("EDITION-QUI-NEXISTE-PAS")).contains("STAND-REPLI");

        given().header(HEADER, "EDITION-QUI-NEXISTE-PAS")
                .when()
                .get("/api/editions/courant")
                .then()
                .statusCode(200)
                .body("id", org.hamcrest.Matchers.equalTo(DEFAUT));
    }

    @Test
    void dupliquerUneEditionRecopieSonReferentielMaisPasSesAffectations() {
        createStand(DEFAUT, "STAND-A-COPIER");

        given().contentType("application/json")
                .body("{\"id\":\"COPIE-2026\",\"nom\":\"Copie 2026\"}")
                .when()
                .post("/api/editions/" + DEFAUT + "/dupliquer")
                .then()
                .statusCode(200);

        assertThat(listStandIds("COPIE-2026")).contains("STAND-A-COPIER");
        given().header(HEADER, "COPIE-2026")
                .when()
                .get("/api/planning/persisted/count")
                .then()
                .statusCode(200)
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
        given().header(HEADER, DEFAUT)
                .contentType("application/json")
                .body("{\"id\":\"ANIM-COPIE\",\"prenom\":\"Ada\",\"nom\":\"Lovelace\","
                        + "\"dateNaissance\":\"1990-01-01\",\"email\":\"ada@example.org\"}")
                .when()
                .post("/api/animateurs")
                .then()
                .statusCode(200);
        given().header(HEADER, DEFAUT)
                .contentType("application/json")
                .body(
                        "{\"id\":\"STAND-HORAIRE\",\"nom\":\"Stand à règles\",\"typologiesProposees\":[\"STRATEGIE\"],\"effectifMin\":1,\"effectifMax\":2,"
                                + "\"horaires\":[{\"mode\":\"FERMETURE\",\"typeJours\":\"TOUS\","
                                + "\"fenetres\":[{\"heureDebut\":\"09:00:00\",\"heureFin\":\"10:00:00\"}]}]}")
                .when()
                .post("/api/stands")
                .then()
                .statusCode(200);
        String sourceToken = given().header(HEADER, DEFAUT)
                .when()
                .get("/api/animateurs")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getString("find { it.id == 'ANIM-COPIE' }.accessToken");

        given().contentType("application/json")
                .body("{\"id\":\"COPIE-2026\",\"nom\":\"Copie 2026\"}")
                .when()
                .post("/api/editions/" + DEFAUT + "/dupliquer")
                .then()
                .statusCode(200);

        given().header(HEADER, "COPIE-2026")
                .when()
                .get("/api/animateurs")
                .then()
                .statusCode(200)
                .body("find { it.id == 'ANIM-COPIE' }.email", org.hamcrest.Matchers.equalTo("ada@example.org"))
                .body(
                        "find { it.id == 'ANIM-COPIE' }.accessToken",
                        org.hamcrest.Matchers.allOf(
                                org.hamcrest.Matchers.notNullValue(), org.hamcrest.Matchers.not(sourceToken)));
        given().header(HEADER, "COPIE-2026")
                .when()
                .get("/api/stands")
                .then()
                .statusCode(200)
                .body("find { it.id == 'STAND-HORAIRE' }.horaires.size()", org.hamcrest.Matchers.equalTo(1))
                .body(
                        "find { it.id == 'STAND-HORAIRE' }.horaires[0].fenetres[0].heureDebut",
                        org.hamcrest.Matchers.equalTo("09:00:00"));
    }

    /**
     * The year-template duplication (issue #90): the structure comes over, the
     * people do not.
     *
     * <p>Preparing 2027 from 2026 otherwise copies names, birth dates and
     * e-mail addresses of people who have not signed up again — a minimisation
     * and retention problem ({@code docs/rgpd.md}), not a convenience. Every ad
     * hoc constraint names at least one animateur, so none of them survives
     * either: a copy pointing at absent persons would be worse than no copy.</p>
     */
    @Test
    void dupliquerSansLesAnimateursGardeLaStructureEtPersonne() {
        createStand(DEFAUT, "STAND-MODELE");
        given().header(HEADER, DEFAUT)
                .contentType("application/json")
                .body("{\"id\":\"ANIM-MODELE\",\"prenom\":\"Ada\",\"nom\":\"Lovelace\","
                        + "\"dateNaissance\":\"1990-01-01\",\"email\":\"ada@example.org\"}")
                .when()
                .post("/api/animateurs")
                .then()
                .statusCode(200);
        given().header(HEADER, DEFAUT)
                .contentType("application/json")
                .body("{\"id\":\"AJUST-MODELE\",\"type\":\"INDISPONIBILITE_FORCEE\","
                        + "\"animateursConcernes\":[{\"id\":\"ANIM-MODELE\"}],\"raison\":\"Absent\"}")
                .when()
                .post("/api/contraintes-ad-hoc")
                .then()
                .statusCode(200);

        given().contentType("application/json")
                .body("{\"id\":\"MODELE-2027\",\"nom\":\"Modèle 2027\"}")
                .when()
                .post("/api/editions/" + DEFAUT + "/dupliquer?avecAnimateurs=false")
                .then()
                .statusCode(200);

        assertThat(listStandIds("MODELE-2027")).contains("STAND-MODELE");
        given().header(HEADER, "MODELE-2027")
                .when()
                .get("/api/animateurs")
                .then()
                .statusCode(200)
                .body("size()", org.hamcrest.Matchers.equalTo(0));
        given().header(HEADER, "MODELE-2027")
                .when()
                .get("/api/contraintes-ad-hoc")
                .then()
                .statusCode(200)
                .body("size()", org.hamcrest.Matchers.equalTo(0));

        // The ad hoc constraint was written into DEFAUT, which no @AfterEach
        // clears: left there it would forbid ANIM-MODELE every seat, in every
        // test that solves after this one.
        given().header(HEADER, DEFAUT).when().delete("/api/contraintes-ad-hoc/AJUST-MODELE");
    }

    /** The default is unchanged: the people follow, as the « plan canicule » ritual of #172 needs. */
    @Test
    void dupliquerSansPreciserRameneLesAnimateurs() {
        given().header(HEADER, DEFAUT)
                .contentType("application/json")
                .body("{\"id\":\"ANIM-DEFAUT\",\"prenom\":\"Grace\",\"nom\":\"Hopper\","
                        + "\"dateNaissance\":\"1990-01-01\"}")
                .when()
                .post("/api/animateurs")
                .then()
                .statusCode(200);

        given().contentType("application/json")
                .body("{\"id\":\"COPIE-AVEC\",\"nom\":\"Copie avec\"}")
                .when()
                .post("/api/editions/" + DEFAUT + "/dupliquer")
                .then()
                .statusCode(200);

        given().header(HEADER, "COPIE-AVEC")
                .when()
                .get("/api/animateurs")
                .then()
                .statusCode(200)
                .body("id", org.hamcrest.Matchers.hasItem("ANIM-DEFAUT"));
    }

    /**
     * The settings a duplication carries, read back on the copy: a column left
     * out of {@code TABLES_A_COPIER} silently comes back at its {@code DEFAULT}
     * instead, and nothing at duplication time complains. Three had drifted
     * that way — the evening hour (V74), the end-of-solve mail, and the
     * polyvalent typologie. Values are chosen to differ from every default, or
     * the assertion would pass on the bug.
     *
     * <p>Source and copy are both editions this test creates, so
     * {@link #supprimerLesEditionsCreees} takes them away: writing the settings
     * into {@code DEFAUT} would leave it reconfigured for every test that runs
     * after this one, and its {@code ninja} typologie would 409 the next run.</p>
     */
    @Test
    void dupliquerRecopieLesReglagesEtPasSeulementLeursDefauts() {
        given().contentType("application/json")
                .body("{\"id\":\"SOURCE-REGLAGES\",\"nom\":\"Source réglages\"}")
                .when()
                .post("/api/editions")
                .then()
                .statusCode(200);
        given().header(HEADER, "SOURCE-REGLAGES")
                .contentType("application/json")
                .body("{\"dureeHebdomadaireMaxMinutes\":2400,\"dureeHebdomadaireMaxMineurMinutes\":1800,"
                        + "\"dureePauseMinutes\":45,\"reposQuotidienMinimalMinutes\":720,"
                        + "\"coupureRepasMinutes\":50,"
                        + "\"coupureRepasMidiDebut\":\"11:30:00\",\"coupureRepasMidiFin\":\"13:30:00\","
                        + "\"coupureRepasSoirDebut\":\"18:30:00\",\"coupureRepasSoirFin\":\"20:30:00\","
                        + "\"heureDebutSoiree\":\"22:00:00\",\"dureeVacationMaxMinutes\":300}")
                .when()
                .put("/api/parametres-legaux")
                .then()
                .statusCode(200);
        given().header(HEADER, "SOURCE-REGLAGES")
                .contentType("application/json")
                .body("{\"dureeResolutionSecondes\":123,\"mailFinResolution\":true}")
                .when()
                .put("/api/parametres-solveur")
                .then()
                .statusCode(200);
        given().header(HEADER, "SOURCE-REGLAGES")
                .contentType("application/json")
                .body("{\"id\":\"POLYVALENT\",\"label\":\"Polyvalent\",\"ninja\":true}")
                .when()
                .post("/api/typologies")
                .then()
                .statusCode(200);

        given().contentType("application/json")
                .body("{\"id\":\"COPIE-REGLAGES\",\"nom\":\"Copie réglages\"}")
                .when()
                .post("/api/editions/SOURCE-REGLAGES/dupliquer")
                .then()
                .statusCode(200);

        given().header(HEADER, "COPIE-REGLAGES")
                .when()
                .get("/api/parametres-legaux")
                .then()
                .statusCode(200)
                .body("heureDebutSoiree", org.hamcrest.Matchers.equalTo("22:00:00"))
                .body("dureeVacationMaxMinutes", org.hamcrest.Matchers.equalTo(300))
                .body("dureePauseMinutes", org.hamcrest.Matchers.equalTo(45))
                .body("coupureRepasMinutes", org.hamcrest.Matchers.equalTo(50));
        given().header(HEADER, "COPIE-REGLAGES")
                .when()
                .get("/api/parametres-solveur")
                .then()
                .statusCode(200)
                .body("mailFinResolution", org.hamcrest.Matchers.equalTo(true))
                .body("dureeResolutionSecondes", org.hamcrest.Matchers.equalTo(123));
        given().header(HEADER, "COPIE-REGLAGES")
                .when()
                .get("/api/typologies")
                .then()
                .statusCode(200)
                .body("find { it.id == 'POLYVALENT' }.ninja", org.hamcrest.Matchers.equalTo(true));
    }

    @Test
    void uneEditionDupliqueeEstIndependanteDeSaSource() {
        createStand(DEFAUT, "STAND-PARTAGE");
        given().contentType("application/json")
                .body("{\"id\":\"COPIE-2026\",\"nom\":\"Copie 2026\"}")
                .when()
                .post("/api/editions/" + DEFAUT + "/dupliquer")
                .then()
                .statusCode(200);

        given().header(HEADER, "COPIE-2026")
                .when()
                .delete("/api/stands/STAND-PARTAGE")
                .then()
                .statusCode(204);

        assertThat(listStandIds("COPIE-2026")).doesNotContain("STAND-PARTAGE");
        assertThat(listStandIds(DEFAUT)).contains("STAND-PARTAGE");
    }

    @Test
    void supprimerLEditionParDefautEstRefuse() {
        createEdition("ANNEE-2026", "Année 2026");

        given().header(HEADER, "ANNEE-2026")
                .when()
                .delete("/api/editions/" + DEFAUT)
                .then()
                .statusCode(400);
    }

    @Test
    void supprimerLEditionCouranteEstRefuse() {
        createEdition("ANNEE-2026", "Année 2026");

        given().header(HEADER, "ANNEE-2026")
                .when()
                .delete("/api/editions/ANNEE-2026")
                .then()
                .statusCode(400);
    }

    @Test
    void creerDeuxFoisLeMemeIdentifiantEstRefuse() {
        createEdition("ANNEE-2026", "Année 2026");

        given().contentType("application/json")
                .body("{\"id\":\"ANNEE-2026\",\"nom\":\"Doublon\"}")
                .when()
                .post("/api/editions")
                .then()
                .statusCode(400);
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
