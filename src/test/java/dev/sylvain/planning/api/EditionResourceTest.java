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
 *
 * <p>Every id is drawn by the application (ADR 0050): an edition, a stand or a
 * typologie is designated by what its creation answered, never by a value the
 * test chose.</p>
 */
@QuarkusTest
class EditionResourceTest {

    private static final String HEADER = "X-Edition-Id";

    /**
     * Every test creates its own edition(s); dropping them afterwards keeps the
     * shared dev-services database as this class found it. The default edition
     * is never touched, so an unrelated test never sees a leftover edition.
     */
    @AfterEach
    void dropCreatedEditions() {
        String defaut = defaultEdition();
        for (Map<String, Object> edition : listEditions()) {
            String id = (String) edition.get("id");
            if (!defaut.equals(id)) {
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

    private String defaultEdition() {
        return listEditions().stream()
                .filter(edition -> Boolean.TRUE.equals(edition.get("defaut")))
                .map(edition -> (String) edition.get("id"))
                .findFirst()
                .orElseThrow();
    }

    /** Creates an edition and answers the id the application gave it. */
    private String createEdition(String nom) {
        return given().contentType("application/json")
                .body("{\"nom\":\"" + nom + "\"}")
                .when()
                .post("/api/editions")
                .then()
                .statusCode(200)
                .extract()
                .path("id");
    }

    private String duplicate(String sourceId, String nom, String query) {
        return given().contentType("application/json")
                .body("{\"nom\":\"" + nom + "\"}")
                .when()
                .post("/api/editions/" + sourceId + "/dupliquer" + query)
                .then()
                .statusCode(200)
                .extract()
                .path("id");
    }

    /**
     * A stand always carries a typologie (issue #343): the edition's
     * {@code TYPO} one, created on first use and cited by its code.
     */
    private String createStand(String editionId, String nom) {
        boolean typologieExiste = given().header(HEADER, editionId)
                .when()
                .get("/api/typologies")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getList("code", String.class)
                .contains("TYPO");
        if (!typologieExiste) {
            createTypologie(editionId, "TYPO", false);
        }
        return given().header(HEADER, editionId)
                .contentType("application/json")
                .body("{\"nom\":\"" + nom
                        + "\",\"typologiesProposees\":[\"TYPO\"],\"effectifMin\":1,\"effectifMax\":2}")
                .when()
                .post("/api/stands")
                .then()
                .statusCode(200)
                .extract()
                .path("stand.id");
    }

    private String createTypologie(String editionId, String code, boolean ninja) {
        return given().header(HEADER, editionId)
                .contentType("application/json")
                .body("{\"code\":\"" + code + "\",\"label\":\"" + code + "\",\"ninja\":" + ninja + "}")
                .when()
                .post("/api/typologies")
                .then()
                .statusCode(200)
                .extract()
                .path("id");
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
    void aFreshDatabaseHoldsASingleDefaultEditionNumberedLikeAnyOther() {
        assertThat(listEditions())
                .filteredOn(edition -> Boolean.TRUE.equals(edition.get("defaut")))
                .singleElement()
                .satisfies(edition -> assertThat((String) edition.get("id")).matches("E[1-9][0-9]*"));
    }

    @Test
    void theDataOfAnEditionAreInvisibleFromAnother() {
        String edition = createEdition("Année 2026");
        String stand = createStand(edition, "Stand 2026");

        assertThat(listStandIds(edition)).contains(stand);
        assertThat(given().header(HEADER, defaultEdition())
                        .when()
                        .get("/api/stands")
                        .then()
                        .statusCode(200)
                        .extract()
                        .jsonPath()
                        .getList("nom", String.class))
                .doesNotContain("Stand 2026");
    }

    /**
     * Numbered per edition: two fresh editions both start from {@code S1}, and
     * the same id designates a different stand in each — exactly what the
     * composite {@code (edition_id, id)} primary keys make possible.
     */
    @Test
    void twoEditionsNumberTheirStandsEachFromOne() {
        String premiere = createEdition("Première");
        String seconde = createEdition("Seconde");
        String standPremiere = createStand(premiere, "Tir à la corde");
        String standSeconde = createStand(seconde, "Tir à la corde");

        assertThat(standPremiere).isEqualTo("S1");
        assertThat(standSeconde).isEqualTo("S1");

        given().header(HEADER, seconde)
                .when()
                .delete("/api/stands/" + standSeconde)
                .then()
                .statusCode(204);

        assertThat(listStandIds(seconde)).doesNotContain("S1");
        assertThat(listStandIds(premiere)).contains("S1");
        assertThat(createStand(seconde, "Remplaçant"))
                .as("a number freed by a deletion is never handed out again")
                .isEqualTo("S2");
    }

    @Test
    void anUnknownHeaderFallsBackOnTheDefaultEditionWithoutFailing() {
        String defaut = defaultEdition();
        String stand = createStand(defaut, "Stand repli");

        // A tab left open on a since-deleted edition must keep working.
        assertThat(listStandIds("EDITION-QUI-NEXISTE-PAS")).contains(stand);

        given().header(HEADER, "EDITION-QUI-NEXISTE-PAS")
                .when()
                .get("/api/editions/courant")
                .then()
                .statusCode(200)
                .body("id", org.hamcrest.Matchers.equalTo(defaut));
        given().header(HEADER, defaut).when().delete("/api/stands/" + stand);
    }

    @Test
    void duplicatingAnEditionCopiesItsReferentialButNotItsAssignments() {
        String source = createEdition("Source 2026");
        String stand = createStand(source, "Stand à copier");

        String copie = duplicate(source, "Copie 2026", "");

        assertThat(copie).matches("E[1-9][0-9]*").isNotEqualTo(source);
        assertThat(listStandIds(copie)).contains(stand);
        given().header(HEADER, copie)
                .when()
                .get("/api/planning/persisted/count")
                .then()
                .statusCode(200)
                .body("assignments", org.hamcrest.Matchers.equalTo(0));
    }

    /**
     * The ids travel with a duplication — and so do the counters that drew
     * them: the copy's next stand must follow the ones it inherited, not
     * collide with the first of them.
     */
    @Test
    void aDuplicateNumbersItsNextRowsAfterTheOnesItInherited() {
        String source = createEdition("Source compteurs");
        String premier = createStand(source, "Premier");
        String second = createStand(source, "Second");

        String copie = duplicate(source, "Copie compteurs", "");

        assertThat(listStandIds(copie)).containsExactlyInAnyOrder(premier, second);
        assertThat(createStand(copie, "Troisième")).isEqualTo("S3");
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
    void duplicatingCopiesEmailAndHoursButMintsAFreshToken() {
        String source = createEdition("Source jetons");
        createTypologie(source, "STRATEGIE", false);
        String animateur = given().header(HEADER, source)
                .contentType("application/json")
                .body("{\"prenom\":\"Ada\",\"nom\":\"Lovelace\","
                        + "\"dateNaissance\":\"1990-01-01\",\"email\":\"ada@example.org\"}")
                .when()
                .post("/api/animateurs")
                .then()
                .statusCode(200)
                .extract()
                .path("animateur.id");
        String stand = given().header(HEADER, source)
                .contentType("application/json")
                .body(
                        "{\"code\":\"STAND-HORAIRE\",\"nom\":\"Stand à règles\",\"typologiesProposees\":[\"STRATEGIE\"],\"effectifMin\":1,\"effectifMax\":2,"
                                + "\"horaires\":[{\"mode\":\"FERMETURE\",\"typeJours\":\"TOUS\","
                                + "\"fenetres\":[{\"heureDebut\":\"09:00:00\",\"heureFin\":\"10:00:00\"}]}]}")
                .when()
                .post("/api/stands")
                .then()
                .statusCode(200)
                .extract()
                .path("stand.id");
        String sourceToken = given().header(HEADER, source)
                .when()
                .get("/api/animateurs")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getString("find { it.id == '" + animateur + "' }.accessToken");

        String copie = duplicate(source, "Copie jetons", "");

        given().header(HEADER, copie)
                .when()
                .get("/api/animateurs")
                .then()
                .statusCode(200)
                .body("find { it.id == '" + animateur + "' }.email", org.hamcrest.Matchers.equalTo("ada@example.org"))
                .body(
                        "find { it.id == '" + animateur + "' }.accessToken",
                        org.hamcrest.Matchers.allOf(
                                org.hamcrest.Matchers.notNullValue(), org.hamcrest.Matchers.not(sourceToken)));
        given().header(HEADER, copie)
                .when()
                .get("/api/stands")
                .then()
                .statusCode(200)
                .body("find { it.id == '" + stand + "' }.code", org.hamcrest.Matchers.equalTo("STAND-HORAIRE"))
                .body("find { it.id == '" + stand + "' }.horaires.size()", org.hamcrest.Matchers.equalTo(1))
                .body(
                        "find { it.id == '" + stand + "' }.horaires[0].fenetres[0].heureDebut",
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
    void duplicatingWithoutTheAnimateursKeepsTheStructureAndNobody() {
        String source = createEdition("Source modèle");
        String stand = createStand(source, "Stand modèle");
        String animateur = given().header(HEADER, source)
                .contentType("application/json")
                .body("{\"prenom\":\"Ada\",\"nom\":\"Lovelace\","
                        + "\"dateNaissance\":\"1990-01-01\",\"email\":\"ada@example.org\"}")
                .when()
                .post("/api/animateurs")
                .then()
                .statusCode(200)
                .extract()
                .path("animateur.id");
        given().header(HEADER, source)
                .contentType("application/json")
                .body("{\"type\":\"INDISPONIBILITE_FORCEE\"," + "\"animateursConcernes\":[{\"id\":\"" + animateur
                        + "\"}],\"raison\":\"Absent\"}")
                .when()
                .post("/api/contraintes-ad-hoc")
                .then()
                .statusCode(200);

        String modele = duplicate(source, "Modèle 2027", "?avecAnimateurs=false");

        assertThat(listStandIds(modele)).contains(stand);
        given().header(HEADER, modele)
                .when()
                .get("/api/animateurs")
                .then()
                .statusCode(200)
                .body("size()", org.hamcrest.Matchers.equalTo(0));
        given().header(HEADER, modele)
                .when()
                .get("/api/contraintes-ad-hoc")
                .then()
                .statusCode(200)
                .body("size()", org.hamcrest.Matchers.equalTo(0));
    }

    /** The default is unchanged: the people follow, as the « plan canicule » ritual of #172 needs. */
    @Test
    void duplicatingWithoutSayingBringsTheAnimateurs() {
        String source = createEdition("Source avec");
        String animateur = given().header(HEADER, source)
                .contentType("application/json")
                .body("{\"prenom\":\"Grace\",\"nom\":\"Hopper\",\"dateNaissance\":\"1990-01-01\"}")
                .when()
                .post("/api/animateurs")
                .then()
                .statusCode(200)
                .extract()
                .path("animateur.id");

        String copie = duplicate(source, "Copie avec", "");

        given().header(HEADER, copie)
                .when()
                .get("/api/animateurs")
                .then()
                .statusCode(200)
                .body("id", org.hamcrest.Matchers.hasItem(animateur));
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
     * {@link #dropCreatedEditions} takes them away: writing the settings into
     * the default edition would leave it reconfigured for every test that runs
     * after this one.</p>
     */
    @Test
    void duplicatingCopiesTheSettingsAndNotJustTheirDefaults() {
        String source = createEdition("Source réglages");
        given().header(HEADER, source)
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
        given().header(HEADER, source)
                .contentType("application/json")
                .body("{\"dureeResolutionSecondes\":123,\"mailFinResolution\":true}")
                .when()
                .put("/api/parametres-solveur")
                .then()
                .statusCode(200);
        String polyvalent = createTypologie(source, "POLYVALENT", true);

        String copie = duplicate(source, "Copie réglages", "");

        given().header(HEADER, copie)
                .when()
                .get("/api/parametres-legaux")
                .then()
                .statusCode(200)
                .body("heureDebutSoiree", org.hamcrest.Matchers.equalTo("22:00:00"))
                .body("dureeVacationMaxMinutes", org.hamcrest.Matchers.equalTo(300))
                .body("dureePauseMinutes", org.hamcrest.Matchers.equalTo(45))
                .body("coupureRepasMinutes", org.hamcrest.Matchers.equalTo(50));
        given().header(HEADER, copie)
                .when()
                .get("/api/parametres-solveur")
                .then()
                .statusCode(200)
                .body("mailFinResolution", org.hamcrest.Matchers.equalTo(true))
                .body("dureeResolutionSecondes", org.hamcrest.Matchers.equalTo(123));
        given().header(HEADER, copie)
                .when()
                .get("/api/typologies")
                .then()
                .statusCode(200)
                .body("find { it.id == '" + polyvalent + "' }.ninja", org.hamcrest.Matchers.equalTo(true))
                .body("find { it.id == '" + polyvalent + "' }.code", org.hamcrest.Matchers.equalTo("POLYVALENT"));
    }

    @Test
    void aDuplicatedEditionIsIndependentOfItsSource() {
        String source = createEdition("Source partage");
        String stand = createStand(source, "Stand partagé");
        String copie = duplicate(source, "Copie partage", "");

        given().header(HEADER, copie)
                .when()
                .delete("/api/stands/" + stand)
                .then()
                .statusCode(204);

        assertThat(listStandIds(copie)).doesNotContain(stand);
        assertThat(listStandIds(source)).contains(stand);
    }

    @Test
    void deletingTheDefaultEditionIsRefused() {
        String edition = createEdition("Année 2026");

        given().header(HEADER, edition)
                .when()
                .delete("/api/editions/" + defaultEdition())
                .then()
                .statusCode(400);
    }

    @Test
    void deletingTheCurrentEditionIsRefused() {
        String edition = createEdition("Année 2026");

        given().header(HEADER, edition)
                .when()
                .delete("/api/editions/" + edition)
                .then()
                .statusCode(400);
    }

    /** An id sent in the body is ignored: two creations are two editions, never a 409 on a chosen id. */
    @Test
    void anIdSentOnCreationIsIgnored() {
        String premiere = given().contentType("application/json")
                .body("{\"id\":\"ANNEE-2026\",\"nom\":\"Année 2026\"}")
                .when()
                .post("/api/editions")
                .then()
                .statusCode(200)
                .extract()
                .path("id");
        String seconde = given().contentType("application/json")
                .body("{\"id\":\"ANNEE-2026\",\"nom\":\"Doublon\"}")
                .when()
                .post("/api/editions")
                .then()
                .statusCode(200)
                .extract()
                .path("id");

        assertThat(premiere).matches("E[1-9][0-9]*");
        assertThat(seconde).matches("E[1-9][0-9]*").isNotEqualTo(premiere);
    }

    /**
     * A name shaped like an edition id is refused (D5 of ADR 0050): the MCP
     * {@code edition} argument tries the id first, so « E2 » as a name would
     * designate another edition. A plain year stays a fine name.
     */
    @Test
    void aNameShapedLikeAnEditionIdIsRefused() {
        given().contentType("application/json")
                .body("{\"nom\":\"e12\"}")
                .when()
                .post("/api/editions")
                .then()
                .statusCode(400);
        String annee = createEdition("2027");
        given().contentType("application/json")
                .body("{\"nom\":\"E7\"}")
                .when()
                .put("/api/editions/" + annee)
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
    void flaggingANinjaTypologieDoesNotUnflagAnotherEditionS() {
        String annee2025 = createEdition("Année 2025");
        String annee2026 = createEdition("Année 2026");
        String ninja2025 = createTypologie(annee2025, "NINJA_2025", true);

        String ninja2026 = createTypologie(annee2026, "NINJA_2026", true);

        assertThat(isNinja(annee2026, ninja2026)).isTrue();
        assertThat(isNinja(annee2025, ninja2025)).isTrue();
    }
}
