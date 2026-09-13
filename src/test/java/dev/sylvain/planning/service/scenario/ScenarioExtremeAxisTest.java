package dev.sylvain.planning.service.scenario;

import static dev.sylvain.planning.service.scenario.ScenarioLadder.assertFeasible;
import static dev.sylvain.planning.service.scenario.ScenarioLadder.endMinute;
import static dev.sylvain.planning.service.scenario.ScenarioLadder.load;
import static dev.sylvain.planning.service.scenario.ScenarioLadder.seatsByAnimateur;
import static dev.sylvain.planning.service.scenario.ScenarioLadder.solveUntilFeasible;
import static dev.sylvain.planning.service.scenario.ScenarioLadder.startMinute;
import static org.assertj.core.api.Assertions.assertThat;

import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.service.referentiel.ContrainteAdHocContradictions;
import dev.sylvain.planning.service.scenario.ScenarioLadder.Loaded;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * One limit at a time, each solved to zero hard: a thousand animateurs, a
 * hundred and twenty days, five hundred stands in a day, round-the-clock
 * operation, one-hour vacations, a two-hundred-seat stand, two thousand ad hoc
 * rules.
 *
 * <p>Every ceiling is about ten times the time measured on the development
 * machine (noted next to it), and never under a minute: a ceiling is where a
 * convergence regression turns red, not a target. Tagged
 * {@code scenario-extreme}: {@code ./mvnw test -Pscenario-extreme
 * -DargLine=-Xmx3g -Dtest=ScenarioExtremeAxisTest}, in the foreground.</p>
 */
@Tag("scenario-extreme")
class ScenarioExtremeAxisTest {

    /** 240 seats, a thousand candidates each: zero hard in 5 s. */
    @Test
    void aThousandAnimateursForAWeekend() {
        Loaded loaded = load("extreme-01-3j-30stands-1000animateurs-surdotation");
        assertThat(loaded.problem().getPostes()).hasSize(240);

        assertFeasible(solveUntilFeasible(loaded, 120L));
    }

    /**
     * A month with a thousand animateurs: 6 480 seats, zero hard in 185 s,
     * almost all of it the construction heuristic — whose cost grows with seats
     * times candidates, the first limit this set runs into.
     */
    @Test
    void aMonthWithAThousandAnimateurs() {
        Loaded loaded = load("extreme-02-30j-150stands-1000animateurs-mois");
        assertThat(loaded.problem().getPostes()).hasSize(6480);

        assertFeasible(solveUntilFeasible(loaded, 1800L));
    }

    /** A season of a hundred and twenty days, six public holidays: 5 034 seats, zero hard in 30 s. */
    @Test
    void aSeasonOfAHundredAndTwentyDays() {
        Loaded loaded = load("extreme-03-120j-40stands-84animateurs-saison");
        assertThat(loaded.problem().getPostes()).hasSize(5034);
        assertThat(loaded.creneaux().stream().map(creneau -> creneau.getDate()).distinct())
                .hasSize(120);

        assertFeasible(solveUntilFeasible(loaded, 300L));
    }

    /** 2 400 seats over eighteen weeks with some fifteen percent of slack: zero hard in 11 s. */
    @Test
    void aHundredAndTwentyDaysWithoutARestDayForTheEvent() {
        Loaded loaded = load("extreme-04-120j-10stands-14animateurs-sans-relache");
        assertThat(loaded.problem().getPostes()).hasSize(2400);

        PlanningEvenement solved = solveUntilFeasible(loaded, 180L);

        assertFeasible(solved);
        assertThat(seatsByAnimateur(solved)).hasSize(14);
    }

    /** Six four-hour vacations around the clock for a week: zero hard in 3 s. */
    @Test
    void roundTheClockOperationKeepsTheDailyRest() {
        Loaded loaded = load("extreme-06-7j-20stands-120animateurs-24h-sur-24");
        assertThat(loaded.problem().getPostes()).hasSize(840);

        PlanningEvenement solved = solveUntilFeasible(loaded, 60L);

        assertFeasible(solved);
        seatsByAnimateur(solved).forEach((animateur, postes) -> {
            List<PosteAffectation> ordre = postes.stream()
                    .sorted((a, b) -> Long.compare(startMinute(a), startMinute(b)))
                    .toList();
            for (int i = 1; i < ordre.size(); i++) {
                PosteAffectation avant = ordre.get(i - 1);
                PosteAffectation apres = ordre.get(i);
                if (!avant.getCreneau().getDate().equals(apres.getCreneau().getDate())) {
                    assertThat(startMinute(apres) - endMinute(avant))
                            .as(
                                    "daily rest of %s before %s",
                                    animateur.getId(), apres.getCreneau().getDate())
                            .isGreaterThanOrEqualTo(11 * 60);
                }
            }
        });
    }

    /** Five hundred stands on the same Saturday, 1 200 seats: zero hard in 30 s. */
    @Test
    void fiveHundredStandsOnTheSameDay() {
        Loaded loaded = load("extreme-05-1j-500stands-800animateurs-un-jour-dense");
        assertThat(loaded.problem().getPostes()).hasSize(1200);

        assertFeasible(solveUntilFeasible(loaded, 300L));
    }

    /** Twelve one-hour vacations a day, a working day being a chain of small seats: 2 160 seats, zero hard in 15 s. */
    @Test
    void oneHourVacations() {
        Loaded loaded = load("extreme-07-3j-60stands-172animateurs-creneaux-d-une-heure");
        assertThat(loaded.problem().getPostes()).hasSize(2160);
        assertThat(loaded.creneaux())
                .allSatisfy(creneau -> assertThat(creneau.getDureeMinutes()).isEqualTo(60));

        assertFeasible(solveUntilFeasible(loaded, 180L));
    }

    /** 200 identical seats per vacation on one stand: zero hard in 7 s. */
    @Test
    void aSingleStandOfTwoHundredSeats() {
        Loaded loaded = load("extreme-08-2j-1stands-300animateurs-un-stand-de-200-places");
        assertThat(loaded.problem().getPostes()).hasSize(800);
        assertThat(loaded.problem().getPostes())
                .filteredOn(poste -> poste.getCreneau().getHeureDebut().equals(LocalTime.of(10, 0)))
                .hasSize(400);

        assertFeasible(solveUntilFeasible(loaded, 120L));
    }

    /**
     * Two thousand ad hoc rules. The contradiction check used to compare them
     * pair by pair — 3.9 s here, paid by every pre-solve analysis; indexed by
     * animateur it takes a fraction of a second, and the ceiling below keeps it
     * there. The solve reaches zero hard in 11 s.
     */
    @Test
    void twoThousandAdHocRules() {
        Loaded loaded = load("extreme-13-2000-contraintes-ad-hoc");
        assertThat(loaded.problem().getContraintesAdHoc()).hasSize(2000);

        Instant debut = Instant.now();
        assertThat(ContrainteAdHocContradictions.detectAll(loaded.problem().getContraintesAdHoc(), loaded.creneaux()))
                .isEmpty();
        assertThat(Duration.between(debut, Instant.now())).isLessThan(Duration.ofSeconds(5));

        assertFeasible(solveUntilFeasible(loaded, 120L));
    }
}
