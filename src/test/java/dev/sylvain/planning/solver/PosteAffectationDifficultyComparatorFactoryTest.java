package dev.sylvain.planning.solver;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.ConstraintToggle;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.NiveauCompetence;
import dev.sylvain.planning.domain.ParametresQualite;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The order the construction heuristic places seats in: scarcest first, and,
 * under the hard run of days, earliest day first (ADR 0068). The heuristic
 * places the <em>greatest</em> seat first, so « placed first » reads as
 * « compares greater » here.
 */
class PosteAffectationDifficultyComparatorFactoryTest {

    private static final LocalDate MONDAY = LocalDate.of(2026, 7, 6);

    private final Stand everyone = new Stand("SETUP", "Montage", Set.of("LOGISTIQUE"), 1, 1, false);
    private final Stand fewOnly = new Stand("RARE", "Rare", Set.of("RARE"), 1, 1, false);

    private final PosteAffectation setUpOnMonday = seat("P1", everyone, MONDAY);
    private final PosteAffectation rareOnTuesday = seat("P2", fewOnly, MONDAY.plusDays(1));
    private final PosteAffectation rareOnMonday = seat("P3", fewOnly, MONDAY);

    private static PosteAffectation seat(String id, Stand stand, LocalDate date) {
        return new PosteAffectation(
                id, stand, new Creneau((long) id.hashCode(), 1, date, LocalTime.of(9, 0), LocalTime.of(12, 0)));
    }

    private PlanningEvenement solution(boolean hardRunCap) {
        List<Animateur> animateurs = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            Animateur animateur = new Animateur("A" + i, "Prénom", "Nom", LocalDate.of(1990, 1, 1), false);
            animateur.setCompetences(
                    i == 0
                            ? Map.of("LOGISTIQUE", NiveauCompetence.AUTONOME, "RARE", NiveauCompetence.AUTONOME)
                            : Map.of("LOGISTIQUE", NiveauCompetence.AUTONOME));
            animateurs.add(animateur);
        }
        PlanningEvenement solution =
                new PlanningEvenement(MONDAY, animateurs, List.of(setUpOnMonday, rareOnTuesday, rareOnMonday));
        ParametresQualite defaults = new ParametresQualite();
        solution.setParametresQualite(List.of(new ParametresQualite(
                defaults.maxEmplacementsDistinctsParJour(),
                defaults.heureServiceTardif(),
                defaults.heureServiceMatinal(),
                defaults.reposSouhaiteApresServiceTardifMinutes(),
                defaults.typologiesDistinctesMax(),
                6)));
        if (hardRunCap) {
            solution.setConstraintsDesactivees(List.of(new ConstraintToggle("maxJoursConsecutifsTravaillesDur", true)));
        }
        return solution;
    }

    private List<PosteAffectation> placementOrder(boolean hardRunCap) {
        Comparator<PosteAffectation> difficulty =
                new PosteAffectationDifficultyComparatorFactory().createComparator(solution(hardRunCap));
        List<PosteAffectation> seats = new ArrayList<>(List.of(setUpOnMonday, rareOnTuesday, rareOnMonday));
        seats.sort(difficulty.reversed());
        return seats;
    }

    @Test
    void withoutTheHardRunOfDaysTheScarcestSeatsArePlacedFirstWhateverTheirDay() {
        assertThat(placementOrder(false).getLast()).isSameAs(setUpOnMonday);
    }

    @Test
    void underTheHardRunOfDaysASeatWithoutADayIsPlacedLast() {
        PosteAffectation undated = new PosteAffectation("P4", fewOnly, null);
        Comparator<PosteAffectation> difficulty =
                new PosteAffectationDifficultyComparatorFactory().createComparator(solution(true));
        List<PosteAffectation> seats = new ArrayList<>(List.of(undated, rareOnTuesday, setUpOnMonday));
        seats.sort(difficulty.reversed());
        assertThat(seats).containsExactly(setUpOnMonday, rareOnTuesday, undated);
    }

    @Test
    void underTheHardRunOfDaysTheEarliestDayIsPlacedFirstThenTheScarcestOfIt() {
        assertThat(placementOrder(true)).containsExactly(rareOnMonday, setUpOnMonday, rareOnTuesday);
    }
}
