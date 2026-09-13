package dev.sylvain.planning.service.scenario;

import static dev.sylvain.planning.service.scenario.ScenarioLadder.assertFeasible;
import static dev.sylvain.planning.service.scenario.ScenarioLadder.seatCountByStandAndDate;
import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.service.EditionContext;
import dev.sylvain.planning.service.solve.PlanningService;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The ladder files whose meaning lives in what the import writes — day
 * templates, recurring openings, the découpage, a target edition — played
 * through the real import and the real problem build, then solved.
 *
 * <p>Each test also compares the seats production builds with the ones
 * {@link ScenarioLadder} builds without a database. The plain harness is what
 * the rest of the ladder runs on; this is where it is held to the pipeline it
 * claims to mirror.</p>
 */
@QuarkusTest
class ScenarioLadderImportTest {

    private static final String HEADER = "X-Edition-Id";
    private static final String EDITION = "LADDER-IMPORT";
    private static final long CEILING_SECONDS = 120L;

    @Inject
    PlanningService planningService;

    @Inject
    EditionContext editionContext;

    @AfterEach
    void deleteTheLandingEditions() {
        given().when().delete("/api/editions/" + EDITION);
        given().when().delete("/api/editions/GAMME-10");
    }

    private void createLandingEdition() {
        given().contentType("application/json")
                .body("{\"id\":\"" + EDITION + "\",\"nom\":\"Gamme de scénarios\"}")
                .when()
                .post("/api/editions")
                .then()
                .statusCode(200);
    }

    private void importInto(String edition, String name) {
        given().header(HEADER, edition)
                .contentType("text/plain")
                .body(ScenarioLadder.yaml(name))
                .when()
                .post("/api/reference-data/import-scenario-fichier")
                .then()
                .statusCode(200);
    }

    private PlanningEvenement buildIn(String edition) {
        return editionContext.executeIn(edition, () -> planningService.buildFromReferenceData());
    }

    private PlanningEvenement solveIn(String edition, PlanningEvenement problem) {
        return editionContext.executeIn(edition, () -> planningService.solveUntilFeasible(problem, CEILING_SECONDS));
    }

    /** The day templates of the file govern its dates, and applying them right after the import moves nothing. */
    private void assertDayTemplatesLandedInStep(String edition, int templates) {
        given().header(HEADER, edition)
                .when()
                .get("/api/journees-types")
                .then()
                .statusCode(200)
                .body("journeesTypes", hasSize(templates))
                .body("datesEnEcart", hasSize(0));
        given().header(HEADER, edition)
                .when()
                .post("/api/journees-types/application/apercu")
                .then()
                .statusCode(200)
                .body("aucunChangement", equalTo(true));
    }

    private void assertSameSeatsAsTheHarness(PlanningEvenement production, String name) {
        assertThat(seatCountByStandAndDate(production.getPostes()))
                .isEqualTo(seatCountByStandAndDate(
                        ScenarioLadder.load(name).problem().getPostes()));
    }

    @Test
    void rung02MiddayRelayThroughTheImport() {
        String name = "gamme-02-1j-2stands-4animateurs-relais-midi";
        createLandingEdition();
        importInto(EDITION, name);
        assertDayTemplatesLandedInStep(EDITION, 1);

        PlanningEvenement problem = buildIn(EDITION);
        assertSameSeatsAsTheHarness(problem, name);

        assertFeasible(solveIn(EDITION, problem));
    }

    @Test
    void rung07RecurringOpeningsThroughTheImport() {
        String name = "gamme-07-3j-5stands-10animateurs-horaires-recurrents";
        createLandingEdition();
        importInto(EDITION, name);

        PlanningEvenement problem = buildIn(EDITION);
        assertSameSeatsAsTheHarness(problem, name);

        assertFeasible(solveIn(EDITION, problem));
    }

    @Test
    void rung08TheImportSlicesTheAmplitudesLikeTheHarness() {
        String name = "gamme-08-3j-5stands-16animateurs-decoupage-auto";
        createLandingEdition();
        given().header(HEADER, EDITION)
                .contentType("text/plain")
                .body(ScenarioLadder.yaml(name))
                .when()
                .post("/api/reference-data/import-scenario-fichier")
                .then()
                .statusCode(200)
                .body("decoupageAuto", equalTo(true));

        PlanningEvenement problem = buildIn(EDITION);
        assertSameSeatsAsTheHarness(problem, name);

        assertFeasible(solveIn(EDITION, problem));
    }

    /** The file names its own edition: the import creates it, whatever edition the caller sits on. */
    @Test
    void rung10TheFileLandsInTheEditionItNames() {
        String name = "gamme-10-4j-8stands-20animateurs-journees-types-multiples";
        given().contentType("text/plain")
                .body(ScenarioLadder.yaml(name))
                .when()
                .post("/api/reference-data/import-scenario-fichier")
                .then()
                .statusCode(200)
                .body("editionId", equalTo("GAMME-10"))
                .body("editionCreee", equalTo(true));
        assertDayTemplatesLandedInStep("GAMME-10", 3);

        PlanningEvenement problem = buildIn("GAMME-10");
        assertSameSeatsAsTheHarness(problem, name);

        assertFeasible(solveIn("GAMME-10", problem));
    }
}
