package dev.sylvain.planning.service.solve;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.ConstraintToggle;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.ParametresQualite;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.service.EmptyReferenceData;
import dev.sylvain.planning.service.analyse.FeasibilityAnalyzer;
import dev.sylvain.planning.service.analyse.StaffingAnalyzer;
import dev.sylvain.planning.service.analyse.StaffingAnalyzer.StaffingSummary;
import dev.sylvain.planning.service.referentiel.TypologieItem;
import dev.sylvain.planning.service.solve.ProblemBuilder.Seats;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.Test;

/**
 * The check the staffing screen offers, on the path it really takes —
 * {@link StaffingVerificationService#team}, {@link StaffingVerificationService#problem},
 * then the hypothetical preparation and the solve — without a container: a
 * week of seven days needing three people each.
 *
 * <p>The floor is four (21 person-days, six days each), and the point of the
 * check is that it is the answer, not merely a bound: four made-up people
 * staff the week, three cannot.</p>
 */
class StaffingVerificationSolveTest {

    private static final List<TypologieItem> TYPOLOGIES = List.of(new TypologieItem("JEUX", "Jeux"));

    private static final LocalDate LUNDI = LocalDate.of(2026, 7, 6);

    private final PlanningService planningService = new PlanningService(
            420L,
            0L,
            ParametresQualite.EMPLACEMENTS_DISTINCTS_PAR_JOUR_MAX_PAR_DEFAUT,
            new EmptyReferenceData(),
            new FeasibilityAnalyzer(),
            null,
            null,
            ConfigProvider.getConfig());

    @Test
    void theFloorStaffsTheWeekAndOneLessDoesNot() {
        Seats seats = week();
        StaffingSummary summary = new StaffingAnalyzer().analyze(seats.postes(), List.of(), TYPOLOGIES, 48 * 60, 30);
        assertThat(summary.minimumTotal()).isEqualTo(4);

        PlanningEvenement auPlancher = solve(week(), summary.minimumTotal(), 60);
        assertThat(auPlancher.getScore().hardScore()).isZero();
        assertThat(auPlancher.getPostes()).allMatch(poste -> poste.getAnimateur() != null);

        PlanningEvenement enDessous = solve(week(), summary.minimumTotal() - 1, 3);
        assertThat(enDessous.getScore().hardScore()).isNegative();
    }

    @Test
    void theTeamStartsFromThePlanInPlaceAndKeepsItWhenItIsAsLarge() {
        // A real plan of the week: four people, solved once.
        PlanningEvenement reel = solve(week(), 4, 60);
        assertThat(reel.getScore().hardScore()).isZero();
        Map<String, List<String>> plan = new HashMap<>();
        List<Animateur> reels = new ArrayList<>();
        for (PosteAffectation poste : reel.getPostes()) {
            String id = "R-" + poste.getAnimateur().getId();
            plan.computeIfAbsent(
                            PlanningPersistenceService.standCreneauKey(
                                    poste.getStand().getId(), poste.getCreneau().getId()),
                            cle -> new ArrayList<>())
                    .add(id);
            if (reels.stream().noneMatch(animateur -> animateur.getId().equals(id))) {
                reels.add(new Animateur(id, "R", id, LocalDate.of(1990, 1, 1), false));
            }
        }

        PlanningEvenement autant =
                StaffingVerificationService.problem(week(), StaffingVerificationService.team(4, 0, TYPOLOGIES, LUNDI));
        int seeded = StaffingVerificationService.seedFromPlan(
                autant.getPostes(), autant.getAnimateurs(), 4, plan, reels, LUNDI);

        assertThat(seeded).isEqualTo(21);
        assertThat(autant.getPostes()).allMatch(poste -> poste.getAnimateur() != null);
        planningService.prepareHypothetical(autant);
        assertThat(planningService
                        .solvePreparedUntilFeasible(autant, 5)
                        .getScore()
                        .hardScore())
                .isZero();

        // One person short: the busiest three keep their schedule, the fourth's
        // seats start empty for the solve to fill.
        PlanningEvenement moins =
                StaffingVerificationService.problem(week(), StaffingVerificationService.team(3, 0, TYPOLOGIES, LUNDI));
        int seededMoins = StaffingVerificationService.seedFromPlan(
                moins.getPostes(), moins.getAnimateurs(), 3, plan, reels, LUNDI);

        assertThat(seededMoins).isBetween(1, 20);
        assertThat(moins.getPostes()).anyMatch(poste -> poste.getAnimateur() == null);
    }

    @Test
    void nothingIsSeededWithoutAPlan() {
        PlanningEvenement problem =
                StaffingVerificationService.problem(week(), StaffingVerificationService.team(4, 0, TYPOLOGIES, LUNDI));

        assertThat(StaffingVerificationService.seedFromPlan(
                        problem.getPostes(), problem.getAnimateurs(), 4, Map.of(), List.of(), LUNDI))
                .isZero();
        assertThat(problem.getPostes()).allMatch(poste -> poste.getAnimateur() == null);
    }

    @Test
    void theMadeUpTeamHoldsEveryCategoryAndIsAdultOnAnyDate() {
        List<TypologieItem> typologies =
                List.of(new TypologieItem("JEUX", "Jeux"), new TypologieItem("NINJA", "Ninja", true));

        var team = StaffingVerificationService.team(3, 0, typologies, LUNDI);

        assertThat(team).hasSize(3).allSatisfy(animateur -> {
            assertThat(animateur.getCompetences()).containsOnlyKeys("JEUX", "NINJA");
            assertThat(animateur.isNinja()).isTrue();
            assertThat(animateur.isMajeurOn(LocalDate.of(2026, 7, 6))).isTrue();
            assertThat(animateur.getJoursIndisponibles()).isNullOrEmpty();
        });
    }

    @Test
    void theMadeUpMinorsAreSixteenToSeventeenThroughoutTheEvent() {
        var team = StaffingVerificationService.team(2, 3, TYPOLOGIES, LUNDI);

        assertThat(team).hasSize(5);
        assertThat(team.subList(0, 2)).allMatch(animateur -> animateur.isMajeurOn(LUNDI));
        assertThat(team.subList(2, 5)).allSatisfy(animateur -> {
            assertThat(animateur.isMineurOn(LUNDI)).isTrue();
            assertThat(animateur.isMineurOn(LUNDI.plusMonths(11))).isTrue();
            assertThat(animateur.getDateNaissance()).isEqualTo(LUNDI.minusYears(16));
        });
        assertThat(team).extracting(animateur -> animateur.getId()).doesNotHaveDuplicates();
    }

    @Test
    void theCheckAlwaysHoldsTheSeatRuleEvenWhenTheEditionSwitchedItOff() {
        PlanningEvenement problem =
                StaffingVerificationService.problem(week(), StaffingVerificationService.team(4, 0, TYPOLOGIES, LUNDI));
        problem.setConstraintsDesactivees(List.of(new ConstraintToggle("posteDoitEtrePourvu", false)));

        planningService.prepareHypothetical(problem);

        assertThat(problem.getConstraintsDesactivees())
                .filteredOn(toggle -> "posteDoitEtrePourvu".equals(toggle.getNom()))
                .singleElement()
                .satisfies(toggle -> assertThat(toggle.isActif()).isTrue());
    }

    private PlanningEvenement solve(Seats seats, int effectif, long seconds) {
        PlanningEvenement problem = StaffingVerificationService.problem(
                seats, StaffingVerificationService.team(effectif, 0, TYPOLOGIES, LUNDI));
        planningService.prepareHypothetical(problem);
        return planningService.solvePreparedUntilFeasible(problem, seconds);
    }

    /** Monday to Sunday, three seats from 10:00 to 12:00 every day. */
    private static Seats week() {
        Stand stand = new Stand("A", "A", Set.of("JEUX"), 3, 3, false);
        List<Creneau> creneaux = new ArrayList<>();
        for (int jour = 0; jour < 7; jour++) {
            creneaux.add(
                    new Creneau((long) jour, jour + 1, LUNDI.plusDays(jour), LocalTime.of(10, 0), LocalTime.of(12, 0)));
        }
        List<PosteAffectation> postes = ProblemBuilder.buildPostes(List.of(stand), creneaux);
        return new Seats(List.of(stand), creneaux, postes);
    }
}
