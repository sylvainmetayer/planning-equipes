package dev.sylvain.planning.service.referentiel;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.FenetreHoraire;
import dev.sylvain.planning.domain.HoraireStand;
import dev.sylvain.planning.domain.IndisponibiliteStand;
import dev.sylvain.planning.domain.ModeHoraire;
import dev.sylvain.planning.domain.OuvertureStand;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.domain.TypeJoursHoraire;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** What pruning takes away, and what it never touches. */
class HoraireElagageTest {

    private static final LocalDate PREMIER_JOUR = LocalDate.of(2026, 7, 8);
    private static final int NOMBRE_JOURS = 4;

    private static List<Creneau> creneaux() {
        List<Creneau> creneaux = new ArrayList<>();
        long id = 1;
        for (int jour = 0; jour < NOMBRE_JOURS; jour++) {
            LocalDate date = PREMIER_JOUR.plusDays(jour);
            creneaux.add(new Creneau(id++, jour + 1, date, LocalTime.of(10, 0), LocalTime.of(12, 0)));
            creneaux.add(new Creneau(id++, jour + 1, date, LocalTime.of(14, 0), LocalTime.of(20, 0)));
        }
        return creneaux;
    }

    private static Stand stand() {
        Stand stand = new Stand();
        stand.setId("STAND");
        stand.setNom("Stand");
        stand.setEffectifMin(2);
        stand.setEffectifMax(2);
        return stand;
    }

    private static HoraireStand ouvertureSurDates(LocalTime debut, LocalTime fin, LocalDate... dates) {
        HoraireStand regle = new HoraireStand(
                null, ModeHoraire.OUVERTURE, TypeJoursHoraire.DATES, List.of(new FenetreHoraire(debut, fin)));
        regle.setDates(Set.of(dates));
        return regle;
    }

    /**
     * The shape a real edition carried sixty-five times: an opening on the days
     * the stand works, and a closure from an hour early enough to cover every
     * créneau, every day, whose only job was to shut the rest.
     */
    @Test
    void laFermetureDAppointQuiNeFaisaitQueFermerLeResteEstRetiree() {
        Stand stand = stand();
        stand.setHoraires(new ArrayList<>(List.of(
                ouvertureSurDates(LocalTime.of(14, 0), LocalTime.of(20, 0), PREMIER_JOUR, PREMIER_JOUR.plusDays(1)),
                HoraireStand.everyDay(ModeHoraire.FERMETURE, new FenetreHoraire(LocalTime.of(9, 0), null)))));

        HoraireElagage.LigneElagage ligne = HoraireElagage.elaguer(stand, creneaux());

        assertThat(ligne.elague()).isTrue();
        assertThat(ligne.reglesApres()).isEqualTo(1);
        assertThat(stand.getHoraires())
                .singleElement()
                .satisfies(regle -> assertThat(regle.getMode()).isEqualTo(ModeHoraire.OUVERTURE));
    }

    /** The pruning is judged on the openings, never on the shape of the rule. */
    @Test
    void uneRegleQuiPorteEncoreQuelqueChoseEstGardee() {
        Stand stand = stand();
        stand.setHoraires(new ArrayList<>(List.of(
                HoraireStand.everyDay(
                        ModeHoraire.OUVERTURE, new FenetreHoraire(LocalTime.of(10, 0), LocalTime.of(20, 0))),
                ouvertureSurDates(LocalTime.of(14, 0), LocalTime.of(20, 0), PREMIER_JOUR))));

        HoraireElagage.LigneElagage ligne = HoraireElagage.elaguer(stand, creneaux());

        // The dated rule is more specific, so it decides the first day alone:
        // dropping either one moves an opening.
        assertThat(ligne.elague()).isFalse();
        assertThat(stand.getHoraires()).hasSize(2);
    }

    /**
     * An opening is never pruned, however little it does against today's
     * créneaux: "open 08:00-09:00" on a grid that starts at 10:00 is a
     * statement waiting for a créneau, not dead weight — and taking the last
     * one away would put back the very trap this change removes.
     */
    @Test
    void uneOuvertureNEstJamaisRetireeMemeQuandElleNOuvreRien() {
        Stand stand = stand();
        stand.setHoraires(new ArrayList<>(List.of(
                HoraireStand.everyDay(
                        ModeHoraire.OUVERTURE, new FenetreHoraire(LocalTime.of(10, 0), LocalTime.of(12, 0))),
                ouvertureSurDates(LocalTime.of(8, 0), LocalTime.of(9, 0), PREMIER_JOUR.plusDays(3)))));

        HoraireElagage.LigneElagage ligne = HoraireElagage.elaguer(stand, creneaux());

        assertThat(ligne.elague()).isFalse();
        assertThat(stand.getHoraires()).hasSize(2);
    }

    /**
     * A stand whose rules only close is describing exceptions to being open,
     * and every one of them is load-bearing the moment a créneau moves into
     * it: pruning does not run there at all. This closure shuts nothing today
     * — there is no créneau between noon and two — and it stays.
     */
    @Test
    void unStandQuiNeDeclareQueDesFermeturesGardeSesRegles() {
        Stand stand = stand();
        stand.setHoraires(new ArrayList<>(List.of(HoraireStand.everyDay(
                ModeHoraire.FERMETURE, new FenetreHoraire(LocalTime.of(12, 0), LocalTime.of(14, 0))))));

        HoraireElagage.LigneElagage ligne = HoraireElagage.elaguer(stand, creneaux());

        assertThat(ligne.elague()).isFalse();
        assertThat(stand.getHoraires()).hasSize(1);
    }

    /**
     * The grid's own marker for an empty column — shut, all day — on a day the
     * opening rules leave out anyway. Said twice, kept once.
     */
    @Test
    void laFermetureDUneJourneeEntiereQueLesReglesImpliquentDejaEstRetiree() {
        Stand stand = stand();
        stand.setHoraires(
                new ArrayList<>(List.of(ouvertureSurDates(LocalTime.of(14, 0), LocalTime.of(20, 0), PREMIER_JOUR))));
        stand.setIndisponibilites(new ArrayList<>(List.of(
                new IndisponibiliteStand(null, PREMIER_JOUR.plusDays(1), LocalTime.MIDNIGHT, null, null),
                new IndisponibiliteStand(null, PREMIER_JOUR.plusDays(2), LocalTime.MIDNIGHT, null, null))));

        HoraireElagage.LigneElagage ligne = HoraireElagage.elaguer(stand, creneaux());

        assertThat(ligne.fermeturesRetirees()).isEqualTo(2);
        assertThat(stand.getIndisponibilites()).isEmpty();
    }

    /**
     * Caught end to end: a stand open « 09:00-12:00 every day » that the grid
     * shuts on a nocturne, whose slots start at 14:00. Dropping that day's
     * closure moves no open segment — the morning window meets no créneau
     * there — but it declares an opening nobody wrote, and the screen then
     * reports a window with no effect. The openings are compared too, for
     * exactly this.
     */
    @Test
    void uneFermetureQuiSeuleEmpecheUneOuvertureHorsGrilleEstGardee() {
        List<Creneau> nocturne = new ArrayList<>(creneaux());
        LocalDate soir = PREMIER_JOUR.plusDays(NOMBRE_JOURS);
        nocturne.add(new Creneau(99L, 5, soir, LocalTime.of(14, 0), LocalTime.of(19, 0)));
        Stand stand = stand();
        stand.setHoraires(new ArrayList<>(List.of(HoraireStand.everyDay(
                ModeHoraire.OUVERTURE, new FenetreHoraire(LocalTime.of(10, 0), LocalTime.of(12, 0))))));
        stand.setIndisponibilites(
                new ArrayList<>(List.of(new IndisponibiliteStand(null, soir, LocalTime.MIDNIGHT, null, null))));

        HoraireElagage.LigneElagage ligne = HoraireElagage.elaguer(stand, nocturne);

        assertThat(ligne.elague()).isFalse();
        assertThat(stand.getIndisponibilites()).hasSize(1);
    }

    /** A closure that carves out part of a day is not a marker: it stays. */
    @Test
    void uneFermetureDeQuelquesHeuresNEstPasRetiree() {
        Stand stand = stand();
        stand.setHoraires(new ArrayList<>(List.of(HoraireStand.everyDay(
                ModeHoraire.FERMETURE, new FenetreHoraire(LocalTime.of(10, 0), LocalTime.of(12, 0))))));
        stand.setIndisponibilites(new ArrayList<>(
                List.of(new IndisponibiliteStand(null, PREMIER_JOUR, LocalTime.of(15, 0), LocalTime.of(16, 0), null))));

        HoraireElagage.LigneElagage ligne = HoraireElagage.elaguer(stand, creneaux());

        assertThat(ligne.elague()).isFalse();
        assertThat(stand.getIndisponibilites()).hasSize(1);
    }

    /** Nothing to resolve against, nothing to prove: the stand is left alone. */
    @Test
    void sansCreneauRienNEstRetire() {
        Stand stand = stand();
        stand.setHoraires(new ArrayList<>(List.of(
                ouvertureSurDates(LocalTime.of(14, 0), LocalTime.of(20, 0), PREMIER_JOUR),
                HoraireStand.everyDay(ModeHoraire.FERMETURE, new FenetreHoraire(LocalTime.of(9, 0), null)))));

        HoraireElagage.LigneElagage ligne = HoraireElagage.elaguer(stand, List.of());

        assertThat(ligne.elague()).isFalse();
        assertThat(stand.getHoraires()).hasSize(2);
    }

    /** An opening rule that dated windows shadow on every day is still an opening: it stays. */
    @Test
    void uneRegleDOuvertureMasqueeParDesOuverturesDateesResteEnPlace() {
        Stand stand = stand();
        stand.setHoraires(new ArrayList<>(List.of(HoraireStand.everyDay(
                ModeHoraire.OUVERTURE, new FenetreHoraire(LocalTime.of(14, 0), LocalTime.of(20, 0))))));
        List<OuvertureStand> datees = new ArrayList<>();
        for (int jour = 0; jour < NOMBRE_JOURS; jour++) {
            datees.add(new OuvertureStand(
                    null, PREMIER_JOUR.plusDays(jour), LocalTime.of(14, 0), LocalTime.of(20, 0), null, null));
        }
        stand.setOuvertures(datees);

        HoraireElagage.LigneElagage ligne = HoraireElagage.elaguer(stand, creneaux());

        assertThat(ligne.reglesApres()).isEqualTo(1);
        assertThat(stand.getOuvertures()).hasSize(NOMBRE_JOURS);
    }
}
