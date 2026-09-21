package dev.sylvain.planning.service.scenario;

import static dev.sylvain.planning.service.scenario.ScenarioLadder.assertFeasible;
import static dev.sylvain.planning.service.scenario.ScenarioLadder.endMinute;
import static dev.sylvain.planning.service.scenario.ScenarioLadder.isoWeek;
import static dev.sylvain.planning.service.scenario.ScenarioLadder.load;
import static dev.sylvain.planning.service.scenario.ScenarioLadder.matchCounts;
import static dev.sylvain.planning.service.scenario.ScenarioLadder.seatsByAnimateur;
import static dev.sylvain.planning.service.scenario.ScenarioLadder.seatsOnStand;
import static dev.sylvain.planning.service.scenario.ScenarioLadder.solveUntilFeasible;
import static dev.sylvain.planning.service.scenario.ScenarioLadder.startMinute;
import static org.assertj.core.api.Assertions.assertThat;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.service.scenario.ScenarioLadder.Loaded;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Rungs 8 to 15 of the scenario ladder: a few days to two weeks, up to twenty
 * stands and fifty-odd animateurs. Each one still reaches zero hard in its
 * construction heuristic — about a second — so they stay in the default run,
 * and the plan they read back does not depend on the machine's speed.
 */
class ScenarioLadderMediumTest {

    private static final long CEILING_SECONDS = 120L;

    @Test
    void rung08AmplitudesSlicedAroundTheMealWithAReducedCrew() {
        Loaded loaded = load("gamme-08-3j-5stands-16animateurs-relais-midi-reduit");
        assertThat(loaded.creneaux()).hasSize(12);
        assertThat(loaded.creneaux())
                .allSatisfy(vacation -> assertThat(vacation.getDureeMinutes()).isLessThanOrEqualTo(300));
        Map<LocalDate, List<Creneau>> parJour =
                loaded.creneaux().stream().collect(Collectors.groupingBy(Creneau::getDate));
        assertThat(parJour)
                .hasSize(3)
                .allSatisfy((jour, vacations) -> assertThat(vacations)
                        .filteredOn(Creneau::isCouverturePause)
                        .singleElement()
                        .satisfies(pause -> assertThat(pause.getHeureDebut()).isEqualTo(LocalTime.NOON)));
        // Seven seats a vacation, five on the meal relay: the two-person stands halve, the others keep one.
        assertThat(loaded.problem().getPostes()).hasSize(78);
        assertThat(loaded.problem().getPostes())
                .filteredOn(poste -> poste.getCreneau().isCouverturePause())
                .hasSize(15);

        assertFeasible(solveUntilFeasible(loaded, CEILING_SECONDS));
    }

    /**
     * Two stands with a single specialist each: every other seat of theirs
     * goes to a polyvalent, and nobody holds a stand outside their skills.
     */
    @Test
    void rung09PolyvalentsCoverTheStandsNobodyElseCan() {
        Loaded loaded = load("gamme-09-4j-6stands-14animateurs-typologies-ninja-emplacements");
        assertThat(loaded.problem().getAnimateurs())
                .filteredOn(Animateur::isNinja)
                .extracting(Animateur::getId)
                .containsExactlyInAnyOrder("A173", "A174", "A175");
        assertThat(loaded.stands())
                .allSatisfy(stand -> assertThat(stand.getEmplacement()).isNotNull());

        PlanningEvenement solved = solveUntilFeasible(loaded, CEILING_SECONDS);

        assertFeasible(solved);
        assertThat(matchCounts(solved)).doesNotContainKey("appreciationIncompatible");
        assertThat(seatsOnStand(solved.getPostes(), "CIRQUE"))
                .allSatisfy(poste -> assertThat(poste.getAnimateur().getId()).isIn("A171", "A173", "A174", "A175"));
        assertThat(seatsOnStand(solved.getPostes(), "ROBOTIQUE"))
                .allSatisfy(poste -> assertThat(poste.getAnimateur().getId()).isIn("A172", "A173", "A174", "A175"));
    }

    @Test
    void rung10ThreeDayTemplatesAndAnEditionOfItsOwn() {
        Loaded loaded = load("gamme-10-4j-8stands-20animateurs-journees-types-multiples");
        assertThat(loaded.dayTemplatesPlan().isEmpty()).isTrue();
        assertThat(loaded.sections().journeesTypes().orElseThrow().journeesTypes())
                .extracting(template -> template.getNom())
                .containsExactly("Montage", "Jour normal", "Nocturne");
        assertThat(loaded.sections().edition().orElseThrow().id()).isEqualTo("GAMME-10");
        assertThat(loaded.problem().getPostes()).hasSize(127);
        // The nocturne is held by the three stands open past 18:00, on the last day only.
        assertThat(loaded.problem().getPostes())
                .filteredOn(poste -> poste.getCreneau().getHeureDebut().equals(LocalTime.of(20, 0)))
                .hasSize(6)
                .allSatisfy(poste -> {
                    assertThat(poste.getCreneau().getDate()).isEqualTo(LocalDate.of(2027, 9, 4));
                    assertThat(poste.getStand().getId()).isIn("PLATEAU", "ESCAPE", "ARCADE");
                });

        assertFeasible(solveUntilFeasible(loaded, CEILING_SECONDS));
    }

    /**
     * Across two civil weeks, with evenings past midnight: the 30-hour cap
     * holds per ISO week, and whoever closes at one in the morning rests
     * eleven hours before their next seat.
     */
    @Test
    void rung11WeeklyCapAcrossTwoWeeksAndNightsPastMidnight() {
        Loaded loaded = load("gamme-11-5j-8stands-18animateurs-semaine-a-cheval-nuit");
        assertThat(loaded.problem().getPostes()).hasSize(53);
        assertThat(loaded.problem().getPostes())
                .filteredOn(poste -> poste.getCreneau()
                        .getHeureFin()
                        .isBefore(poste.getCreneau().getHeureDebut()))
                .hasSize(4)
                .allSatisfy(poste -> assertThat(poste.getStand().getId()).isEqualTo("BAR-A-JEUX"));

        PlanningEvenement solved = solveUntilFeasible(loaded, CEILING_SECONDS);

        assertFeasible(solved);
        seatsByAnimateur(solved).forEach((animateur, postes) -> {
            postes.stream()
                    .collect(Collectors.groupingBy(
                            poste -> isoWeek(poste.getCreneau().getDate()),
                            Collectors.summingLong(poste -> endMinute(poste) - startMinute(poste))))
                    .forEach((semaine, minutes) -> assertThat(minutes)
                            .as("%s in %s", animateur.getId(), semaine)
                            .isLessThanOrEqualTo(1800));
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

    /**
     * A whole civil week: nobody past six days, no minor on the fourteenth of
     * July, and every minor with two consecutive days off inside the week.
     */
    @Test
    void rung12AFullWeekAHolidayAndTheMinorsWeeklyRest() {
        Loaded loaded = load("gamme-12-7j-10stands-24animateurs-semaine-complete-ferie");
        assertThat(loaded.problem().getPostes()).hasSize(168);

        PlanningEvenement solved = solveUntilFeasible(loaded, CEILING_SECONDS);

        assertFeasible(solved);
        LocalDate lundi = LocalDate.of(2027, 7, 12);
        seatsByAnimateur(solved).forEach((animateur, postes) -> {
            if (!animateur.isMineurOn(lundi)) {
                return;
            }
            Set<LocalDate> travailles = postes.stream()
                    .map(poste -> poste.getCreneau().getDate())
                    .collect(Collectors.toCollection(TreeSet::new));
            assertThat(travailles)
                    .as("minor %s on the holiday", animateur.getId())
                    .doesNotContain(LocalDate.of(2027, 7, 14));
            boolean deuxJoursDeSuite = lundi.datesUntil(lundi.plusDays(6))
                    .anyMatch(jour -> !travailles.contains(jour) && !travailles.contains(jour.plusDays(1)));
            assertThat(deuxJoursDeSuite)
                    .as("minor %s keeps two consecutive days off", animateur.getId())
                    .isTrue();
        });
    }

    /** The dosage the file carries keeps beginners off the premium stands and nobody on two exhausting ones in a row. */
    @Test
    void rung13PremiumAndExhaustingStandsUnderTheirDosage() {
        Loaded loaded = load("gamme-13-7j-12stands-30animateurs-premium-epuisants");
        assertThat(loaded.problem().getPonderationsScenario())
                .containsEntry("experienceRequisePourStandsPremium", 3)
                .containsEntry("eviterEnchainementStandsEpuisants", 2);

        PlanningEvenement solved = solveUntilFeasible(loaded, CEILING_SECONDS);

        assertFeasible(solved);
        assertThat(matchCounts(solved))
                .doesNotContainKeys("experienceRequisePourStandsPremium", "eviterEnchainementStandsEpuisants");
        assertThat(solved.getPostes())
                .filteredOn(poste -> poste.getStand().isPremium())
                .noneMatch(poste -> poste.getAnimateur().isDebutantFor(poste.getStand()));
    }

    /**
     * The relief strategy: on a long weekend day the découpage cuts around the
     * meal and puts a full crew on the break, and consecutive vacations overlap
     * by a quarter of an hour.
     */
    @Test
    void rung14AFullReliefCrewOnTheBreakAndOverlappingHandovers() {
        Loaded loaded = load("gamme-14-10j-15stands-52animateurs-releve-repas");
        assertThat(loaded.problem().getParametresLegaux().getFirst().getCoupureRepasMinutes())
                .isEqualTo(45);
        assertThat(loaded.creneaux()).hasSize(26);
        LocalDate samedi = LocalDate.of(2027, 9, 4);
        List<Creneau> samediVacations = loaded.creneaux().stream()
                .filter(vacation -> vacation.getDate().equals(samedi))
                .sorted((a, b) -> a.getHeureDebut().compareTo(b.getHeureDebut()))
                .toList();
        assertThat(samediVacations)
                .extracting(Creneau::getHeureDebut)
                .containsExactly(
                        LocalTime.of(10, 0),
                        LocalTime.of(12, 0),
                        LocalTime.of(12, 45),
                        LocalTime.of(13, 45),
                        LocalTime.of(17, 30));
        Map<Creneau, Long> siegesSamedi = loaded.problem().getPostes().stream()
                .filter(poste -> poste.getCreneau().getDate().equals(samedi))
                .collect(Collectors.groupingBy(PosteAffectation::getCreneau, Collectors.counting()));
        assertThat(siegesSamedi.get(samediVacations.get(1))).isEqualTo(siegesSamedi.get(samediVacations.get(0)));
        loaded.creneaux().stream()
                .filter(vacation -> vacation.getDate().getDayOfWeek().getValue() <= 5)
                .collect(Collectors.groupingBy(Creneau::getDate))
                .forEach((jour, vacations) -> assertThat(vacations)
                        .extracting(Creneau::getHeureDebut, Creneau::getHeureFin)
                        .containsExactlyInAnyOrder(
                                org.assertj.core.groups.Tuple.tuple(LocalTime.of(13, 0), LocalTime.of(17, 0)),
                                org.assertj.core.groups.Tuple.tuple(LocalTime.of(16, 45), LocalTime.of(19, 0))));
        assertThat(loaded.problem().getPostes()).hasSize(494);

        assertFeasible(solveUntilFeasible(loaded, CEILING_SECONDS));
    }

    /**
     * Weekday afternoons and weekends, with empty days in between: weekend-only
     * stands open no weekday seat, the hollow Monday and Tuesday none at all,
     * and the students work weekends only.
     */
    @Test
    void rung15TwoWeeksOfWeekendsWithHollowDays() {
        Loaded loaded = load("gamme-15-10j-20stands-56animateurs-deux-week-ends");
        assertThat(loaded.dayTemplatesPlan().isEmpty()).isTrue();
        assertThat(loaded.problem().getPostes()).hasSize(472);
        assertThat(loaded.problem().getPostes())
                .filteredOn(poste -> Integer.parseInt(poste.getStand().getId().substring("STAND-".length())) > 12)
                .allSatisfy(poste -> assertThat(poste.getCreneau().getDate().getDayOfWeek())
                        .isIn(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY));
        assertThat(loaded.problem().getPostes())
                .extracting(poste -> poste.getCreneau().getDate())
                .doesNotContain(LocalDate.of(2027, 6, 7), LocalDate.of(2027, 6, 8));

        PlanningEvenement solved = solveUntilFeasible(loaded, CEILING_SECONDS);

        assertFeasible(solved);
        assertThat(solved.getPostes())
                .filteredOn(poste -> poste.getAnimateur().getId().compareTo("A356") < 0)
                .allSatisfy(poste -> assertThat(poste.getCreneau().getDate().getDayOfWeek())
                        .isIn(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY));
    }
}
