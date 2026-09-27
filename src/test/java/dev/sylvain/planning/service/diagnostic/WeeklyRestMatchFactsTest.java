package dev.sylvain.planning.service.diagnostic;

import static org.assertj.core.api.Assertions.assertThat;

import ai.timefold.solver.core.api.solver.SolverFactory;
import ai.timefold.solver.core.config.score.director.ScoreDirectorFactoryConfig;
import ai.timefold.solver.core.config.solver.SolverConfig;
import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.solver.PlanningConstraintProvider;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The weekly rest rule carries its deficit in the tuple, computed once, and
 * names its own facts: what the diagnostic reads of a match stays the
 * animateur and their seats, as it was when the deficit was recomputed in the
 * filter and in the penalty.
 */
class WeeklyRestMatchFactsTest {

    private static final LocalDate MONDAY = LocalDate.of(2026, 7, 13);

    @Test
    @SuppressWarnings("unchecked")
    void aWeekWithoutItsRestNamesThePersonAndTheirSeats() {
        Stand stand = new Stand("S1", "Stand", Set.of(), 1, 1, false);
        Animateur animateur = new Animateur("A1", "Alice", "Martin", LocalDate.of(1990, 1, 1), false);
        List<PosteAffectation> seats = new ArrayList<>();
        for (int day = 0; day < 7; day++) {
            PosteAffectation seat = new PosteAffectation(
                    "P" + day,
                    stand,
                    new Creneau((long) day, day + 1, MONDAY.plusDays(day), LocalTime.of(11, 0), LocalTime.of(15, 0)));
            seat.setAnimateur(animateur);
            seats.add(seat);
        }
        PlanningEvenement planning = new PlanningEvenement(MONDAY, List.of(animateur), seats);

        ConstraintContribution weeklyRest = new ScoreDirectorConstraintDiagnosticService(solverFactory())
                .analyze(planning).contributions().stream()
                        .filter(contribution -> contribution.constraintName().equals("reposHebdomadaireMinimal"))
                        .findFirst()
                        .orElseThrow();

        // Seven days from 11:00 to 15:00: 22 h credited, 13 h short.
        assertThat(weeklyRest.score().hardScore()).isEqualTo(-(35L - 22) * 60);
        assertThat(weeklyRest.matches()).singleElement().satisfies(match -> {
            assertThat(match.facts()).hasSize(2);
            assertThat(match.facts().getFirst()).isSameAs(animateur);
            assertThat((Collection<Object>) match.facts().get(1)).containsExactlyInAnyOrderElementsOf(seats);
        });
    }

    private static SolverFactory<PlanningEvenement> solverFactory() {
        SolverConfig solverConfig = SolverConfig.createFromXmlResource("solver/solverConfig.xml");
        solverConfig.setScoreDirectorFactoryConfig(
                new ScoreDirectorFactoryConfig().withConstraintProviderClass(PlanningConstraintProvider.class));
        return SolverFactory.create(solverConfig);
    }
}
