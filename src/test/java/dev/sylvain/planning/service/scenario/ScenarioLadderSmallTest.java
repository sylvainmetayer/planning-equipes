package dev.sylvain.planning.service.scenario;

import static dev.sylvain.planning.service.scenario.ScenarioLadder.assertFeasible;
import static dev.sylvain.planning.service.scenario.ScenarioLadder.endMinute;
import static dev.sylvain.planning.service.scenario.ScenarioLadder.load;
import static dev.sylvain.planning.service.scenario.ScenarioLadder.matchCounts;
import static dev.sylvain.planning.service.scenario.ScenarioLadder.seatCountByStandAndDate;
import static dev.sylvain.planning.service.scenario.ScenarioLadder.seatsByAnimateur;
import static dev.sylvain.planning.service.scenario.ScenarioLadder.seatsOf;
import static dev.sylvain.planning.service.scenario.ScenarioLadder.seatsOnStand;
import static dev.sylvain.planning.service.scenario.ScenarioLadder.solveFor;
import static dev.sylvain.planning.service.scenario.ScenarioLadder.solveUntilFeasible;
import static dev.sylvain.planning.service.scenario.ScenarioLadder.startMinute;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.service.scenario.ScenarioLadder.Loaded;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * The first rungs of the scenario ladder — one to three days, a handful of
 * stands — each solved to zero hard and then read back for the one thing the
 * file is about. Small enough for the default test run: every solve here
 * reaches feasibility in seconds.
 */
class ScenarioLadderSmallTest {

    private static final long CEILING_SECONDS = 60L;

    private static final LocalDate JUILLET_10 = LocalDate.of(2027, 7, 10);
    private static final LocalDate JUILLET_11 = LocalDate.of(2027, 7, 11);

    @Test
    void rung01OneDayTwoStandsThreeAnimateurs() {
        Loaded loaded = load("gamme-01-1j-2stands-3animateurs");
        assertThat(loaded.problem().getPostes()).hasSize(4);
        assertThat(loaded.feasibility().feasible()).isTrue();

        PlanningEvenement solved = solveUntilFeasible(loaded, CEILING_SECONDS);

        assertFeasible(solved);
    }

    /**
     * The midday rotation: the relay vacations open half a crew, and with
     * everybody needed morning and afternoon, each person takes exactly one of
     * the two relief hours and eats during the other.
     */
    @Test
    void rung02MiddayRelayHalvesTheCrewAndEveryoneKeepsTheirMealHour() {
        Loaded loaded = load("gamme-02-1j-2stands-4animateurs-relais-midi");
        assertThat(loaded.problem().getPostes()).hasSize(12);
        assertThat(loaded.problem().getPostes())
                .filteredOn(poste -> poste.getCreneau().isCouverturePause())
                .hasSize(4);
        assertThat(loaded.dayTemplatesPlan().isEmpty()).isTrue();

        PlanningEvenement solved = solveUntilFeasible(loaded, CEILING_SECONDS);

        assertFeasible(solved);
        long midi = LocalTime.NOON.toSecondOfDay() / 60;
        seatsByAnimateur(solved).forEach((animateur, postes) -> {
            assertThat(postes).as("%s works the whole day", animateur.getId()).hasSize(3);
            long minutesTenuesEntreMidiEtQuatorze = postes.stream()
                    .mapToLong(poste -> overlapOfDay(poste, midi, midi + 120))
                    .sum();
            assertThat(minutesTenuesEntreMidiEtQuatorze)
                    .as("%s keeps a free hour between noon and two", animateur.getId())
                    .isEqualTo(60);
        });
    }

    @Test
    void rung03NobodyOutsideTheirWishesAndNoBeginnerOnThePremiumStand() {
        Loaded loaded = load("gamme-03-1j-3stands-5animateurs-competences-souhaits");
        assertThat(loaded.sections().typologies()).hasSize(3);

        PlanningEvenement solved = solveFor(loaded, 10L);

        assertFeasible(solved);
        Map<String, Integer> matches = matchCounts(solved);
        assertThat(matches).doesNotContainKeys("souhaitsIncompatibles", "experienceRequisePourStandsPremium");
        assertThat(seatsOnStand(solved.getPostes(), "ESCAPE"))
                .allSatisfy(poste -> assertThat(poste.getAnimateur().getId()).isIn("A008", "A010"));
    }

    /**
     * A hand-written seat list is taken as written — one seat more than the
     * stand's minimum on Saturday afternoon — and its windows still narrow to
     * the stand's openings.
     */
    @Test
    void rung04ExplicitSeatsAndDaysOff() {
        Loaded loaded = load("gamme-04-2j-3stands-6animateurs-postes-explicites-indisponibilites");
        assertThat(loaded.problem().getPostes()).hasSize(10);
        assertThat(seatCountByStandAndDate(loaded.problem().getPostes()))
                .containsOnly(
                        entry("KAPLA", Map.of(JUILLET_10, 2L, JUILLET_11, 2L)),
                        entry("QUIZ", Map.of(JUILLET_10, 1L)),
                        entry("DOMINOS", Map.of(JUILLET_10, 3L, JUILLET_11, 2L)));

        PlanningEvenement solved = solveUntilFeasible(loaded, CEILING_SECONDS);

        assertFeasible(solved);
        PosteAffectation kaplaDimancheApresMidi = solved.getPostes().stream()
                .filter(poste -> poste.getId().equals("P-KAPLA-J2-APREM"))
                .findFirst()
                .orElseThrow();
        assertThat(kaplaDimancheApresMidi.heureFinEffectif()).isEqualTo(LocalTime.of(16, 30));
        PosteAffectation quiz = seatsOnStand(solved.getPostes(), "QUIZ").getFirst();
        assertThat(quiz.heureDebutEffectif()).isEqualTo(LocalTime.of(14, 0));
        assertThat(quiz.heureFinEffectif()).isEqualTo(LocalTime.of(17, 0));
        assertThat(seatsOf(solved, "A013"))
                .noneMatch(poste -> poste.getCreneau().getDate().equals(JUILLET_10));
        assertThat(seatsOf(solved, "A014"))
                .noneMatch(poste -> poste.getCreneau().getDate().equals(JUILLET_11));
        assertThat(solved.getPostes().stream()
                        .filter(poste -> poste.getId().startsWith("P-DOMINOS-J1-APREM"))
                        .map(poste -> poste.getAnimateur().getId())
                        .distinct())
                .hasSize(2);
    }

    /**
     * Minors: the adults-only bar, the evening past their legal night, never
     * alone on a stand — and the one who comes of age on Saturday may take the
     * bar that day only.
     */
    @Test
    void rung05MinorsAndTheBirthdayThatChangesTheRules() {
        Loaded loaded = load("gamme-05-2j-4stands-8animateurs-mineurs");
        assertThat(loaded.problem().getPostes()).hasSize(27);
        assertThat(loaded.dayTemplatesPlan().isEmpty()).isTrue();

        PlanningEvenement solved = solveUntilFeasible(loaded, CEILING_SECONDS);

        assertFeasible(solved);
        LocalDate vendredi = LocalDate.of(2027, 7, 16);
        assertThat(seatsOf(solved, "A023"))
                .filteredOn(poste -> poste.getStand().getId().equals("BAR-A-JEUX"))
                .allSatisfy(poste -> assertThat(poste.getCreneau().getDate()).isAfter(vendredi));
        Set<String> soiree = solved.getPostes().stream()
                .filter(poste -> poste.getCreneau().getHeureDebut().equals(LocalTime.of(20, 30)))
                .map(poste -> poste.getAnimateur().getId())
                .collect(Collectors.toSet());
        assertThat(soiree).hasSize(3).doesNotContain("A024", "A025", "A026");
        // Friday leaves no slack: every adult works both vacations.
        seatsByAnimateur(solved).entrySet().stream()
                .filter(e -> e.getKey().isMajeurOn(vendredi))
                .forEach(e -> assertThat(e.getValue())
                        .filteredOn(poste -> poste.getCreneau().getDate().equals(vendredi))
                        .as("adult %s on Friday", e.getKey().getId())
                        .hasSize(2));
    }

    @Test
    void rung06EveryKindOfAdHocRuleHolds() {
        Loaded loaded = load("gamme-06-3j-4stands-8animateurs-contraintes-ad-hoc");
        assertThat(loaded.problem().getContraintesAdHoc()).hasSize(6);
        assertThat(loaded.feasibility().causes()).isEmpty();

        PlanningEvenement solved = solveFor(loaded, 10L);

        assertFeasible(solved);
        assertThat(seatsOf(solved, "A027"))
                .noneMatch(poste -> poste.getStand().getId().equals("CONTES"));
        assertThat(seatsOf(solved, "A028"))
                .noneMatch(poste -> poste.getCreneau().getDate().equals(LocalDate.of(2027, 7, 24))
                        && poste.getCreneau().getHeureDebut().equals(LocalTime.of(14, 0)));
        Set<Object> creneauxA029 = seatsOf(solved, "A029").stream()
                .map(PosteAffectation::getCreneau)
                .collect(Collectors.toSet());
        assertThat(seatsOf(solved, "A030")).noneMatch(poste -> creneauxA029.contains(poste.getCreneau()));
        assertThat(seatsOf(solved, "A031"))
                .anyMatch(poste -> poste.getCreneau().getDate().equals(LocalDate.of(2027, 7, 25))
                        && poste.getCreneau().getHeureDebut().equals(LocalTime.of(10, 0)));
        assertThat(seatsOf(solved, "A032"))
                .anyMatch(poste -> poste.getStand().getId().equals("GRAND-JEU"));
        // The affinity reaches the solver, and that is all this file can pin: a
        // soft reward never outweighs the medium level, so whether the pair ends
        // up together depends on what the load balancing leaves free.
        assertThat(solved.getContraintesAdHoc())
                .filteredOn(contrainte -> contrainte.getId().equals("C06"))
                .singleElement()
                .satisfies(contrainte -> assertThat(contrainte.getAnimateursConcernes())
                        .extracting(animateur -> animateur.getId())
                        .containsExactly("A033", "A034"));
    }

    /**
     * Every scope of a recurring opening, read back as seats: which days a
     * stand opens, how many seats a window carries, and where a dated rule or
     * a dated exception overrides the general one.
     */
    @Test
    void rung07RecurringOpeningsInEveryScope() {
        Loaded loaded = load("gamme-07-3j-5stands-10animateurs-horaires-recurrents");
        LocalDate jeudi = LocalDate.of(2027, 7, 29);
        LocalDate vendredi = jeudi.plusDays(1);
        LocalDate samedi = jeudi.plusDays(2);
        assertThat(seatCountByStandAndDate(loaded.problem().getPostes()))
                .containsOnly(
                        entry("BOURSE", Map.of(jeudi, 3L, vendredi, 3L, samedi, 3L)),
                        entry("PODIUM", Map.of(vendredi, 2L, samedi, 2L)),
                        entry("MEDIATHEQUE", Map.of(jeudi, 2L, vendredi, 2L, samedi, 1L)),
                        entry("GRAND-JEU", Map.of(vendredi, 7L, samedi, 7L)),
                        entry("ATELIER", Map.of(jeudi, 1L, vendredi, 1L, samedi, 1L)));
        assertThat(seatsOnStand(loaded.problem().getPostes(), "MEDIATHEQUE"))
                .filteredOn(poste -> poste.getCreneau().getDate().equals(samedi))
                .singleElement()
                .satisfies(poste -> assertThat(poste.heureFinEffectif()).isEqualTo(LocalTime.NOON));
        assertThat(seatsOnStand(loaded.problem().getPostes(), "ATELIER"))
                .filteredOn(poste -> poste.getCreneau().getDate().equals(samedi))
                .singleElement()
                .satisfies(
                        poste -> assertThat(poste.getCreneau().getHeureDebut()).isEqualTo(LocalTime.of(10, 0)));

        PlanningEvenement solved = solveUntilFeasible(loaded, CEILING_SECONDS);

        assertFeasible(solved);
    }

    /** Minutes of {@code poste} that fall within {@code [from, to)} minutes of its own day. */
    private static long overlapOfDay(PosteAffectation poste, long from, long to) {
        long dayStart = poste.getCreneau().getDate().toEpochDay() * 24 * 60;
        long start = startMinute(poste) - dayStart;
        long end = endMinute(poste) - dayStart;
        return Math.max(0, Math.min(end, to) - Math.max(start, from));
    }
}
