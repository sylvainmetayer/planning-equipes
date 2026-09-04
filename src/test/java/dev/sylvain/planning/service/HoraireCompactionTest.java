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
 * {@link HoraireCompaction}: turning repeated dated windows back into the rules
 * they repeat, and refusing to do so whenever the result would not reproduce the
 * stand's own open segments.
 *
 * <p>Calendar of the reference event: 2026-07-08 is a Wednesday, so
 * 2026-07-11 and 2026-07-12 are the weekend.</p>
 */
class HoraireCompactionTest {

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

        HoraireCompaction.RapportCompactage rapport =
                HoraireCompaction.compact(List.of(bourse), amplitudes(LocalTime.of(20, 0)), true);

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
     * The {@code 23:59} workaround, on an event closing at midnight: it is
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

        HoraireCompaction.RapportCompactage rapport =
                HoraireCompaction.compact(List.of(stand), amplitudes(LocalTime.MIDNIGHT), true);

        assertThat(stand.getHoraires()).hasSize(1);
        assertThat(stand.getHoraires().get(0).getFenetres())
                .containsExactly(new FenetreHoraire(LocalTime.of(14, 0), null));
        HoraireCompaction.LigneCompactage ligne = rapport.stands().get(0);
        assertThat(ligne.compacte()).isTrue();
        assertThat(ligne.ecartMinutes()).isEqualTo(1);
        assertThat(ligne.raison()).contains("23:59");
    }

    /**
     * Two patterns covering the whole event come out as "every day, and this
     * on the weekend" rather than as two date-or-weekday lists: the largest one
     * becomes a plain {@code TOUS} rule and the other, being more specific, wins
     * on its own days. That is the layering doing the work — and it is only sound
     * because every event day is stated (see {@code baseGroup}).
     */
    @Test
    void leMotifMajoritaireDevientUneRegleTousLesJoursEtLAutreLaSurcharge() {
        Stand stand = stand("GIGAMIC");
        for (int jour = 0; jour < NOMBRE_JOURS; jour++) {
            LocalDate date = PREMIER_JOUR.plusDays(jour);
            boolean weekend = date.getDayOfWeek() == DayOfWeek.SATURDAY || date.getDayOfWeek() == DayOfWeek.SUNDAY;
            stand.getOuvertures().add(new OuvertureStand(null, date,
                    weekend ? LocalTime.of(10, 0) : LocalTime.of(14, 0), LocalTime.of(20, 0), null));
        }

        HoraireCompaction.compact(List.of(stand), amplitudes(LocalTime.of(20, 0)), true);

        assertThat(stand.getHoraires()).hasSize(2);
        HoraireStand base = horaireStartingAt(stand, LocalTime.of(14, 0));
        assertThat(base.getJours()).isEqualTo(TypeJoursHoraire.TOUS);
        HoraireStand duWeekend = horaireStartingAt(stand, LocalTime.of(10, 0));
        assertThat(duWeekend.getJours()).isEqualTo(TypeJoursHoraire.JOURS_SEMAINE);
        assertThat(duWeekend.getJoursSemaine()).containsExactly(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY);
        assertThat(duWeekend.specificite()).isGreaterThan(base.specificite());
    }

    /**
     * The mirror case: the patterns leave some event days unstated, so no rule
     * may claim "every day" — a base rule would start governing a day that was
     * deliberately left open-by-default.
     */
    @Test
    void sansCouvrirTousLesJoursAucuneRegleNeDevientTousLesJours() {
        Stand stand = stand("PARTIEL");
        for (int jour = 0; jour < 6; jour++) {
            stand.getOuvertures().add(new OuvertureStand(null, PREMIER_JOUR.plusDays(jour), LocalTime.of(10, 0),
                    LocalTime.of(12, 0), null));
        }

        HoraireCompaction.compact(List.of(stand), amplitudes(LocalTime.of(20, 0)), true);

        assertThat(stand.getHoraires()).hasSize(1);
        assertThat(stand.getHoraires().get(0).getJours()).isEqualTo(TypeJoursHoraire.PLAGE);
        assertThat(stand.getHoraires().get(0).getDateDebut()).isEqualTo(PREMIER_JOUR);
        assertThat(stand.getHoraires().get(0).getDateFin()).isEqualTo(PREMIER_JOUR.plusDays(5));
    }

    private static HoraireStand horaireStartingAt(Stand stand, LocalTime heureDebut) {
        return stand.getHoraires().stream()
                .filter(regle -> regle.getFenetres().get(0).getHeureDebut().equals(heureDebut))
                .findFirst()
                .orElseThrow();
    }

    /** A pattern seen on a single day is left dated: turning it into a rule buys nothing. */
    @Test
    void unMotifIsoleResteUneExceptionDatee() {
        Stand stand = stand("STAND-ARGENT");
        for (int jour = 0; jour < NOMBRE_JOURS; jour++) {
            stand.getIndisponibilites().add(new IndisponibiliteStand(null, PREMIER_JOUR.plusDays(jour),
                    LocalTime.of(10, 0), LocalTime.of(20, 0), null));
        }
        // One day open instead, unlike every other.
        stand.getIndisponibilites().removeIf(fermeture -> fermeture.getDate().equals(PREMIER_JOUR.plusDays(3)));
        stand.getOuvertures().add(new OuvertureStand(null, PREMIER_JOUR.plusDays(3), LocalTime.of(14, 0),
                LocalTime.of(18, 0), null));

        HoraireCompaction.compact(List.of(stand), amplitudes(LocalTime.of(20, 0)), true);

        assertThat(stand.getHoraires()).hasSize(1);
        assertThat(stand.getOuvertures()).hasSize(1);
        assertThat(stand.getOuvertures().get(0).getDate()).isEqualTo(PREMIER_JOUR.plusDays(3));
    }

    @Test
    void unStandSansMotifRepeteEstLaisseIntact() {
        Stand stand = stand("UNIQUE");
        stand.getIndisponibilites().add(new IndisponibiliteStand(null, PREMIER_JOUR, LocalTime.of(14, 0),
                LocalTime.of(16, 0), null));

        HoraireCompaction.RapportCompactage rapport =
                HoraireCompaction.compact(List.of(stand), amplitudes(LocalTime.of(20, 0)), true);

        assertThat(rapport.standsCompactes()).isZero();
        assertThat(stand.getHoraires()).isEmpty();
        assertThat(stand.getIndisponibilites()).hasSize(1);
    }

    @Test
    void unStandAyantDejaDesReglesEstIgnore() {
        Stand stand = stand("DEJA-REGLE");
        stand.setHoraires(new ArrayList<>(List.of(HoraireStand.everyDay(ModeHoraire.OUVERTURE,
                new FenetreHoraire(LocalTime.of(14, 0), null)))));
        for (int jour = 0; jour < NOMBRE_JOURS; jour++) {
            stand.getOuvertures().add(new OuvertureStand(null, PREMIER_JOUR.plusDays(jour), LocalTime.of(10, 0),
                    LocalTime.of(12, 0), null));
        }

        HoraireCompaction.RapportCompactage rapport =
                HoraireCompaction.compact(List.of(stand), amplitudes(LocalTime.of(20, 0)), true);

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

        HoraireCompaction.RapportCompactage rapport =
                HoraireCompaction.compact(List.of(stand), amplitudes(LocalTime.of(20, 0)), false);

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

        assertThat(HoraireCompaction.maxGapMinutes(stand, stand, amplitudes(LocalTime.of(20, 0)))).isZero();
    }

    @Test
    void lEcartCompteLesMinutesDeDesaccord() {
        Stand ouvertToutLeTemps = stand("A");
        Stand ferme2h = stand("B");
        ferme2h.getIndisponibilites().add(new IndisponibiliteStand(null, PREMIER_JOUR, LocalTime.of(14, 0),
                LocalTime.of(16, 0), null));

        assertThat(HoraireCompaction.maxGapMinutes(ouvertToutLeTemps, ferme2h,
                amplitudes(LocalTime.of(20, 0)))).isEqualTo(120);
    }

    /**
     * The case the measure in minutes exists to accept: a stand away all day long
     * used to be written "closed 10:00-23:59" on a day closing at midnight, which
     * left one minute open — hence a one-minute seat. Rewritten as "closed from
     * 10:00 until closing time", the stand generates no seat at all: the number of
     * segments falls from 1 to 0 while the real disagreement is that single
     * minute.
     */
    @Test
    void unPosteDUneMinuteHeriteDu2359NEmpechePasLeCompactage() {
        Stand stand = stand("ABSENT");
        for (int jour = 0; jour < NOMBRE_JOURS; jour++) {
            stand.getIndisponibilites().add(new IndisponibiliteStand(null, PREMIER_JOUR.plusDays(jour),
                    LocalTime.of(10, 0), LocalTime.of(23, 59), null));
        }
        List<Creneau> creneaux = amplitudes(LocalTime.MIDNIGHT);

        // Before: one open minute a day, at 23:59.
        assertThat(creneaux.get(0).segmentsOuvertsMinutes(stand)).containsExactly(new int[] {839, 840});

        HoraireCompaction.RapportCompactage rapport =
                HoraireCompaction.compact(List.of(stand), creneaux, true);

        assertThat(rapport.standsCompactes()).isEqualTo(1);
        assertThat(rapport.stands().get(0).ecartMinutes()).isEqualTo(1);
        assertThat(stand.getHoraires()).hasSize(1);
        assertThat(stand.getHoraires().get(0).getFenetres())
                .containsExactly(new FenetreHoraire(LocalTime.of(10, 0), null));
        // After: the stand is closed all day, hence no seat at all.
        HoraireStandResolver.apply(List.of(stand), creneaux);
        assertThat(creneaux.get(0).segmentsOuvertsMinutes(stand)).isEmpty();
    }

    private static Stand stand(String id) {
        Stand stand = new Stand(id, id, Set.of(), 1, 1, false);
        stand.setIndisponibilites(new ArrayList<>());
        stand.setOuvertures(new ArrayList<>());
        stand.setHoraires(new ArrayList<>());
        return stand;
    }
}
