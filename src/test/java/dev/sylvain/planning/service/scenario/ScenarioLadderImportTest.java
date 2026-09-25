package dev.sylvain.planning.service.scenario;

import static dev.sylvain.planning.service.scenario.ScenarioLadder.assertFeasible;
import static dev.sylvain.planning.service.scenario.ScenarioLadder.seatCountByStandAndDate;
import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.service.EditionContext;
import dev.sylvain.planning.service.solve.PlanningService;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
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
    private static final String EDITION_NOM = "Gamme de scénarios";
    /** The name the {@code edition:} section of rung 10 designates its edition by. */
    private static final String GAMME_10_NOM = "Gamme 10 — journées types";

    private static final long CEILING_SECONDS = 120L;

    /**
     * The landing editions of the test under way, by the ids the application
     * drew for them (ADR 0050) — deleted after each test.
     */
    private final List<String> landingEditions = new ArrayList<>();

    @Inject
    PlanningService planningService;

    @Inject
    EditionContext editionContext;

    @AfterEach
    void deleteTheLandingEditions() {
        landingEditions.forEach(id -> given().when().delete("/api/editions/" + id));
        landingEditions.clear();
    }

    private String createLandingEdition() {
        String id = given().contentType("application/json")
                .body("{\"nom\":\"" + EDITION_NOM + "\"}")
                .when()
                .post("/api/editions")
                .then()
                .statusCode(200)
                .extract()
                .path("id");
        landingEditions.add(id);
        return id;
    }

    /** Ids of the editions carrying {@code nom} — what an interrupted earlier run may have left behind. */
    private static List<String> editionsNamed(String nom) {
        List<Map<String, Object>> editions = given().when()
                .get("/api/editions")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getList("$");
        return editions.stream()
                .filter(edition -> nom.equals(edition.get("nom")))
                .map(edition -> (String) edition.get("id"))
                .toList();
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

    /**
     * The harness keeps the file's stand ids; the import gives each stand a
     * generated id and keeps the file's reference as its code (ADR 0050). The
     * production seats are therefore keyed by code before the comparison.
     */
    private void assertSameSeatsAsTheHarness(PlanningEvenement production, String name) {
        assertThat(seatCountByStandCodeAndDate(production.getPostes()))
                .isEqualTo(seatCountByStandAndDate(
                        ScenarioLadder.load(name).problem().getPostes()));
    }

    private static Map<String, Map<LocalDate, Long>> seatCountByStandCodeAndDate(Collection<PosteAffectation> postes) {
        return postes.stream()
                .collect(Collectors.groupingBy(
                        poste -> poste.getStand().getCode() != null
                                ? poste.getStand().getCode()
                                : poste.getStand().getId(),
                        TreeMap::new,
                        Collectors.groupingBy(
                                poste -> poste.getCreneau().getDate(), TreeMap::new, Collectors.counting())));
    }

    @Test
    void rung02MiddayRelayThroughTheImport() {
        String name = "gamme-02-1j-2stands-4animateurs-relais-midi";
        String edition = createLandingEdition();
        importInto(edition, name);
        assertDayTemplatesLandedInStep(edition, 1);

        PlanningEvenement problem = buildIn(edition);
        assertSameSeatsAsTheHarness(problem, name);

        assertFeasible(solveIn(edition, problem));
    }

    @Test
    void rung07RecurringOpeningsThroughTheImport() {
        String name = "gamme-07-3j-5stands-10animateurs-horaires-recurrents";
        String edition = createLandingEdition();
        importInto(edition, name);

        PlanningEvenement problem = buildIn(edition);
        assertSameSeatsAsTheHarness(problem, name);

        assertFeasible(solveIn(edition, problem));
    }

    /**
     * The rung that used to be sliced at import: it states its relay vacations
     * outright now, and the imported grid must still be the one the plain-Java
     * harness builds from the same file.
     */
    @Test
    void rung08TheImportedGridMatchesTheHarness() {
        String name = "gamme-08-3j-5stands-16animateurs-relais-midi-reduit";
        String edition = createLandingEdition();
        importInto(edition, name);

        PlanningEvenement problem = buildIn(edition);
        assertSameSeatsAsTheHarness(problem, name);

        assertFeasible(solveIn(edition, problem));
    }

    /** The file names its own edition: the import creates it, whatever edition the caller sits on. */
    @Test
    void rung10TheFileLandsInTheEditionItNames() {
        String name = "gamme-10-4j-8stands-20animateurs-journees-types-multiples";
        // The section designates its edition by name once its id is unknown:
        // one left over by an interrupted run would be landed in, not created.
        editionsNamed(GAMME_10_NOM).forEach(id -> given().when().delete("/api/editions/" + id));
        // UTF-8 said out loud: the edition is now found by its name, and a
        // text/plain body defaults to ISO-8859-1, which mangles « — » and « é ».
        String edition = given().contentType("text/plain; charset=UTF-8")
                .body(ScenarioLadder.yaml(name).getBytes(StandardCharsets.UTF_8))
                .when()
                .post("/api/reference-data/import-scenario-fichier")
                .then()
                .statusCode(200)
                .body("editionCreee", equalTo(true))
                .extract()
                .path("editionId");
        landingEditions.add(edition);
        assertThat(editionsNamed(GAMME_10_NOM)).containsExactly(edition);
        assertDayTemplatesLandedInStep(edition, 3);

        PlanningEvenement problem = buildIn(edition);
        assertSameSeatsAsTheHarness(problem, name);

        assertFeasible(solveIn(edition, problem));
    }
}
