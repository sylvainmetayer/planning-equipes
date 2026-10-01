package dev.sylvain.planning.service.analyse;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sylvain.planning.domain.ConstraintToggle;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.ParametresQualite;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.service.EmptyReferenceData;
import dev.sylvain.planning.service.analyse.StaffingAnalyzer.StaffingSummary;
import dev.sylvain.planning.service.referentiel.TypologieItem;
import dev.sylvain.planning.service.solve.PlanningService;
import dev.sylvain.planning.service.solve.ProblemBuilder;
import dev.sylvain.planning.service.solve.ProblemBuilder.Seats;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
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
    void theMadeUpTeamHoldsEveryCategoryAndIsAdultOnAnyDate() {
        List<TypologieItem> typologies =
                List.of(new TypologieItem("JEUX", "Jeux"), new TypologieItem("NINJA", "Ninja", true));

        var team = StaffingVerificationService.team(3, typologies);

        assertThat(team).hasSize(3).allSatisfy(animateur -> {
            assertThat(animateur.getCompetences()).containsOnlyKeys("JEUX", "NINJA");
            assertThat(animateur.isNinja()).isTrue();
            assertThat(animateur.isMajeurOn(LocalDate.of(2026, 7, 6))).isTrue();
            assertThat(animateur.getJoursIndisponibles()).isNullOrEmpty();
        });
    }

    @Test
    void theCheckAlwaysHoldsTheSeatRuleEvenWhenTheEditionSwitchedItOff() {
        PlanningEvenement problem =
                StaffingVerificationService.problem(week(), StaffingVerificationService.team(4, TYPOLOGIES));
        problem.setConstraintsDesactivees(List.of(new ConstraintToggle("posteDoitEtrePourvu", false)));

        planningService.prepareHypothetical(problem);

        assertThat(problem.getConstraintsDesactivees())
                .filteredOn(toggle -> "posteDoitEtrePourvu".equals(toggle.getNom()))
                .singleElement()
                .satisfies(toggle -> assertThat(toggle.isActif()).isTrue());
    }

    private PlanningEvenement solve(Seats seats, int effectif, long seconds) {
        PlanningEvenement problem =
                StaffingVerificationService.problem(seats, StaffingVerificationService.team(effectif, TYPOLOGIES));
        planningService.prepareHypothetical(problem);
        return planningService.solvePreparedUntilFeasible(problem, seconds);
    }

    /** Monday to Sunday, three seats from 10:00 to 12:00 every day. */
    private static Seats week() {
        Stand stand = new Stand("A", "A", Set.of("JEUX"), 3, 3, false);
        List<Creneau> creneaux = new ArrayList<>();
        LocalDate lundi = LocalDate.of(2026, 7, 6);
        for (int jour = 0; jour < 7; jour++) {
            creneaux.add(
                    new Creneau((long) jour, jour + 1, lundi.plusDays(jour), LocalTime.of(10, 0), LocalTime.of(12, 0)));
        }
        List<PosteAffectation> postes = ProblemBuilder.buildPostes(List.of(stand), creneaux);
        return new Seats(List.of(stand), creneaux, postes);
    }
}
