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
import dev.sylvain.planning.service.solve.PlanningPersistenceService;
import dev.sylvain.planning.service.solve.PlanningService;
import dev.sylvain.planning.service.solve.StaffingVerificationService;
import dev.sylvain.planning.solver.ConstraintCatalog;
import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
 * instance's engine, stop at the first feasible plan, started from the rung's
 * own plan and, failing that, from nothing), then {@code B + 1}, {@code B + 2},
 * {@code B + 3}, {@code B + 6}… up to the rung's real team, which holds by
 * construction. Three outcomes:</p>
 * <ul>
 *   <li>feasible at {@code B}, and not at {@code B - 1}: the bound is the need;</li>
 *   <li>feasible only above {@code B}: the bound is a floor and the solve needs
 *       what it leaves out — {@link #ALLOWED_GAPS} names the rungs where that
 *       is more than {@link #MAX_GAP}, with the figure last measured, so a rule
 *       that widens the gap breaks this test instead of the screen;</li>
 *   <li>feasible at {@code B - 1}: the bound would be wrong — a hard failure.</li>
 * </ul>
 * <p>{@code needed} is the first size of that grid that holds, so an upper
 * bound of the true need; a failure within the time given proves nothing, a
 * success proves the team holds.</p>
 *
 * <p>Tagged {@code scenario-lent}: run with {@code ./mvnw test -Pscenario-tests
 * -Dtest=StaffingBoundEquivalenceTest}, in the background, on a machine that
 * can spare its cores for an hour. {@code -Dequivalence.rungs=1,2,3} narrows it.</p>
 */
@Tag("scenario-lent")
class StaffingBoundEquivalenceTest {

    /** What a solve may need above the bound on a rung not named in {@link #ALLOWED_GAPS}. */
    static final int MAX_GAP = 2;

    /**
     * The rungs where the solve needs more than {@link #MAX_GAP} people above the
     * bound, by rung number, with the gap last measured (ADR 0098): 17 has the meal
     * break and the typology limit switched off and the search finds no plan under
     * its whole team, 18 and 20 break the overlap and the continuous-work rules
     * that the bound's day-by-day count does not see.
     */
    static final Map<Integer, Integer> ALLOWED_GAPS = Map.of(17, 43, 18, 24, 20, 12);

    /** Seconds a solve of one team is given; a failure within it proves nothing, a success proves the team holds. */
    private static final long SOLVE_SECONDS = Long.getLong("equivalence.seconds", 300L);

    /** @param broken the hard rules the best plan still breaks at the bound itself, empty when the bound holds */
    private record Row(
            String rung, int bound, int needed, boolean feasibleBelow, int gap, java.util.Set<String> broken) {}

    /** The outcome of one team: whether it holds every seat, and what its best plan breaks when it does not. */
    private record Attempt(boolean holds, java.util.Set<String> broken) {}

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
                "EQUIVALENCE %s | %d | %d | %b | %d | %s%n",
                row.rung(), row.bound(), row.needed(), row.feasibleBelow(), row.gap(), row.broken()));

        assertThat(rows)
                .as("a team below the bound that holds every seat would make the bound wrong")
                .noneMatch(Row::feasibleBelow);
        assertThat(rows)
                .as("the solve needs at most the gap measured for the rung more than the bound")
                .allMatch(row -> row.gap() >= 0 && row.gap() <= allowedGap(row.rung()));
    }

    private static int allowedGap(String rung) {
        return ALLOWED_GAPS.getOrDefault(Integer.parseInt(rung.substring(6, 8)), MAX_GAP);
    }

    private Row measure(String rung) {
        int bound = bound(rung);
        boolean below = bound > 1 && holds(rung, bound - 1, SOLVE_SECONDS / 5).holds();
        Attempt atBound = below ? new Attempt(true, java.util.Set.of()) : holds(rung, bound, SOLVE_SECONDS);
        int needed = bound;
        // Steps widen once the gap is plainly more than a person or two, up to the real team, which holds by
        // construction.
        int[] steps = {1, 2, 3, 6, 12, 24, 48};
        int real = load(rung).problem().getAnimateurs().size();
        for (int i = 0; !below && !atBound.holds() && i < steps.length && needed < real; i++) {
            needed = Math.min(bound + steps[i], real);
            if (holds(rung, needed, SOLVE_SECONDS).holds()) {
                break;
            }
            if (needed == real) {
                needed = -1;
            }
        }
        return new Row(rung, bound, needed, below, needed < 0 ? -1 : needed - bound, atBound.broken());
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

    /**
     * Whether {@code adults} made-up adults hold every seat of the rung, as a
     * staffing check solves it: from the plan in place when there is one, as
     * production does, and failing that from nothing — a team can hold the
     * seats and the search still miss them cold.
     */
    private static Attempt holds(String rung, int adults, long seconds) {
        Attempt fromPlan = holds(rung, adults, seconds * 2 / 3, true);
        return fromPlan.holds() ? fromPlan : holds(rung, adults, seconds / 3, false);
    }

    private static Attempt holds(String rung, int adults, long seconds, boolean fromPlan) {
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
        if (fromPlan) {
            // The edition's own plan, as production reads it from the database.
            Loaded real = load(rung);
            PlanningEvenement solvedReal = service().solveUntilFeasible(real.problem(), SOLVE_SECONDS);
            Map<String, List<String>> plan = new HashMap<>();
            for (PosteAffectation poste : solvedReal.getPostes()) {
                if (poste.getAnimateur() != null) {
                    plan.computeIfAbsent(
                                    PlanningPersistenceService.standCreneauKey(
                                            poste.getStand().getId(),
                                            poste.getCreneau().getId()),
                                    key -> new ArrayList<>())
                            .add(poste.getAnimateur().getId());
                }
            }
            StaffingVerificationService.seedFromPlan(
                    problem.getPostes(), team, adults, plan, solvedReal.getAnimateurs(), first);
        }
        problem.setContraintesAdHoc(List.of());
        problem.setVerrouillages(List.of());
        problem.setPastHorizon(new PastHorizon(first.minusDays(1), LocalTime.MIDNIGHT));
        PlanningService service = service();
        service.prepareHypothetical(problem);
        PlanningEvenement solved = service.solveCheck(problem, seconds, solver -> {});
        boolean complete = solved.getPostes().stream().noneMatch(poste -> poste.getAnimateur() == null);
        return new Attempt(
                solved.getScore().hardScore() == 0 && complete,
                solved.getScore().hardScore() == 0 ? java.util.Set.of() : ScenarioLadder.brokenHardConstraints(solved));
    }
}
