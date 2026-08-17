package dev.sylvain.planning.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
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
 * {@link CompactageHoraires}: turning repeated dated windows back into the rules
 * they repeat, and refusing to do so whenever the result would not reproduce the
 * stand's own open segments.
 *
 * <p>Calendar of the reference festival: 2026-07-08 is a Wednesday, so
 * 2026-07-11 and 2026-07-12 are the weekend.</p>
 */
class CompactageHorairesTest {

    private static final LocalDate PREMIER_JOUR = LocalDate.of(2026, 7, 8);
    private static final int NOMBRE_JOURS = 12;

    /** One 10:00→20:00 amplitude per day, the shape the découpage fixture uses. */
    private static List<Creneau> amplitudes(LocalTime heureFin) {
        List<Creneau> creneaux = new ArrayList<>();
        for (int jour = 0; jour < NOMBRE_JOURS; jour++) {
            creneaux.add(new Creneau((long) jour, jour + 1, PREMIER_JOUR.plusDays(jour), LocalTime.of(10, 0),
                    heureFin));
        }
        return creneaux;
    }

    /**
     * The AUTRES-BOURSE case: 24 dated openings — 10:00-12:00 plus 14:00-20:00,
     * twelve days running — collapse into one "every day" rule with two windows.
     */
    @Test
    void vingtQuatreFenetresRepeteesDeviennentUneRegle() {
        Stand bourse = stand("AUTRES-BOURSE");
        for (int jour = 0; jour < NOMBRE_JOURS; jour++) {
            LocalDate date = PREMIER_JOUR.plusDays(jour);
            bourse.getOuvertures().add(new OuvertureStand(null, date, LocalTime.of(10, 0), LocalTime.of(12, 0), null));
            bourse.getOuvertures().add(new OuvertureStand(null, date, LocalTime.of(14, 0), LocalTime.of(20, 0), null));
        }

        CompactageHoraires.RapportCompactage rapport =
                CompactageHoraires.compacter(List.of(bourse), amplitudes(LocalTime.of(20, 0)), true);

        assertThat(rapport.standsCompactes()).isEqualTo(1);
        assertThat(rapport.fenetresAvant()).isEqualTo(24);
        assertThat(bourse.getHoraires()).hasSize(1);
        HoraireStand regle = bourse.getHoraires().get(0);
        assertThat(regle.getMode()).isEqualTo(ModeHoraire.OUVERTURE);
        assertThat(regle.getJours()).isEqualTo(TypeJoursHoraire.TOUS);
        assertThat(regle.getFenetres()).containsExactly(
                new FenetreHoraire(LocalTime.of(10, 0), LocalTime.of(12, 0)),
                // 20:00 is the day's closing time, so the end becomes open-ended.
                new FenetreHoraire(LocalTime.of(14, 0), null));
        assertThat(bourse.getOuvertures()).isEmpty();
    }

    /**
     * The {@code 23:59} workaround, on a festival closing at midnight: it is
     * recognised as the closing time and rewritten as such, and the one minute
     * that recovers is reported rather than hidden.
     */
    @Test
    void leContournement2359DevientLaFermetureReelleEtEstSignale() {
        Stand stand = stand("SOIR");
        for (int jour = 0; jour < NOMBRE_JOURS; jour++) {
            stand.getOuvertures().add(new OuvertureStand(null, PREMIER_JOUR.plusDays(jour), LocalTime.of(14, 0),
                    LocalTime.of(23, 59), null));
        }

        CompactageHoraires.RapportCompactage rapport =
                CompactageHoraires.compacter(List.of(stand), amplitudes(LocalTime.MIDNIGHT), true);

        assertThat(stand.getHoraires()).hasSize(1);
        assertThat(stand.getHoraires().get(0).getFenetres())
                .containsExactly(new FenetreHoraire(LocalTime.of(14, 0), null));
        CompactageHoraires.LigneCompactage ligne = rapport.stands().get(0);
        assertThat(ligne.compacte()).isTrue();
        assertThat(ligne.ecartMinutes()).isEqualTo(1);
        assertThat(ligne.raison()).contains("23:59");
    }

    @Test
    void deuxMotifsDeviennentUneRegleParJourDeSemaine() {
        Stand stand = stand("GIGAMIC");
        for (int jour = 0; jour < NOMBRE_JOURS; jour++) {
            LocalDate date = PREMIER_JOUR.plusDays(jour);
            boolean weekend = date.getDayOfWeek() == DayOfWeek.SATURDAY || date.getDayOfWeek() == DayOfWeek.SUNDAY;
            stand.getOuvertures().add(new OuvertureStand(null, date,
                    weekend ? LocalTime.of(10, 0) : LocalTime.of(14, 0), LocalTime.of(20, 0), null));
        }

        CompactageHoraires.compacter(List.of(stand), amplitudes(LocalTime.of(20, 0)), true);

        assertThat(stand.getHoraires()).hasSize(2);
        assertThat(stand.getHoraires()).allSatisfy(regle ->
                assertThat(regle.getJours()).isEqualTo(TypeJoursHoraire.JOURS_SEMAINE));
        HoraireStand duWeekend = stand.getHoraires().stream()
                .filter(regle -> regle.getFenetres().get(0).getHeureDebut().equals(LocalTime.of(10, 0)))
                .findFirst()
                .orElseThrow();
        assertThat(duWeekend.getJoursSemaine()).containsExactly(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY);
    }

    /** A pattern seen on a single day is left dated: turning it into a rule buys nothing. */
    @Test
    void unMotifIsoleResteUneExceptionDatee() {
        Stand stand = stand("SILVER-FEST");
        for (int jour = 0; jour < NOMBRE_JOURS; jour++) {
            stand.getIndisponibilites().add(new IndisponibiliteStand(null, PREMIER_JOUR.plusDays(jour),
                    LocalTime.of(10, 0), LocalTime.of(20, 0), null));
        }
        // One day open instead, unlike every other.
        stand.getIndisponibilites().removeIf(fermeture -> fermeture.getDate().equals(PREMIER_JOUR.plusDays(3)));
        stand.getOuvertures().add(new OuvertureStand(null, PREMIER_JOUR.plusDays(3), LocalTime.of(14, 0),
                LocalTime.of(18, 0), null));

        CompactageHoraires.compacter(List.of(stand), amplitudes(LocalTime.of(20, 0)), true);

        assertThat(stand.getHoraires()).hasSize(1);
        assertThat(stand.getOuvertures()).hasSize(1);
        assertThat(stand.getOuvertures().get(0).getDate()).isEqualTo(PREMIER_JOUR.plusDays(3));
    }

    @Test
    void unStandSansMotifRepeteEstLaisseIntact() {
        Stand stand = stand("UNIQUE");
        stand.getIndisponibilites().add(new IndisponibiliteStand(null, PREMIER_JOUR, LocalTime.of(14, 0),
                LocalTime.of(16, 0), null));

        CompactageHoraires.RapportCompactage rapport =
                CompactageHoraires.compacter(List.of(stand), amplitudes(LocalTime.of(20, 0)), true);

        assertThat(rapport.standsCompactes()).isZero();
        assertThat(stand.getHoraires()).isEmpty();
        assertThat(stand.getIndisponibilites()).hasSize(1);
    }

    @Test
    void unStandAyantDejaDesReglesEstIgnore() {
        Stand stand = stand("DEJA-REGLE");
        stand.setHoraires(new ArrayList<>(List.of(HoraireStand.tousLesJours(ModeHoraire.OUVERTURE,
                new FenetreHoraire(LocalTime.of(14, 0), null)))));
        for (int jour = 0; jour < NOMBRE_JOURS; jour++) {
            stand.getOuvertures().add(new OuvertureStand(null, PREMIER_JOUR.plusDays(jour), LocalTime.of(10, 0),
                    LocalTime.of(12, 0), null));
        }

        CompactageHoraires.RapportCompactage rapport =
                CompactageHoraires.compacter(List.of(stand), amplitudes(LocalTime.of(20, 0)), true);

        assertThat(rapport.standsCompactes()).isZero();
        assertThat(rapport.stands().get(0).raison()).contains("déjà des horaires");
        assertThat(stand.getOuvertures()).hasSize(NOMBRE_JOURS);
    }

    @Test
    void unDryRunNeModifieRien() {
        Stand stand = stand("DRY-RUN");
        for (int jour = 0; jour < NOMBRE_JOURS; jour++) {
            stand.getOuvertures().add(new OuvertureStand(null, PREMIER_JOUR.plusDays(jour), LocalTime.of(14, 0),
                    LocalTime.of(20, 0), null));
        }

        CompactageHoraires.RapportCompactage rapport =
                CompactageHoraires.compacter(List.of(stand), amplitudes(LocalTime.of(20, 0)), false);

        // The report says what would happen; only its `applique` flag differs, and
        // it is the service that decides whether to save the mutated stands.
        assertThat(rapport.applique()).isFalse();
        assertThat(rapport.standsCompactes()).isEqualTo(1);
    }

    /**
     * The safety net itself: comparing the open segments a stand produces, not the
     * rules. Windows the compaction never touches must come out identical.
     */
    @Test
    void lEcartEstNulEntreUnStandEtLuiMeme() {
        Stand stand = stand("MEME");
        stand.getOuvertures().add(new OuvertureStand(null, PREMIER_JOUR, LocalTime.of(14, 0), LocalTime.of(18, 0),
                null));

        assertThat(CompactageHoraires.ecartMaximalMinutes(stand, stand, amplitudes(LocalTime.of(20, 0)))).isZero();
    }

    @Test
    void lEcartEstMaximalQuandLeNombreDeSegmentsDiffere() {
        Stand ouvertToutLeTemps = stand("A");
        Stand coupeEnDeux = stand("B");
        coupeEnDeux.getIndisponibilites().add(new IndisponibiliteStand(null, PREMIER_JOUR, LocalTime.of(14, 0),
                LocalTime.of(16, 0), null));

        assertThat(CompactageHoraires.ecartMaximalMinutes(ouvertToutLeTemps, coupeEnDeux,
                amplitudes(LocalTime.of(20, 0)))).isEqualTo(Integer.MAX_VALUE);
    }

    private static Stand stand(String id) {
        Stand stand = new Stand(id, id, Set.of(), 1, 1, false);
        stand.setIndisponibilites(new ArrayList<>());
        stand.setOuvertures(new ArrayList<>());
        stand.setHoraires(new ArrayList<>());
        return stand;
    }
}
