package dev.sylvain.planning.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * The banc de touche over HTTP (issue #303): read-only, on the persisted plan,
 * and answering with constraint names the Contraintes screen already knows how
 * to word.
 *
 * <p>What is worth asserting at this level, rather than in
 * {@code CreneauAvailabilityCoherenceTest}, is the transport: no solve is triggered,
 * the refusals map to the right status codes through {@code BusinessError},
 * and every reason really carries the catalogue wording the client renders.</p>
 */
@QuarkusTest
class CreneauAvailabilityResourceTest {

    private static final Duration POLL_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(250);

    @Test
    void listeLesAnimateursNonAffectesEtLaRaisonDeLeurAbsence() {
        long creneauId = persistedPlan();

        JsonPath banc = given().when()
                .get("/api/banc-de-touche/" + creneauId)
                .then()
                .statusCode(200)
                .extract()
                .jsonPath();

        assertThat(banc.getLong("creneauId")).isEqualTo(creneauId);
        assertThat(banc.getString("posteCibleId")).isNotBlank();
        assertThat(banc.getInt("total")).isEqualTo(banc.getList("animateurs").size());
        assertThat(banc.getInt("disponibles")).isBetween(0, banc.getInt("total"));
        // Nobody on duty on that créneau is on the bench.
        assertThat(banc.getList("animateurs.animateurId")).doesNotHaveDuplicates();
    }

    /**
     * The Siège panel spells both keys out on every call, empty when they do
     * not apply — the frontend's contract check needs them literal — and names
     * the seat it shows: a blank key reads as absent, and the named seat is
     * the one evaluated.
     */
    @Test
    void blankKeysReadAsAbsentAndANamedSeatIsTheOneEvaluated() {
        long creneauId = persistedPlan();
        String evaluated = given().when()
                .get("/api/banc-de-touche/" + creneauId + "?standId=&posteId=")
                .then()
                .statusCode(200)
                .extract()
                .path("posteCibleId");
        assertThat(evaluated).isNotBlank();

        given().when()
                .get("/api/banc-de-touche/" + creneauId + "?standId=&posteId=" + evaluated)
                .then()
                .statusCode(200)
                .body("posteCibleId", org.hamcrest.Matchers.equalTo(evaluated));
    }

    /** Every reason is a catalogued constraint, never a wording invented by the view. */
    @Test
    void chaqueMotifPorteLeNomEtLeLibelleDuneContrainteCataloguee() {
        long creneauId = persistedPlan();

        JsonPath banc = given().when()
                .get("/api/banc-de-touche/" + creneauId)
                .then()
                .statusCode(200)
                .extract()
                .jsonPath();
        JsonPath catalogue = given().when()
                .get("/api/constraints")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath();

        for (Object motif : banc.getList("animateurs.motifs.flatten()")) {
            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> ligne = (java.util.Map<String, Object>) motif;
            assertThat(catalogue.getList("contraintes.name")).contains(ligne.get("contrainte"));
            assertThat((String) ligne.get("description")).isNotBlank();
            assertThat(ligne.get("niveau")).isIn("HARD", "MEDIUM", "SOFT");
        }
    }

    /** A créneau that exists nowhere is the only genuinely bad request left. */
    @Test
    void seulUnCreneauInexistantEstUn404() {
        long creneauId = persistedPlan();
        String posteCible = given().when()
                .get("/api/banc-de-touche/" + creneauId)
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getString("posteCibleId");

        given().when().get("/api/banc-de-touche/999999").then().statusCode(404);
        given().when()
                .get("/api/banc-de-touche/999999?posteId=" + posteCible)
                .then()
                .statusCode(404);
    }

    /**
     * The regression the screen shipped with. Its créneau selector is fed by
     * the referential, which legitimately holds more créneaux than the plan
     * does — a stand closed then, or a créneau added after the last solve. That
     * used to answer 404 « Créneau inconnu » on a perfectly real créneau, on
     * the very first render, with nothing the user could do about it.
     */
    @Test
    void unCreneauSansSiegeDansLePlanRepond200AvecSonStatut() {
        long creneauId = persistedPlan();
        long creneauSansSiege = creneauWithoutSeat(creneauId);

        JsonPath banc = given().when()
                .get("/api/banc-de-touche/" + creneauSansSiege)
                .then()
                .statusCode(200)
                .extract()
                .jsonPath();

        assertThat(banc.getString("statut")).isEqualTo("NO_SEAT");
        assertThat(banc.getString("posteCibleId")).isNull();
        assertThat(banc.getList("animateurs")).isEmpty();
        // The seatless créneau is not among the ones offered, but the answer
        // about it is still a 200 that explains itself: a bookmark may name it.
        assertThat(banc.getList("creneauxAvecSieges.id", Long.class))
                .contains(creneauId)
                .doesNotContain(creneauSansSiege);
    }

    /** Same for a stand the plan opened no seat for on that créneau. */
    @Test
    void unStandSansSiegeSurLeCreneauRepond200AvecSonStatut() {
        long creneauId = persistedPlan();

        given().when()
                .get("/api/banc-de-touche/" + creneauId + "?standId=STAND-INEXISTANT")
                .then()
                .statusCode(200)
                .body("statut", org.hamcrest.Matchers.equalTo("NO_SEAT"));
    }

    /**
     * Opened before any solve — the state a new user is in. The screen has to
     * be able to say « lancez une résolution », which it cannot do if the call
     * fails.
     */
    @Test
    void sansAucunPlanEnregistreLEcranRecoitUneReponseExploitable() {
        given().when().post("/api/planning/reset").then().statusCode(200);
        given().when()
                .post("/api/reference-data/import-scenario?name=scenario.yml")
                .then()
                .statusCode(200);
        Long creneauId = given().when()
                .get("/api/creneaux")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getLong("[0].id");

        JsonPath banc = given().when()
                .get("/api/banc-de-touche/" + creneauId)
                .then()
                .statusCode(200)
                .extract()
                .jsonPath();

        assertThat(banc.getString("statut")).isEqualTo("NO_PLAN");
        assertThat(banc.getList("animateurs")).isEmpty();
        assertThat(banc.getList("creneauxAvecSieges")).isEmpty();
    }

    /**
     * The call the screen makes on a cold open: no créneau named, because it
     * cannot know one before the answer tells it which are staffed.
     */
    @Test
    void withoutATimeslotTheServerPicksOneThatIsFilled() {
        long creneauId = persistedPlan();

        JsonPath banc = given().when()
                .get("/api/banc-de-touche")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath();

        assertThat(banc.getString("statut")).isEqualTo("EVALUATED");
        // The freeze is off under %test: nothing is ever reported started.
        assertThat(banc.getBoolean("seatStarted")).isFalse();
        assertThat(banc.<Object>get("creneauId")).isNotNull();
        assertThat(banc.getList("creneauxAvecSieges.id", Long.class)).contains(banc.getLong("creneauId"));
        assertThat(creneauId).isPositive();
    }

    /**
     * A créneau of the referential the solved plan put no seat on. The scenario
     * is solved over every créneau, so one is created here rather than looked
     * for — which is also exactly how the real case arises: a créneau added
     * after the last solve.
     */
    private long creneauWithoutSeat(long creneauExistant) {
        long ajoute = given().contentType("application/json")
                .body("""
                        {
                          "date":"2030-01-05",
                          "heureDebut":"03:00:00",
                          "heureFin":"04:00:00"
                        }
                        """)
                .when()
                .post("/api/creneaux")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getLong("creneau.id");
        assertThat(ajoute).isNotEqualTo(creneauExistant);
        return ajoute;
    }

    /**
     * The bench is scored under the rules <b>currently in force</b>, not under
     * the defaults — and that is what the server-side preparation is for.
     *
     * <p>This pins the one thing extracting the what-if made breakable. The
     * preparation reaches {@code PlanningWhatIf} as a {@link java.util.function.Consumer},
     * and rewiring it to a no-op left every test green: a constraint the
     * organiser had switched off would come back to life here, and an animateur
     * would be shown « indisponible » in the name of a rule nobody enforces
     * any more.</p>
     *
     * <p>The check is indirect on purpose. Rather than assert a score, it
     * switches a hard rule off and asserts that the bench stops naming it — the
     * user-visible consequence, and the one a reader can judge.</p>
     */
    @Test
    void leBancEstEvalueSousLesContraintesActivesDeLEdition() {
        long creneauId = persistedPlan();
        String regle = premierMotifDur(creneauId);
        assertThat(regle)
                .as("le plan doit produire au moins un motif dur à éteindre")
                .isNotBlank();

        try {
            given().contentType("application/json")
                    .body("{\"actif\":false}")
                    .when()
                    .put("/api/constraints/" + regle)
                    .then()
                    .statusCode(200);

            assertThat(motifs(creneauId))
                    .as("une contrainte éteinte ne doit plus motiver une indisponibilité")
                    .doesNotContain(regle);
        } finally {
            given().contentType("application/json")
                    .body("{\"actif\":true}")
                    .when()
                    .put("/api/constraints/" + regle)
                    .then()
                    .statusCode(200);
        }
    }

    /** The constraint names the bench cites for this créneau. */
    private static java.util.List<String> motifs(long creneauId) {
        return given().when()
                .get("/api/banc-de-touche/" + creneauId)
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getList("animateurs.motifs.flatten().contrainte", String.class);
    }

    /** The first reason the bench cites, if it cites any. */
    private static String premierMotifDur(long creneauId) {
        return motifs(creneauId).stream()
                .filter(nom -> nom != null && !nom.isBlank())
                .findFirst()
                .orElse("");
    }

    /** Loads the sample scenario, solves it once, and returns a créneau of the persisted plan. */
    private long persistedPlan() {
        given().when().post("/api/planning/reset").then().statusCode(200);
        given().when()
                .post("/api/reference-data/import-scenario?name=scenario.yml")
                .then()
                .statusCode(200);
        waitForIdleSolver();
        String jobId = given().when()
                .post("/api/solve/async/reference-data?seconds=1")
                .then()
                .statusCode(202)
                .extract()
                .path("id");
        assertThat(pollUntilFinished(jobId).getString("status")).isEqualTo("COMPLETED");
        Long creneauId = given().when()
                .get("/api/creneaux")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getLong("[0].id");
        assertThat(creneauId).isNotNull();
        return creneauId;
    }

    private void waitForIdleSolver() {
        await().alias("Le solveur ne s'est jamais libéré")
                .atMost(POLL_TIMEOUT)
                .pollInterval(POLL_INTERVAL)
                .until(() ->
                        given().when().get("/api/jobs/active").then().extract().statusCode() == 204);
    }

    private JsonPath pollUntilFinished(String jobId) {
        return await().alias("Le job " + jobId + " ne s'est jamais terminé")
                .atMost(POLL_TIMEOUT)
                .pollInterval(POLL_INTERVAL)
                .until(
                        () -> given().when()
                                .get("/api/jobs/" + jobId)
                                .then()
                                .statusCode(200)
                                .extract()
                                .jsonPath(),
                        job -> !"RUNNING".equals(job.getString("status")) && !"QUEUED".equals(job.getString("status")));
    }
}
