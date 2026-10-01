package dev.sylvain.planning.service.analyse;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.PastHorizon;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.service.analyse.FeasibilityAnalyzer.CauseInfaisabilite;
import dev.sylvain.planning.service.analyse.FeasibilityAnalyzer.ConsecutiveDaysRule;
import dev.sylvain.planning.service.analyse.FeasibilityAnalyzer.FeasibilityReport;
import dev.sylvain.planning.service.analyse.FeasibilityAnalyzer.PlanContext;
import dev.sylvain.planning.service.analyse.FeasibilityAnalyzer.SeveriteInfaisabilite;
import dev.sylvain.planning.service.analyse.FeasibilityAnalyzer.TypeCauseInfaisabilite;
import dev.sylvain.planning.service.diagnostic.BlockerPlaybook;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The cap on days worked in a row read against the grid, before any solve:
 * every window of (cap + 1) days needs a rest day per person, so the
 * person-days the available animateurs can offer bound what the days need.
 *
 * <p>The grid used throughout: three days, one stand asking for two people
 * every morning, a cap of two days in a row — so the one window of three days
 * needs six person-days, and each animateur offers at most two.</p>
 */
class ConsecutiveDaysCapTest {

    private static final LocalDate J1 = LocalDate.of(2026, 8, 1);

    private final FeasibilityAnalyzer analyzer = new FeasibilityAnalyzer();

    private final Stand stand = new Stand("stand-1", "Stand 1", Set.of("STRATEGIE"), 2, 2, false);

    private final List<Creneau> creneaux =
            List.of(creneau(1, J1, 10, 12), creneau(2, J1.plusDays(1), 10, 12), creneau(3, J1.plusDays(2), 10, 12));

    @Test
    void aWindowShortOfPersonDaysIsAProofUnderTheHardRule() {
        FeasibilityReport report = analyze(animateurs(2), new ConsecutiveDaysRule(2, true), PlanContext.NONE);

        CauseInfaisabilite cause = onlyCapCause(report);
        assertThat(cause.severite()).isEqualTo(SeveriteInfaisabilite.CRITIQUE);
        assertThat(cause.date()).isEqualTo(J1);
        assertThat(cause.demande()).isEqualTo(6);
        assertThat(cause.capacite()).isEqualTo(4);
        assertThat(cause.manque()).isEqualTo(2);
        assertThat(cause.message())
                .contains("du 2026-08-01 au 2026-08-03")
                .contains("Il manque 2 jours-personne, soit au moins 1 animateur de plus");
        assertThat(cause.actions()).extracting(BlockerPlaybook.ActionType::code).startsWith(BlockerPlaybook.CODE_CAP);
        assertThat(report.feasible()).isFalse();
        assertThat(report.causesCritiques()).isEqualTo(1);
    }

    /** Under the medium rule alone nothing blocks: the plan will run past the cap, and the cause says so. */
    @Test
    void theSameShortfallOnlyWarnsUnderTheMediumRule() {
        FeasibilityReport report = analyze(animateurs(2), new ConsecutiveDaysRule(2, false), PlanContext.NONE);

        CauseInfaisabilite cause = onlyCapCause(report);
        assertThat(cause.severite()).isEqualTo(SeveriteInfaisabilite.ELEVE);
        assertThat(cause.message()).contains("sera forcément dépassé");
        assertThat(report.feasible()).isTrue();
    }

    /** A margin under a tenth of the demand holds only if every day employs its minimum: worth a warning, not a verdict. */
    @Test
    void aThinMarginWarnsWithoutMakingThePlanInfeasible() {
        FeasibilityReport report = analyze(animateurs(3), new ConsecutiveDaysRule(2, true), PlanContext.NONE);

        CauseInfaisabilite cause = onlyCapCause(report);
        assertThat(cause.severite()).isEqualTo(SeveriteInfaisabilite.ELEVE);
        assertThat(cause.manque()).isZero();
        assertThat(cause.message()).contains("est à la limite").contains("marge de 0");
        assertThat(report.feasible()).isTrue();
        assertThat(report.causesElevees()).isEqualTo(1);
        assertThat(report.message()).startsWith("Le planning est réalisable").contains("Point de vigilance");
    }

    @Test
    void aComfortableMarginSaysNothing() {
        FeasibilityReport report = analyze(animateurs(4), new ConsecutiveDaysRule(2, true), PlanContext.NONE);

        assertThat(report.causes()).isEmpty();
        assertThat(report.feasible()).isTrue();
    }

    @Test
    void noCapNoCheck() {
        assertThat(analyze(animateurs(2), null, PlanContext.NONE).causes()).isEmpty();
    }

    /** An animateur off on two of the three days can work one of them at most, not two. */
    @Test
    void declaredDaysOffAreCountedNotAGlobalHeadcount() {
        List<Animateur> animateurs = animateurs(3);
        animateurs.getFirst().setJoursIndisponibles(Set.of(J1, J1.plusDays(1)));

        CauseInfaisabilite cause =
                onlyCapCause(analyze(animateurs, new ConsecutiveDaysRule(2, true), PlanContext.NONE));

        assertThat(cause.severite()).isEqualTo(SeveriteInfaisabilite.CRITIQUE);
        assertThat(cause.capacite()).isEqualTo(5);
    }

    /** A run the rule never charges — every day of the window already worked — is not judged either. */
    @Test
    void aWindowEntirelyInTheFrozenPastIsSkipped() {
        PlanContext apresLEvenement =
                new PlanContext(List.of(), Set::of, new PastHorizon(J1.plusDays(5), LocalTime.NOON));

        assertThat(analyze(animateurs(2), new ConsecutiveDaysRule(2, true), apresLEvenement)
                        .causes())
                .isEmpty();
    }

    /** An event shorter than a window cannot run past the cap. */
    @Test
    void anEventShorterThanAWindowHasNothingToJudge() {
        assertThat(analyze(animateurs(2), new ConsecutiveDaysRule(3, true), PlanContext.NONE)
                        .causes())
                .isEmpty();
    }

    /**
     * The grid leaves room, the plan in place does not: its days employ more
     * people than rest days allow, and no rearrangement of the rest days alone
     * can hold the cap.
     */
    @Test
    void aPlanEmployingMoreThanItsRestDaysAllowIsReported() {
        List<Animateur> animateurs = animateurs(4);
        Set<String> tous = Set.of("a1", "a2", "a3", "a4");
        PlanContext plan = new PlanContext(
                List.of(), Set::of, null, () -> Map.of(J1, tous, J1.plusDays(1), tous, J1.plusDays(2), tous));

        CauseInfaisabilite cause = onlyCapCause(analyze(animateurs, new ConsecutiveDaysRule(2, true), plan));

        assertThat(cause.severite()).isEqualTo(SeveriteInfaisabilite.ELEVE);
        assertThat(cause.demande()).isEqualTo(12);
        assertThat(cause.capacite()).isEqualTo(8);
        assertThat(cause.message()).startsWith("Sur le plan en place").contains("Il manque 4 jours de repos");
    }

    @Test
    void aPlanWithinItsRestDaysSaysNothing() {
        PlanContext plan = new PlanContext(
                List.of(),
                Set::of,
                null,
                () -> Map.of(
                        J1,
                        Set.of("a1", "a2"),
                        J1.plusDays(1),
                        Set.of("a3", "a4"),
                        J1.plusDays(2),
                        Set.of("a1", "a3")));

        assertThat(analyze(animateurs(4), new ConsecutiveDaysRule(2, true), plan)
                        .causes())
                .isEmpty();
    }

    /** Two stands at the same hour add up; two timeslots one after the other on one stand do not. */
    @Test
    void theFloorOfADayIsItsPeakOfSimultaneousSeats() {
        Stand autre = new Stand("stand-2", "Stand 2", Set.of("STRATEGIE"), 1, 1, false);
        Creneau matin = creneau(1, J1, 10, 12);
        Creneau midi = creneau(2, J1, 12, 14);

        assertThat(ConsecutiveDaysCapacity.floors(List.of(stand, autre), List.of(matin, midi)))
                .containsEntry(J1, 3);
        assertThat(ConsecutiveDaysCapacity.floors(List.of(stand), List.of(matin, midi)))
                .containsEntry(J1, 2);
    }

    private FeasibilityReport analyze(List<Animateur> animateurs, ConsecutiveDaysRule plafond, PlanContext contexte) {
        return analyzer.analyze(animateurs, List.of(stand), creneaux, List.of(), false, plafond, contexte);
    }

    private static CauseInfaisabilite onlyCapCause(FeasibilityReport report) {
        assertThat(report.causes())
                .singleElement()
                .extracting(CauseInfaisabilite::type)
                .isEqualTo(TypeCauseInfaisabilite.PLAFOND_JOURS_CONSECUTIFS);
        return report.causes().getFirst();
    }

    private static List<Animateur> animateurs(int nombre) {
        List<Animateur> animateurs = new ArrayList<>();
        for (int i = 1; i <= nombre; i++) {
            animateurs.add(new Animateur("a" + i, "a" + i, "a" + i, LocalDate.of(1990, 1, 1), false));
        }
        return animateurs;
    }

    private static Creneau creneau(long id, LocalDate date, int debut, int fin) {
        return new Creneau(id, (int) id, date, LocalTime.of(debut, 0), LocalTime.of(fin, 0));
    }
}
