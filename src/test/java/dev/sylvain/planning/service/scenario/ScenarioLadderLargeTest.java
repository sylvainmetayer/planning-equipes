package dev.sylvain.planning.service.scenario;

import static dev.sylvain.planning.service.scenario.ScenarioLadder.assertFeasible;
import static dev.sylvain.planning.service.scenario.ScenarioLadder.load;
import static dev.sylvain.planning.service.scenario.ScenarioLadder.matchCounts;
import static dev.sylvain.planning.service.scenario.ScenarioLadder.seatsOnStand;
import static dev.sylvain.planning.service.scenario.ScenarioLadder.solveUntilFeasible;
import static org.assertj.core.api.Assertions.assertThat;

import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.IndisponibiliteStand;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.service.scenario.ScenarioLadder.Loaded;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Rungs 16 to 25 of the scenario ladder: two weeks to a month, up to 150
 * stands and 320 animateurs, several thousand seats. Sized with slack like the
 * rest of the ladder, each still reaches zero hard in its construction
 * heuristic — measured at 3 to 45 seconds on the development machine — but the
 * construction alone is too long for the default loop.
 *
 * <p>Tagged {@code scenario-lent}: run with {@code ./mvnw test -Pscenario-tests
 * -Dtest=ScenarioLadderLargeTest}, in the background from an agent session.</p>
 */
@Tag("scenario-lent")
class ScenarioLadderLargeTest {

    /** A ceiling for a run that stops converging, never a target: the solve returns at zero hard. */
    private static final long CEILING_SECONDS = 900L;

    private static boolean isWeekend(LocalDate date) {
        return date.getDayOfWeek() == DayOfWeek.SATURDAY || date.getDayOfWeek() == DayOfWeek.SUNDAY;
    }

    private static int standNumber(PosteAffectation poste) {
        return Integer.parseInt(poste.getStand().getId().replaceAll("\\D", ""));
    }

    @Test
    void rung16AThirdOfMinorsAHolidayAndNocturnes() {
        Loaded loaded = load("gamme-16-14j-25stands-72animateurs-mineurs-ferie-nocturnes");
        assertThat(loaded.problem().getPostes()).hasSize(1012);
        assertThat(loaded.problem().getAnimateurs())
                .filteredOn(animateur -> animateur.isMineurOn(LocalDate.of(2027, 8, 9)))
                .hasSizeGreaterThanOrEqualTo(15);
        assertThat(loaded.problem().getPostes())
                .filteredOn(poste -> poste.getCreneau().getHeureDebut().equals(LocalTime.of(20, 0)))
                .hasSize(32)
                .allSatisfy(poste -> assertThat(poste.getCreneau().getDate().getDayOfWeek())
                        .isIn(DayOfWeek.FRIDAY, DayOfWeek.SATURDAY));

        assertFeasible(solveUntilFeasible(loaded, CEILING_SECONDS));
    }

    /**
     * The file only solves because it switches the meal break off: scored with
     * every rule back on, the same plan owes a break to everyone holding a
     * morning vacation, which spans the whole midday window.
     */
    @Test
    void rung17TheSwitchedOffMealBreakIsWhatMakesItSolvable() {
        Loaded loaded = load("gamme-17-14j-30stands-78animateurs-contraintes-desactivees");
        assertThat(loaded.problem().getConstraintsDesactivees())
                .extracting(toggle -> toggle.getNom())
                .containsExactlyInAnyOrder(
                        "coupureRepasObligatoire",
                        "coupureRepasPlacementPrefere",
                        "limiterTypologiesDistinctesParAnimateur");
        assertThat(loaded.problem().getPostes()).hasSize(640);

        PlanningEvenement solved = solveUntilFeasible(loaded, CEILING_SECONDS);

        assertFeasible(solved);
        long matinsTenus = solved.getPostes().stream()
                .filter(poste -> poste.getCreneau().getHeureDebut().equals(LocalTime.of(10, 0)))
                .map(poste -> poste.getAnimateur().getId() + poste.getCreneau().getDate())
                .distinct()
                .count();
        solved.setConstraintsDesactivees(List.of());
        assertThat(matchCounts(solved)).containsEntry("coupureRepasObligatoire", (int) matinsTenus);
    }

    /**
     * Seats carried by windows: two overlapping windows take the higher
     * headcount, and a one-hour closure splits an afternoon into two seat
     * groups with their own effective windows.
     */
    @Test
    void rung18HeadcountsCarriedByTheOpeningWindows() {
        Loaded loaded = load("gamme-18-14j-35stands-132animateurs-effectifs-par-fenetre");
        // Owed seats only: this rung's stands declare an effectifMax above
        // what their windows ask for, so the generation adds renforts on top
        // (issue #505). The figure the ladder pins is the need, not the
        // capacity — pinning the sum would move it on any capacity edit.
        assertThat(loaded.problem().getPostes())
                .filteredOn(poste -> !poste.isOptionnel())
                .hasSize(1687);
        assertThat(loaded.problem().getPostes())
                .filteredOn(PosteAffectation::isOptionnel)
                .isNotEmpty();
        // STAND-02 declares effectifMax 2 over windows asking 1 then 2: the
        // renfort is the one seat the morning is short of its capacity, and
        // there is none where the window already asks for the maximum. Read
        // per vacation rather than as one global figure — a count of the whole
        // rung would move on any capacity edit without saying which.
        assertThat(renfortsParVacation(loaded, "STAND-02", LocalDate.of(2027, 6, 14)))
                .containsOnly(Map.entry(LocalTime.of(10, 0), 1L));
        Map<LocalTime, Long> chevauchementParVacation = seatsOnStand(
                        loaded.problem().getPostes(), "STAND-02")
                .stream()
                // Owed seats: what this rung reads is the window's effectif
                // driving the seat count, not the stand's capacity on top.
                .filter(poste -> !poste.isOptionnel())
                .filter(poste -> poste.getCreneau().getDate().equals(LocalDate.of(2027, 6, 14)))
                .collect(Collectors.groupingBy(poste -> poste.getCreneau().getHeureDebut(), Collectors.counting()));
        assertThat(chevauchementParVacation)
                .containsOnly(
                        Map.entry(LocalTime.of(10, 0), 1L),
                        Map.entry(LocalTime.of(14, 0), 2L),
                        Map.entry(LocalTime.of(18, 0), 2L));
        Stand fermeParfois = loaded.stands().stream()
                .filter(stand -> stand.getId().equals("STAND-04"))
                .findFirst()
                .orElseThrow();
        assertThat(fermeParfois.getIndisponibilites()).hasSize(3);
        for (IndisponibiliteStand fermeture : fermeParfois.getIndisponibilites()) {
            assertThat(seatsOnStand(loaded.problem().getPostes(), "STAND-04"))
                    .filteredOn(poste -> poste.getCreneau().getDate().equals(fermeture.getDate())
                            && poste.getCreneau().getHeureDebut().equals(LocalTime.of(14, 0)))
                    .extracting(PosteAffectation::heureDebutEffectif, PosteAffectation::heureFinEffectif)
                    .containsExactlyInAnyOrder(
                            org.assertj.core.groups.Tuple.tuple(LocalTime.of(14, 0), LocalTime.of(15, 0)),
                            org.assertj.core.groups.Tuple.tuple(LocalTime.of(16, 0), LocalTime.of(18, 0)));
        }

        assertFeasible(solveUntilFeasible(loaded, CEILING_SECONDS));
    }

    @Test
    void rung19SeasonalStaffAndStandsThatArriveMidway() {
        Loaded loaded = load("gamme-19-21j-40stands-118animateurs-saisonniers-plages");
        assertThat(loaded.problem().getPostes()).hasSize(1778);
        assertThat(loaded.problem().getPostes())
                .filteredOn(poste -> standNumber(poste) >= 26 && standNumber(poste) <= 33)
                .isNotEmpty()
                .allSatisfy(poste -> assertThat(poste.getCreneau().getDate())
                        .isBetween(LocalDate.of(2027, 7, 12), LocalDate.of(2027, 7, 25)));
        assertThat(loaded.problem().getPostes())
                .filteredOn(poste -> standNumber(poste) >= 34)
                .isNotEmpty()
                .allSatisfy(poste ->
                        assertThat(isWeekend(poste.getCreneau().getDate())).isTrue());

        assertFeasible(solveUntilFeasible(loaded, CEILING_SECONDS));
    }

    @Test
    void rung20ThreeWeeksOfAFestival() {
        Loaded loaded = load("gamme-20-21j-50stands-172animateurs-festival-type");
        assertThat(loaded.dayTemplatesPlan().isEmpty()).isTrue();
        assertThat(loaded.problem().getPostes()).hasSize(5160);
        assertThat(loaded.stands()).filteredOn(Stand::isPremium).hasSize(15);
        // The single-seat stands close over lunch rather than open half a seat.
        assertThat(loaded.problem().getPostes())
                .filteredOn(poste -> standNumber(poste) >= 36)
                .noneMatch(poste -> poste.getCreneau().isCouverturePause());

        assertFeasible(solveUntilFeasible(loaded, CEILING_SECONDS));
    }

    @Test
    void rung21AMonthInThreeDayTemplates() {
        Loaded loaded = load("gamme-21-30j-60stands-104animateurs-mois-journees-types");
        assertThat(loaded.dayTemplatesPlan().isEmpty()).isTrue();
        assertThat(loaded.problem().getPostes()).hasSize(1840);
        assertThat(loaded.problem().getPostes())
                .filteredOn(poste -> standNumber(poste) >= 26)
                .allSatisfy(poste ->
                        assertThat(isWeekend(poste.getCreneau().getDate())).isTrue());

        assertFeasible(solveUntilFeasible(loaded, CEILING_SECONDS));
    }

    /**
     * A month of amplitudes: the weekday one fits under the maximum duration
     * and passes through whole, the weekend one is cut in four around lunch.
     */
    @Test
    void rung22AMonthSlicedOnImport() {
        Loaded loaded = load("gamme-22-30j-80stands-256animateurs-mois-week-ends");
        Map<Boolean, List<Creneau>> parType = loaded.creneaux().stream()
                .collect(Collectors.partitioningBy(vacation -> isWeekend(vacation.getDate())));
        assertThat(parType.get(false))
                .allSatisfy(vacation -> assertThat(vacation.getDureeMinutes()).isEqualTo(300));
        assertThat(parType.get(true).stream().collect(Collectors.groupingBy(Creneau::getDate)))
                .allSatisfy((jour, vacations) -> assertThat(vacations)
                        .hasSize(4)
                        .filteredOn(Creneau::isCouverturePause)
                        .hasSize(1));
        assertThat(loaded.problem().getPostes()).hasSize(3736);

        assertFeasible(solveUntilFeasible(loaded, CEILING_SECONDS));
    }

    @Test
    void rung23AMonthOfWishesAndHandEnteredExceptions() {
        Loaded loaded = load("gamme-23-30j-100stands-144animateurs-ad-hoc-souhaits");
        assertThat(loaded.problem().getContraintesAdHoc()).hasSize(54);
        assertThat(loaded.feasibility().causes()).isEmpty();
        assertThat(loaded.problem().getPostes()).hasSize(3360);

        assertFeasible(solveUntilFeasible(loaded, CEILING_SECONDS));
    }

    @Test
    void rung24AMonthOnTwelveLocationsWithMinors() {
        Loaded loaded = load("gamme-24-30j-120stands-260animateurs-mineurs-emplacements");
        assertThat(loaded.stands())
                .allSatisfy(stand -> assertThat(stand.getEmplacement()).isNotNull());
        assertThat(loaded.problem().getPostes()).hasSize(5988);

        assertFeasible(solveUntilFeasible(loaded, CEILING_SECONDS));
    }

    /** The top of the ladder: everything a scenario file can say, at once. */
    @Test
    void rung25TheCompleteEdition() {
        Loaded loaded = load("gamme-25-30j-150stands-320animateurs-edition-complete");
        assertThat(loaded.sections().edition().orElseThrow().id()).isEqualTo("GAMME-25");
        assertThat(loaded.dayTemplatesPlan().isEmpty()).isTrue();
        assertThat(loaded.stands()).filteredOn(Stand::isPremium).hasSize(30);
        assertThat(loaded.problem().getContraintesAdHoc()).hasSize(32);
        // Owed seats only: this rung's stands declare an effectifMax above
        // what their windows ask for, so the generation adds renforts on top
        // (issue #505). The figure the ladder pins is the need, not the
        // capacity — pinning the sum would move it on any capacity edit.
        assertThat(loaded.problem().getPostes())
                .filteredOn(poste -> !poste.isOptionnel())
                .hasSize(5013);
        assertThat(loaded.problem().getPostes())
                .filteredOn(PosteAffectation::isOptionnel)
                .hasSize(80);
        assertThat(loaded.problem().getPostes())
                .filteredOn(poste -> standNumber(poste) >= 41 && standNumber(poste) <= 60)
                .isNotEmpty()
                .allSatisfy(poste -> assertThat(poste.getCreneau().getDate())
                        .isBetween(LocalDate.of(2027, 7, 10), LocalDate.of(2027, 7, 25)));

        assertFeasible(solveUntilFeasible(loaded, CEILING_SECONDS));
    }

    /** Renforts of one stand on one day, by the start time of their vacation. */
    private static Map<LocalTime, Long> renfortsParVacation(Loaded loaded, String standId, LocalDate date) {
        return seatsOnStand(loaded.problem().getPostes(), standId).stream()
                .filter(PosteAffectation::isOptionnel)
                .filter(poste -> poste.getCreneau().getDate().equals(date))
                .collect(Collectors.groupingBy(poste -> poste.getCreneau().getHeureDebut(), Collectors.counting()));
    }
}
