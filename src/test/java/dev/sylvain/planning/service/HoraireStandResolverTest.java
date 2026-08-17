package dev.sylvain.planning.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.FenetreHoraire;
import dev.sylvain.planning.domain.HoraireStand;
import dev.sylvain.planning.domain.IndisponibiliteStand;
import dev.sylvain.planning.domain.ModeHoraire;
import dev.sylvain.planning.domain.OuvertureStand;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.domain.TypeJoursHoraire;

/**
 * {@link HoraireStandResolver}: the layering of dated exceptions over recurring
 * rules, and the fact that the expansion lands on the effective lists only —
 * never on the persisted ones.
 *
 * <p>Dates follow the reference festival: 2026-07-08 is a Wednesday,
 * 2026-07-11 a Saturday.</p>
 */
class HoraireStandResolverTest {

    private static final LocalDate MERCREDI = LocalDate.of(2026, 7, 8);
    private static final List<LocalDate> DOUZE_JOURS = java.util.stream.IntStream.range(0, 12)
            .mapToObj(MERCREDI::plusDays)
            .toList();

    /**
     * The AUTRES-BOURSE case, the one this whole mechanism exists for: 24 dated
     * windows in the fixture (10:00-12:00 plus 14:00-20:00, repeated over twelve
     * days) become a single rule carrying two windows.
     */
    @Test
    void uneRegleQuotidienneCouvreChaqueJourDuFestival() {
        Stand bourse = stand("AUTRES-BOURSE");
        bourse.setHoraires(List.of(HoraireStand.tousLesJours(ModeHoraire.OUVERTURE,
                new FenetreHoraire(LocalTime.of(10, 0), LocalTime.of(12, 0)),
                new FenetreHoraire(LocalTime.of(14, 0), null))));

        HoraireStandResolver.appliquer(bourse, DOUZE_JOURS);

        assertThat(bourse.getOuverturesEffectives()).hasSize(24);
        assertThat(bourse.getOuverturesEffectives())
                .extracting(OuvertureStand::getDate)
                .containsAll(DOUZE_JOURS);
        assertThat(bourse.getIndisponibilitesEffectives()).isEmpty();
        // The rule stays the rule: nothing was written onto the persisted lists.
        assertThat(bourse.getOuvertures()).isEmpty();
    }

    @Test
    void unStandSansRegleNEstPasTouche() {
        Stand stand = stand("SANS-REGLE");
        stand.setIndisponibilites(new java.util.ArrayList<>(List.of(
                new IndisponibiliteStand(null, MERCREDI, LocalTime.of(14, 0), LocalTime.of(16, 0), null))));

        HoraireStandResolver.appliquer(stand, DOUZE_JOURS);

        assertThat(stand.getIndisponibilitesEffectives()).isSameAs(stand.getIndisponibilites());
    }

    @Test
    void laRegleLaPlusSpecifiqueGagne() {
        Stand stand = stand("GIGAMIC");
        HoraireStand semaine = HoraireStand.tousLesJours(ModeHoraire.OUVERTURE,
                new FenetreHoraire(LocalTime.of(14, 0), null));
        HoraireStand weekend = new HoraireStand(null, ModeHoraire.OUVERTURE, TypeJoursHoraire.JOURS_SEMAINE,
                List.of(new FenetreHoraire(LocalTime.of(10, 0), null)));
        weekend.setJoursSemaine(Set.of(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY));
        stand.setHoraires(List.of(semaine, weekend));

        HoraireStandResolver.appliquer(stand, DOUZE_JOURS);

        assertThat(heureDebutLe(stand, MERCREDI)).containsExactly(LocalTime.of(14, 0));
        assertThat(heureDebutLe(stand, LocalDate.of(2026, 7, 11))).containsExactly(LocalTime.of(10, 0));
    }

    @Test
    void uneExceptionDateePrimeSurLesReglesEtLesRemplaceCeJourLa() {
        Stand stand = stand("AVEC-EXCEPTION");
        stand.setHoraires(List.of(HoraireStand.tousLesJours(ModeHoraire.OUVERTURE,
                new FenetreHoraire(LocalTime.of(14, 0), null))));
        LocalDate ferie = MERCREDI.plusDays(2);
        stand.setIndisponibilites(new java.util.ArrayList<>(List.of(
                new IndisponibiliteStand(null, ferie, LocalTime.of(10, 0), null, "Férié"))));

        HoraireStandResolver.appliquer(stand, DOUZE_JOURS);

        assertThat(stand.getOuverturesEffectives())
                .extracting(OuvertureStand::getDate)
                .doesNotContain(ferie)
                .hasSize(11);
        assertThat(stand.getIndisponibilitesEffectives()).hasSize(1);
    }

    @Test
    void unJourNonCouvertResteOuvertParDefaut() {
        Stand stand = stand("SEULEMENT-LE-14");
        HoraireStand leQuatorze = new HoraireStand(null, ModeHoraire.OUVERTURE, TypeJoursHoraire.DATES,
                List.of(new FenetreHoraire(LocalTime.of(14, 0), null)));
        leQuatorze.setDates(Set.of(LocalDate.of(2026, 7, 14)));
        stand.setHoraires(List.of(leQuatorze));

        HoraireStandResolver.appliquer(stand, DOUZE_JOURS);

        assertThat(stand.getOuverturesEffectives())
                .extracting(OuvertureStand::getDate)
                .containsExactly(LocalDate.of(2026, 7, 14));
    }

    /**
     * Two rules of equal specificity disagreeing on the mode is rejected at write
     * time; the resolver still has to be deterministic about it, and picks the
     * more restrictive reading (closed-by-default outside the openings).
     */
    @Test
    void aSpecificiteEgaleLOuvertureLEmporte() {
        Stand stand = stand("AMBIGU");
        stand.setHoraires(List.of(
                HoraireStand.tousLesJours(ModeHoraire.FERMETURE,
                        new FenetreHoraire(LocalTime.of(14, 0), LocalTime.of(16, 0))),
                HoraireStand.tousLesJours(ModeHoraire.OUVERTURE,
                        new FenetreHoraire(LocalTime.of(10, 0), LocalTime.of(12, 0)))));

        HoraireStandResolver.appliquer(stand, DOUZE_JOURS);

        assertThat(stand.getOuverturesEffectives()).hasSize(12);
        assertThat(stand.getIndisponibilitesEffectives()).isEmpty();
    }

    @Test
    void resoudreDeuxFoisDonneLeMemeResultat() {
        Stand stand = stand("IDEMPOTENT");
        stand.setHoraires(List.of(HoraireStand.tousLesJours(ModeHoraire.OUVERTURE,
                new FenetreHoraire(LocalTime.of(14, 0), null))));

        HoraireStandResolver.appliquer(stand, DOUZE_JOURS);
        int premierPassage = stand.getOuverturesEffectives().size();
        HoraireStandResolver.appliquer(stand, DOUZE_JOURS);

        assertThat(stand.getOuverturesEffectives()).hasSize(premierPassage);
    }

    /**
     * Only the days a window could actually be read on: the créneau dates, plus
     * the day after one that crosses midnight (the sole case
     * {@code Creneau#segmentsOuvertsMinutes} looks past the slot's own date).
     */
    @Test
    void lesJoursAResoudreSuiventLesCreneaux() {
        Creneau journee = new Creneau(1L, 1, MERCREDI, LocalTime.of(10, 0), LocalTime.of(20, 0));
        Creneau nuit = new Creneau(2L, 2, MERCREDI.plusDays(1), LocalTime.of(20, 0), LocalTime.of(2, 0));

        assertThat(HoraireStandResolver.datesConcernees(List.of(journee)))
                .containsExactly(MERCREDI);
        assertThat(HoraireStandResolver.datesConcernees(List.of(journee, nuit)))
                .containsExactly(MERCREDI, MERCREDI.plusDays(1), MERCREDI.plusDays(2));
    }

    private static List<LocalTime> heureDebutLe(Stand stand, LocalDate date) {
        return stand.getOuverturesEffectives().stream()
                .filter(ouverture -> ouverture.getDate().equals(date))
                .map(OuvertureStand::getHeureDebut)
                .toList();
    }

    private static Stand stand(String id) {
        return new Stand(id, id, Set.of(), 1, 1, false);
    }
}
