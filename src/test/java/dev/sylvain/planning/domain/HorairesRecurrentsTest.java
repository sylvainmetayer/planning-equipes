package dev.sylvain.planning.domain;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sylvain.planning.domain.HorairesRecurrents.Regle;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * What the recurring rules of 3.x mean, as the migration and the scenario-file
 * link read them: the layering of dated exceptions over rules, specificity,
 * and the reading of a day nobody names.
 *
 * <p>Dates follow the reference event: 2026-07-08 is a Wednesday,
 * 2026-07-11 a Saturday.</p>
 */
class HorairesRecurrentsTest {

    private static final LocalDate MERCREDI = LocalDate.of(2026, 7, 8);
    private static final List<LocalDate> DOUZE_JOURS =
            java.util.stream.IntStream.range(0, 12).mapToObj(MERCREDI::plusDays).toList();

    private static List<PlageStand> expand(List<Regle> regles, PlageStand... exceptions) {
        return HorairesRecurrents.expand(regles, List.of(exceptions), DOUZE_JOURS);
    }

    private static List<PlageStand> openings(List<PlageStand> plages) {
        return plages.stream().filter(p -> p.getMode() == ModeHoraire.OUVERTURE).toList();
    }

    private static List<PlageStand> closures(List<PlageStand> plages) {
        return plages.stream().filter(p -> p.getMode() == ModeHoraire.FERMETURE).toList();
    }

    /** The AUTRES-BOURSE case: 24 dated windows for twelve days of a lunch break, written as one rule. */
    @Test
    void aDailyRuleBecomesOnePlagePerDayAndWindow() {
        List<PlageStand> plages = expand(List.of(Regle.everyDay(
                ModeHoraire.OUVERTURE,
                new FenetreHoraire(LocalTime.of(10, 0), LocalTime.of(12, 0)),
                new FenetreHoraire(LocalTime.of(14, 0), null))));

        assertThat(openings(plages)).hasSize(24);
        assertThat(openings(plages)).extracting(PlageStand::getDate).containsAll(DOUZE_JOURS);
        assertThat(closures(plages)).isEmpty();
    }

    @Test
    void noRuleLeavesTheExceptionsAlone() {
        PlageStand fermeture = PlageStand.fermeture(null, MERCREDI, LocalTime.of(14, 0), LocalTime.of(16, 0), null);

        assertThat(expand(List.of(), fermeture)).containsExactly(fermeture);
    }

    @Test
    void theMostSpecificRuleWins() {
        Regle semaine = Regle.everyDay(ModeHoraire.OUVERTURE, new FenetreHoraire(LocalTime.of(14, 0), null));
        Regle weekend = new Regle(
                ModeHoraire.OUVERTURE,
                TypeJoursHoraire.JOURS_SEMAINE,
                Set.of(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY),
                null,
                null,
                null,
                List.of(new FenetreHoraire(LocalTime.of(10, 0), null)),
                null);

        List<PlageStand> plages = expand(List.of(semaine, weekend));

        assertThat(startTimeOn(plages, MERCREDI)).containsExactly(LocalTime.of(14, 0));
        assertThat(startTimeOn(plages, LocalDate.of(2026, 7, 11))).containsExactly(LocalTime.of(10, 0));
    }

    @Test
    void aDatedExceptionWinsOverTheRulesAndReplacesThemThatDay() {
        LocalDate ferie = MERCREDI.plusDays(2);
        PlageStand exception = PlageStand.fermeture(null, ferie, LocalTime.of(10, 0), null, "Férié");

        List<PlageStand> plages = expand(
                List.of(Regle.everyDay(ModeHoraire.OUVERTURE, new FenetreHoraire(LocalTime.of(14, 0), null))),
                exception);

        assertThat(openings(plages))
                .extracting(PlageStand::getDate)
                .doesNotContain(ferie)
                .hasSize(11);
        assertThat(closures(plages)).containsExactly(exception);
    }

    /** "Open on the 14th" is a schedule: the other eleven days are days this stand does not open. */
    @Test
    void aDayNobodyNamesIsShutForAStandThatDeclaresOpenings() {
        Regle leQuatorze = onDates(ModeHoraire.OUVERTURE, LocalDate.of(2026, 7, 14));

        List<PlageStand> plages = expand(List.of(leQuatorze));

        assertThat(openings(plages)).extracting(PlageStand::getDate).containsExactly(LocalDate.of(2026, 7, 14));
        assertThat(closures(plages))
                .hasSize(DOUZE_JOURS.size() - 1)
                .allSatisfy(fermeture -> {
                    assertThat(fermeture.getHeureDebut()).isEqualTo(LocalTime.MIDNIGHT);
                    assertThat(fermeture.getHeureFin()).isNull();
                    assertThat(fermeture.getMotif()).isEqualTo(HorairesRecurrents.MOTIF_JOUR_NON_DECLARE);
                })
                .extracting(PlageStand::getDate)
                .doesNotContain(LocalDate.of(2026, 7, 14));
    }

    /** The mirror: rules that only ever close list exceptions to being open, and the other days stay open all day. */
    @Test
    void aDayNobodyNamesStaysOpenForAStandThatOnlyDeclaresClosures() {
        List<PlageStand> plages = expand(List.of(onDates(ModeHoraire.FERMETURE, LocalDate.of(2026, 7, 14))));

        assertThat(openings(plages)).isEmpty();
        assertThat(closures(plages)).extracting(PlageStand::getDate).containsExactly(LocalDate.of(2026, 7, 14));
    }

    /** Two rules of equal specificity disagreeing on the mode: the more restrictive reading, the opening. */
    @Test
    void atEqualSpecificityTheOpeningWins() {
        List<PlageStand> plages = expand(List.of(
                Regle.everyDay(ModeHoraire.FERMETURE, new FenetreHoraire(LocalTime.of(14, 0), LocalTime.of(16, 0))),
                Regle.everyDay(ModeHoraire.OUVERTURE, new FenetreHoraire(LocalTime.of(10, 0), LocalTime.of(12, 0)))));

        assertThat(openings(plages)).hasSize(12);
        assertThat(closures(plages)).isEmpty();
    }

    /** A window's headcount rides along onto every plage the rule produces. */
    @Test
    void theWindowsHeadcountIsCarried() {
        List<PlageStand> plages = expand(List.of(Regle.everyDay(
                ModeHoraire.OUVERTURE, new FenetreHoraire(LocalTime.of(10, 0), LocalTime.of(12, 0), 3))));

        assertThat(plages).allSatisfy(plage -> assertThat(plage.getEffectif()).isEqualTo(3));
    }

    /** Only the days a window could be read on: the créneau dates, and the morning after one crossing midnight. */
    @Test
    void theDaysFollowTheTimeslots() {
        Creneau journee = new Creneau(1L, 1, MERCREDI, LocalTime.of(10, 0), LocalTime.of(20, 0));
        Creneau nuit = new Creneau(2L, 2, MERCREDI.plusDays(1), LocalTime.of(20, 0), LocalTime.of(2, 0));

        assertThat(HorairesRecurrents.datesConcernees(List.of(journee))).containsExactly(MERCREDI);
        assertThat(HorairesRecurrents.datesConcernees(List.of(journee, nuit)))
                .containsExactly(MERCREDI, MERCREDI.plusDays(1), MERCREDI.plusDays(2));
    }

    private static Regle onDates(ModeHoraire mode, LocalDate... dates) {
        return new Regle(
                mode,
                TypeJoursHoraire.DATES,
                null,
                null,
                null,
                Set.of(dates),
                List.of(new FenetreHoraire(LocalTime.of(14, 0), null)),
                null);
    }

    private static List<LocalTime> startTimeOn(List<PlageStand> plages, LocalDate date) {
        return openings(plages).stream()
                .filter(plage -> plage.getDate().equals(date))
                .map(PlageStand::getHeureDebut)
                .toList();
    }
}
