package dev.sylvain.planning.service.scenario;

import static dev.sylvain.planning.service.scenario.ScenarioLadder.load;
import static dev.sylvain.planning.service.scenario.ScenarioLadder.service;
import static org.assertj.core.api.Assertions.assertThat;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.PastHorizon;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.scenario.dto.TypologieDto;
import dev.sylvain.planning.service.analyse.FeasibilityAnalyzer.ConsecutiveDaysRule;
import dev.sylvain.planning.service.analyse.StaffingAnalyzer;
import dev.sylvain.planning.service.analyse.StaffingAnalyzer.StaffingRules;
import dev.sylvain.planning.service.referentiel.TypologieItem;
import dev.sylvain.planning.service.scenario.ScenarioLadder.Loaded;
import dev.sylvain.planning.service.solve.PlanningService;
import dev.sylvain.planning.service.solve.StaffingVerificationService;
import dev.sylvain.planning.solver.ConstraintCatalog;
import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The exact bound against the solve of a made-up team, on every feasible rung
 * of the scenario ladder (ADR 0097): the screen's answer by default is the
 * bound, and this is the test that says it is the answer a solve would give.
 *
 * <p>For each rung the bound {@code B} is read with {@link StaffingAnalyzer},
 * then a team of {@code B} adults is solved exactly as
 * {@link StaffingVerificationService} does it (hypothetical preparation, the
 * instance's engine, stop at the first feasible plan). Three outcomes:</p>
 * <ul>
 *   <li>feasible at {@code B}, and not at {@code B - 1}: the bound is the need;</li>
 *   <li>feasible at {@code B + k}, {@code k} at most {@link #MAX_GAP}: the bound
 *       is a floor and the solve needs what it leaves out (the table printed
 *       says which rungs, and it is the gap the ADR explains);</li>
 *   <li>feasible at {@code B - 1}: the bound would be wrong — a hard failure.</li>
 * </ul>
 *
 * <p>Tagged {@code scenario-lent}: run with {@code ./mvnw test -Pscenario-tests
 * -Dtest=StaffingBoundEquivalenceTest}, in the background, on a machine that
 * can spare its cores for an hour. {@code -Dequivalence.rungs=1,2,3} narrows it.</p>
 */
@Tag("scenario-lent")
class StaffingBoundEquivalenceTest {

    /** What a solve may need above the bound before the bound stops being a useful answer. */
    static final int MAX_GAP = 3;

    /** Seconds a solve of one team is given; a failure within it proves nothing, a success proves the team holds. */
    private static final long SOLVE_SECONDS = Long.getLong("equivalence.seconds", 300L);

    private record Row(String rung, int bound, int needed, boolean feasibleBelow, int gap) {}

    @Test
    void theBoundIsWhatAMadeUpTeamNeedsOnTheWholeLadder() throws IOException {
        String only = System.getProperty("equivalence.rungs");
        List<String> rungs = ScenariosLivres.noms("gamme-").stream()
                .filter(nom -> !nom.contains("infaisable"))
                .filter(nom -> only == null
                        || List.of(only.split(",")).contains(String.valueOf(Integer.parseInt(nom.substring(6, 8)))))
                .toList();
        assertThat(rungs).isNotEmpty();

        List<Row> rows = new ArrayList<>();
        for (String rung : rungs) {
            rows.add(measure(rung));
            System.out.println("EQUIVALENCE " + rows.getLast());
        }
        System.out.println("EQUIVALENCE rung | bound | needed | feasible below | gap");
        rows.forEach(row -> System.out.printf(
                "EQUIVALENCE %s | %d | %d | %b | %d%n",
                row.rung(), row.bound(), row.needed(), row.feasibleBelow(), row.gap()));

        assertThat(rows)
                .as("a team below the bound that holds every seat would make the bound wrong")
                .noneMatch(Row::feasibleBelow);
        assertThat(rows)
                .as("the solve needs at most MAX_GAP more than the bound")
                .allMatch(row -> row.gap() <= MAX_GAP);
    }

    private Row measure(String rung) {
        int bound = bound(rung);
        boolean below = bound > 1 && holds(rung, bound - 1, SOLVE_SECONDS / 5);
        int needed = bound;
        while (!below && needed <= bound + MAX_GAP && !holds(rung, needed, SOLVE_SECONDS)) {
            needed++;
        }
        return new Row(rung, bound, needed, below, needed - bound);
    }

    /** The exact minimum under the file's own rules, as the Besoin screen reads it. */
    private static int bound(String rung) {
        Loaded loaded = load(rung);
        PlanningEvenement problem = loaded.problem();
        service().prepareHypothetical(problem);
        return new StaffingAnalyzer()
                .analyze(
                        problem.getPostes(),
                        List.of(),
                        typologies(loaded),
                        rules(problem),
                        List.of(StaffingAnalyzer.ReferentielManquant.ANIMATEURS))
                .minimumTotal();
    }

    private static StaffingRules rules(PlanningEvenement problem) {
        var legaux = problem.getParametresLegaux().getFirst();
        ConsecutiveDaysRule jours = ConsecutiveDaysRule.of(problem);
        boolean repas = ConstraintCatalog.isActive(problem.getConstraintsDesactivees(), "coupureRepasObligatoire");
        return new StaffingRules(
                legaux.getDureeHebdomadaireMaxMinutes(),
                legaux.dureePauseMinutes(false),
                legaux.getDureeHebdomadaireMaxMineurMinutes(),
                repas ? problem.getFenetresRepas() : List.of(),
                jours != null && jours.hard() ? jours.cap() : null,
                ConstraintCatalog.isActive(problem.getConstraintsDesactivees(), "plafondCreneauxParTypologie"));
    }

    private static List<TypologieItem> typologies(Loaded loaded) {
        List<TypologieDto> dtos =
                loaded.dto().typologies() == null ? List.of() : loaded.dto().typologies();
        return dtos.stream()
                .map(dto -> new TypologieItem(
                        dto.id(),
                        dto.code(),
                        dto.label(),
                        Boolean.TRUE.equals(dto.ninja()),
                        dto.maxCreneauxParAnimateur(),
                        dto.description(),
                        null))
                .toList();
    }

    /** Whether {@code adults} made-up adults hold every seat of the rung, as a staffing check solves it. */
    private static boolean holds(String rung, int adults, long seconds) {
        Loaded loaded = load(rung);
        PlanningEvenement problem = loaded.problem();
        LocalDate first = problem.getPostes().stream()
                .map(poste -> poste.getCreneau().getDate())
                .min(LocalDate::compareTo)
                .orElseThrow();
        List<Animateur> team = StaffingVerificationService.team(adults, 0, typologies(loaded), first);
        problem.setAnimateurs(team);
        for (PosteAffectation poste : problem.getPostes()) {
            poste.setAnimateur(null);
            poste.setVerrouille(false);
        }
        problem.setContraintesAdHoc(List.of());
        problem.setVerrouillages(List.of());
        problem.setPastHorizon(new PastHorizon(first.minusDays(1), LocalTime.MIDNIGHT));
        PlanningService service = service();
        service.prepareHypothetical(problem);
        PlanningEvenement solved = service.solveCheck(problem, seconds, solver -> {});
        return solved.getScore().hardScore() == 0
                && solved.getPostes().stream().noneMatch(poste -> poste.getAnimateur() == null);
    }
}
