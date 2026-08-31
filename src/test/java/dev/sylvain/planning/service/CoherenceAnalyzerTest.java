package dev.sylvain.planning.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.FenetreHoraire;
import dev.sylvain.planning.domain.HoraireStand;
import dev.sylvain.planning.domain.IndisponibiliteStand;
import dev.sylvain.planning.domain.ModeHoraire;
import dev.sylvain.planning.domain.OuvertureStand;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.domain.TypeJoursHoraire;

/**
 * {@link CoherenceAnalyzer}: the three warnings a write reports without
 * refusing anything.
 *
 * <p>Every rule is tested both ways. A test that only proves the shout proves
 * nothing about the silence, and a warning that fires on correct data is worse
 * than no warning at all — it teaches the operator to dismiss the whole
 * mechanism.</p>
 *
 * <p>2026-07-08 is a Wednesday, as in the reference event.</p>
 */
class CoherenceAnalyzerTest {

    private static final LocalDate JOUR_1 = LocalDate.of(2026, 7, 8);
    private static final LocalDate JOUR_3 = LocalDate.of(2026, 7, 10);

    /** Three days, 10:00→20:00 each — the shape of the amplitude fixtures. */
    private static List<Creneau> troisJours() {
        return new ArrayList<>(List.of(
                creneau(1L, JOUR_1, 10, 20),
                creneau(2L, JOUR_1.plusDays(1), 10, 20),
                creneau(3L, JOUR_3, 10, 20)));
    }

    private static Creneau creneau(Long id, LocalDate date, int debut, int fin) {
        return new Creneau(id, 0, date, LocalTime.of(debut, 0), LocalTime.of(fin, 0));
    }

    private static Stand stand(String id) {
        Stand stand = new Stand(id, id, Set.of(), 1, 1, false);
        stand.setIndisponibilites(new ArrayList<>());
        stand.setOuvertures(new ArrayList<>());
        stand.setHoraires(new ArrayList<>());
        return stand;
    }

    private static Animateur animateur(LocalDate dateNaissance) {
        return new Animateur("A-1", "Camille", "Durand", dateNaissance, false);
    }

    /** Resolves the recurring rules the way {@link CoherenceService} does, then analyses. */
    private static List<Avertissement> surCreneau(Creneau creneau, List<Stand> stands) {
        HoraireStandResolver.apply(stands, List.of(creneau));
        return CoherenceAnalyzer.onCreneau(creneau, stands);
    }

    private static List<TypeAvertissement> types(List<Avertissement> avertissements) {
        return avertissements.stream().map(Avertissement::type).toList();
    }

    /* ------------------------- Bounds of the edition ------------------------ */

    @Test
    void anEditionWithoutTimeslotHasNoBoundsAndWarnsAboutNothing() {
        Animateur camille = animateur(LocalDate.of(2015, 1, 1));
        camille.setJoursIndisponibles(new TreeSet<>(Set.of(LocalDate.of(1999, 1, 1))));

        assertThat(CoherenceAnalyzer.onAnimateur(camille, JoursEvenement.of(List.of()))).isEmpty();
    }

    @Test
    void theSpanCoversAGapDayBetweenTwoTimeslotDates() {
        JoursEvenement jours = JoursEvenement.of(troisJours());

        assertThat(jours.first()).isEqualTo(JOUR_1);
        assertThat(jours.last()).isEqualTo(JOUR_3);
        // 2026-07-09 carries a créneau, 2026-07-10 too; the span is continuous
        // and a date one day past the last is outside it.
        assertThat(jours.covers(JOUR_3)).isTrue();
        assertThat(jours.covers(JOUR_3.plusDays(1))).isFalse();
        assertThat(jours.covers(JOUR_1.minusDays(1))).isFalse();
    }

    /**
     * A gap day is inside the span, so it is <b>not</b> "outside the event" —
     * but it carries no créneau, and the availability circuit only ever keeps
     * the dates of the créneaux: the day is doomed all the same. The two facts
     * get two types, and neither may be silent.
     */
    @Test
    void aGapDayInsideTheSpanIsReportedAsCarryingNoTimeslotRatherThanAsOutside() {
        // Two dates a week apart: everything between them is inside the span,
        // although most of it carries no créneau at all.
        List<Creneau> ecartes = List.of(creneau(1L, JOUR_1, 10, 20), creneau(2L, JOUR_1.plusDays(7), 10, 20));
        Animateur camille = animateur(LocalDate.of(1990, 1, 1));
        camille.setJoursIndisponibles(new TreeSet<>(Set.of(JOUR_1.plusDays(3))));

        List<Avertissement> avertissements =
                CoherenceAnalyzer.onAnimateur(camille, JoursEvenement.of(ecartes));

        assertThat(types(avertissements))
                .containsExactly(TypeAvertissement.INDISPONIBILITE_JOUR_SANS_CRENEAU);
        assertThat(avertissements.get(0).message())
                .contains("2026-07-11")
                .contains("déclaration de disponibilités");
    }

    /** The span itself still ignores the gap: {@code covers} is unchanged. */
    @Test
    void theSpanStillCoversAGapDayEvenThoughItCarriesNoTimeslot() {
        JoursEvenement jours = JoursEvenement.of(
                List.of(creneau(1L, JOUR_1, 10, 20), creneau(2L, JOUR_1.plusDays(7), 10, 20)));

        assertThat(jours.covers(JOUR_1.plusDays(3))).isTrue();
        assertThat(jours.hasCreneauOn(JOUR_1.plusDays(3))).isFalse();
        assertThat(jours.hasCreneauOn(JOUR_1)).isTrue();
    }

    /* --------------------------- Off days, rule 1 --------------------------- */

    @Test
    void anOffDayOutsideTheSpanIsReportedWithBothBounds() {
        Animateur camille = animateur(LocalDate.of(1990, 1, 1));
        camille.setJoursIndisponibles(new TreeSet<>(Set.of(LocalDate.of(2026, 8, 9))));

        List<Avertissement> avertissements =
                CoherenceAnalyzer.onAnimateur(camille, JoursEvenement.of(troisJours()));

        assertThat(types(avertissements)).containsExactly(TypeAvertissement.INDISPONIBILITE_HORS_EVENEMENT);
        assertThat(avertissements.get(0).message())
                .contains("2026-08-09")
                .contains("2026-07-08")
                .contains("2026-07-10");
    }

    @Test
    void anOffDayOnEitherBoundIsSilent() {
        Animateur camille = animateur(LocalDate.of(1990, 1, 1));
        camille.setJoursIndisponibles(new TreeSet<>(Set.of(JOUR_1, JOUR_3)));

        assertThat(CoherenceAnalyzer.onAnimateur(camille, JoursEvenement.of(troisJours()))).isEmpty();
    }

    @Test
    void severalOffDaysOutsideTheSpanAreGatheredInOneWarning() {
        Animateur camille = animateur(LocalDate.of(1990, 1, 1));
        camille.setJoursIndisponibles(new TreeSet<>(Set.of(
                LocalDate.of(2025, 7, 8), LocalDate.of(2027, 7, 8), JOUR_1)));

        List<Avertissement> avertissements =
                CoherenceAnalyzer.onAnimateur(camille, JoursEvenement.of(troisJours()));

        assertThat(avertissements).hasSize(1);
        assertThat(avertissements.get(0).message()).contains("2025-07-08").contains("2027-07-08");
    }

    /* ---------------------------- Minority, rule 2 --------------------------- */

    @Test
    void anAnimateurWithoutBirthDateIsNeverReportedAsAMinor() {
        Animateur inconnu = animateur(null);

        assertThat(CoherenceAnalyzer.onAnimateur(inconnu, JoursEvenement.of(troisJours()))).isEmpty();
    }

    @Test
    void anAdultThroughoutTheEventIsSilent() {
        // Eighteen well before the event.
        Animateur adulte = animateur(LocalDate.of(2000, 3, 4));

        assertThat(CoherenceAnalyzer.onAnimateur(adulte, JoursEvenement.of(troisJours()))).isEmpty();
    }

    @Test
    void aMinorOnEveryDayIsReportedAsSuchWithTheWholeSpan() {
        Animateur jeune = animateur(LocalDate.of(2012, 5, 1));

        List<Avertissement> avertissements =
                CoherenceAnalyzer.onAnimateur(jeune, JoursEvenement.of(troisJours()));

        assertThat(types(avertissements)).containsExactly(TypeAvertissement.MINEUR_PENDANT_EVENEMENT);
        assertThat(avertissements.get(0).message())
                .contains("tout l'événement")
                .contains("2026-07-08")
                .contains("2026-07-10");
    }

    /**
     * The case a boolean could never express: minor on the first day, adult on
     * the last. The message must name the day the regime changes.
     */
    @Test
    void turningEighteenDuringTheEventNamesTheDayTheRegimeChanges() {
        Animateur bascule = animateur(LocalDate.of(2008, 7, 9));

        List<Avertissement> avertissements =
                CoherenceAnalyzer.onAnimateur(bascule, JoursEvenement.of(troisJours()));

        assertThat(types(avertissements)).containsExactly(TypeAvertissement.MINEUR_PENDANT_EVENEMENT);
        assertThat(avertissements.get(0).message())
                .contains("devient majeur le 2026-07-09")
                .contains("mineur du 2026-07-08 au 2026-07-08");
    }

    /** Eighteen exactly on the first day: adult all along, nothing to say. */
    @Test
    void anEighteenthBirthdayOnTheFirstDayIsSilent() {
        Animateur pilePoil = animateur(JOUR_1.minusYears(18));

        assertThat(CoherenceAnalyzer.onAnimateur(pilePoil, JoursEvenement.of(troisJours()))).isEmpty();
    }

    /** Eighteen exactly on the last day: minor for two days out of three. */
    @Test
    void anEighteenthBirthdayOnTheLastDayIsReported() {
        Animateur juste = animateur(JOUR_3.minusYears(18));

        List<Avertissement> avertissements =
                CoherenceAnalyzer.onAnimateur(juste, JoursEvenement.of(troisJours()));

        assertThat(avertissements.get(0).message()).contains("devient majeur le 2026-07-10");
    }

    /**
     * Born on a February 29th: {@code dateNaissance.plusYears(18)} would say
     * February 28th, one day before {@code isMineurOn} lets go. The warning
     * follows the domain, not the arithmetic shortcut.
     */
    @Test
    void aLeapDayBirthDateFollowsIsMineurOnRatherThanPlusYears() {
        Animateur bissextile = new Animateur("A-BIS", "Alix", "Février", LocalDate.of(2008, 2, 29), false);
        List<Creneau> autourDuVingtHuit = List.of(
                creneau(1L, LocalDate.of(2026, 2, 27), 10, 20),
                creneau(2L, LocalDate.of(2026, 2, 28), 10, 20),
                creneau(3L, LocalDate.of(2026, 3, 2), 10, 20));

        List<Avertissement> avertissements =
                CoherenceAnalyzer.onAnimateur(bissextile, JoursEvenement.of(autourDuVingtHuit));

        assertThat(bissextile.isMineurOn(LocalDate.of(2026, 2, 28))).isTrue();
        assertThat(avertissements.get(0).message()).contains("devient majeur le 2026-03-01");
    }

    /* --------------------- Only what the write changed ---------------------- */

    /**
     * The cry-wolf this class exists to avoid. The bulk edit sends the whole
     * merged fiche — date de naissance included — one {@code PUT} per row, so
     * ticking thirty volunteers to add a competence must not end on a snack bar
     * naming every minor of the selection.
     */
    @Test
    void editingAMinorWithoutTouchingTheBirthDateSaysNothingAboutTheirMinority() {
        Animateur avant = animateur(LocalDate.of(2012, 5, 1));
        Animateur apres = animateur(LocalDate.of(2012, 5, 1));
        apres.setEmail("camille@example.org");

        assertThat(CoherenceAnalyzer.onAnimateur(avant, apres, JoursEvenement.of(troisJours()))).isEmpty();
    }

    /** Touch the field and the rule speaks again: it is the change that is news. */
    @Test
    void changingTheBirthDateOfAnAlreadyMinorFicheWarnsAgain() {
        Animateur avant = animateur(LocalDate.of(2012, 5, 1));
        Animateur apres = animateur(LocalDate.of(2011, 5, 1));

        assertThat(types(CoherenceAnalyzer.onAnimateur(avant, apres, JoursEvenement.of(troisJours()))))
                .containsExactly(TypeAvertissement.MINEUR_PENDANT_EVENEMENT);
    }

    /** Filling in a date de naissance that was blank is a change too. */
    @Test
    void fillingInAPreviouslyMissingBirthDateWarns() {
        Animateur avant = animateur(null);
        Animateur apres = animateur(LocalDate.of(2012, 5, 1));

        assertThat(types(CoherenceAnalyzer.onAnimateur(avant, apres, JoursEvenement.of(troisJours()))))
                .containsExactly(TypeAvertissement.MINEUR_PENDANT_EVENEMENT);
    }

    /** Same reasoning on the off days: an unchanged bad day is not news of this write. */
    @Test
    void anOffDayAlreadyOutsideTheSpanIsNotRepeatedOnAnUnrelatedEdit() {
        Animateur avant = animateur(LocalDate.of(1990, 1, 1));
        avant.setJoursIndisponibles(new TreeSet<>(Set.of(LocalDate.of(2026, 8, 9))));
        Animateur apres = animateur(LocalDate.of(1990, 1, 1));
        apres.setJoursIndisponibles(new TreeSet<>(Set.of(LocalDate.of(2026, 8, 9))));

        assertThat(CoherenceAnalyzer.onAnimateur(avant, apres, JoursEvenement.of(troisJours()))).isEmpty();
    }

    /** …but a newly added one is, and only that one is cited. */
    @Test
    void onlyTheOffDaysAddedByThisWriteAreCited() {
        Animateur avant = animateur(LocalDate.of(1990, 1, 1));
        avant.setJoursIndisponibles(new TreeSet<>(Set.of(LocalDate.of(2026, 8, 9))));
        Animateur apres = animateur(LocalDate.of(1990, 1, 1));
        apres.setJoursIndisponibles(new TreeSet<>(Set.of(LocalDate.of(2026, 8, 9), LocalDate.of(2027, 1, 5))));

        List<Avertissement> avertissements =
                CoherenceAnalyzer.onAnimateur(avant, apres, JoursEvenement.of(troisJours()));

        assertThat(types(avertissements)).containsExactly(TypeAvertissement.INDISPONIBILITE_HORS_EVENEMENT);
        assertThat(avertissements.get(0).message()).contains("2027-01-05").doesNotContain("2026-08-09");
    }

    /* -------------------------- What a message may say ----------------------- */

    /**
     * The browser keeps a log of every message it shows, in {@code localStorage},
     * across logouts. A sentence naming a minor's identity and birth date would
     * therefore outlive the screen it was shown on — see {@code docs/rgpd.md}
     * §7. The id is what stays: enough to act on in a bulk edit, and already
     * what the rest of the application shows.
     */
    @Test
    void theMinorWarningNamesTheIdAloneAndNeverTheIdentityNorTheBirthDate() {
        Animateur jeune = new Animateur("A-12", "Camille", "Durand", LocalDate.of(2012, 5, 1), false);

        String message = CoherenceAnalyzer.onAnimateur(jeune, JoursEvenement.of(troisJours()))
                .get(0).message();

        assertThat(message).contains("A-12");
        assertThat(message)
                .doesNotContain("Camille")
                .doesNotContain("Durand")
                .doesNotContain("2012-05-01");
    }

    /** Same guarantee on the message that names the day the regime changes. */
    @Test
    void theTurningEighteenWarningAlsoKeepsTheIdentityOut() {
        Animateur bascule = new Animateur("A-13", "Alix", "Martin", LocalDate.of(2008, 7, 9), false);

        String message = CoherenceAnalyzer.onAnimateur(bascule, JoursEvenement.of(troisJours()))
                .get(0).message();

        assertThat(message).contains("A-13").doesNotContain("Alix").doesNotContain("Martin");
    }

    /* ----------------------- Timeslot vs stands, rule 3 ---------------------- */

    @Test
    void aTimeslotIsSilentWhenTheEditionHasNoStandToCompareItWith() {
        assertThat(CoherenceAnalyzer.onCreneau(creneau(1L, JOUR_1, 10, 20), List.of())).isEmpty();
    }

    @Test
    void aTimeslotWithinTheOpeningSpanOfEveryStandIsSilent() {
        List<Stand> stands = new ArrayList<>(List.of(stand("A"), stand("B")));

        assertThat(surCreneau(creneau(1L, JOUR_1, 10, 20), stands)).isEmpty();
    }

    /**
     * The whole point of resolving first: the stand opens 14:00→closing by a
     * recurring rule and carries no dated window at all. Reading the dated
     * lists alone would call it shut and shout on a perfectly good timeslot.
     */
    @Test
    void aStandOpenedByARecurringRuleIsNotMistakenForAClosedOne() {
        Stand regle = stand("APREM");
        regle.setHoraires(List.of(HoraireStand.everyDay(ModeHoraire.OUVERTURE,
                new FenetreHoraire(LocalTime.of(14, 0), null))));

        assertThat(surCreneau(creneau(1L, JOUR_1, 14, 20), new ArrayList<>(List.of(regle)))).isEmpty();
    }

    /** Same rule, but the timeslot starts four hours before it opens. */
    @Test
    void aTimeslotStartingBeforeEveryStandOpensReportsTheDeadStretch() {
        Stand regle = stand("APREM");
        regle.setHoraires(List.of(HoraireStand.everyDay(ModeHoraire.OUVERTURE,
                new FenetreHoraire(LocalTime.of(14, 0), null))));

        List<Avertissement> avertissements =
                surCreneau(creneau(1L, JOUR_1, 10, 20), new ArrayList<>(List.of(regle)));

        assertThat(types(avertissements)).containsExactly(TypeAvertissement.CRENEAU_DEBORDE_OUVERTURE_STANDS);
        assertThat(avertissements.get(0).message()).contains("de 10:00 à 14:00").contains("240 min");
    }

    /**
     * A weekday rule that does not cover the timeslot's day leaves it open by
     * default — the third layer of {@code HoraireStandResolver}.
     */
    @Test
    void aWeekdayRuleThatDoesNotCoverTheDayLeavesTheStandOpen() {
        Stand weekend = stand("WEEKEND");
        HoraireStand regle = new HoraireStand(null, ModeHoraire.OUVERTURE, TypeJoursHoraire.JOURS_SEMAINE,
                List.of(new FenetreHoraire(LocalTime.of(14, 0), null)));
        regle.setJoursSemaine(Set.of(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY));
        weekend.setHoraires(List.of(regle));

        // JOUR_1 is a Wednesday: no rule covers it, so the stand is open all day.
        assertThat(surCreneau(creneau(1L, JOUR_1, 10, 20), new ArrayList<>(List.of(weekend)))).isEmpty();
    }

    @Test
    void aTimeslotNoStandIsOpenOnAtAllIsReported() {
        Stand ferme = stand("FERME");
        ferme.getIndisponibilites().add(new IndisponibiliteStand(null, JOUR_1, LocalTime.of(0, 0), null, "Montage"));

        List<Avertissement> avertissements =
                surCreneau(creneau(1L, JOUR_1, 10, 20), new ArrayList<>(List.of(ferme)));

        assertThat(types(avertissements)).containsExactly(TypeAvertissement.CRENEAU_HORS_OUVERTURE_STANDS);
        assertThat(avertissements.get(0).message()).contains("2026-07-08 10:00-20:00");
    }

    /** One stand open is enough: the timeslot will open seats there. */
    @Test
    void oneOpenStandAmongClosedOnesIsEnoughToStaySilent() {
        Stand ferme = stand("FERME");
        ferme.getIndisponibilites().add(new IndisponibiliteStand(null, JOUR_1, LocalTime.of(0, 0), null, "Montage"));
        Stand ouvert = stand("OUVERT");

        assertThat(surCreneau(creneau(1L, JOUR_1, 10, 20), new ArrayList<>(List.of(ferme, ouvert)))).isEmpty();
    }

    /**
     * A closure shared by every stand in the middle of the timeslot is a
     * schedule (the meal pause), not a mistake: only the two ends are read.
     */
    @Test
    void aMiddayClosureCommonToEveryStandIsNotReported() {
        Stand pause = stand("PAUSE");
        pause.getOuvertures().add(new OuvertureStand(null, JOUR_1, LocalTime.of(10, 0), LocalTime.of(12, 0), null));
        pause.getOuvertures().add(new OuvertureStand(null, JOUR_1, LocalTime.of(14, 0), LocalTime.of(20, 0), null));

        assertThat(surCreneau(creneau(1L, JOUR_1, 10, 20), new ArrayList<>(List.of(pause)))).isEmpty();
    }

    /** Ten minutes of overhang is the clock, not a schedule problem. */
    @Test
    void anOverhangShorterThanTheFloorIsIgnored() {
        Stand presque = stand("PRESQUE");
        presque.getOuvertures().add(new OuvertureStand(null, JOUR_1, LocalTime.of(10, 10), LocalTime.of(20, 0), null));

        assertThat(surCreneau(creneau(1L, JOUR_1, 10, 20), new ArrayList<>(List.of(presque)))).isEmpty();
    }

    /** A night timeslot reads the next day's windows; the wording must stay legible past midnight. */
    @Test
    void aNightTimeslotReportsItsOverhangInWallClockHours() {
        Stand tard = stand("TARD");
        tard.getOuvertures().add(new OuvertureStand(null, JOUR_1, LocalTime.of(20, 0), LocalTime.of(23, 0), null));

        List<Avertissement> avertissements = surCreneau(
                new Creneau(1L, 0, JOUR_1, LocalTime.of(20, 0), LocalTime.of(0, 0)),
                new ArrayList<>(List.of(tard)));

        assertThat(types(avertissements)).containsExactly(TypeAvertissement.CRENEAU_DEBORDE_OUVERTURE_STANDS);
        assertThat(avertissements.get(0).message()).contains("de 23:00 à 00:00");
    }
}
