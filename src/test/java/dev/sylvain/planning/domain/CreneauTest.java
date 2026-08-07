package dev.sylvain.planning.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * {@link Creneau#segmentsOuvertsMinutes(Stand)} is the core mechanism behind
 * issue #60 (stand-level, sub-créneau closures): it must reduce cleanly to
 * the pre-#60 all-or-nothing behaviour when a stand has no closure or is
 * closed for the whole slot, and correctly split a slot around a closure that
 * sits only partly inside it — including one that leaves two open remainders.
 */
class CreneauTest {

    private static final LocalDate JOUR = LocalDate.of(2026, 8, 14);

    @Test
    void aucuneIndisponibiliteLaisseLeCreneauEntierementOuvert() {
        Creneau creneau = new Creneau(1L, 1, JOUR, LocalTime.of(9, 0), LocalTime.of(14, 0));
        Stand stand = new Stand("S", "S", java.util.Set.of(), 1, 1, false);

        assertThat(creneau.segmentsOuvertsMinutes(stand)).containsExactly(new int[] {0, 300});
        assertThat(creneau.estStandOuvert(stand)).isTrue();
        assertThat(creneau.estStandFermeIntegralement(stand)).isFalse();
    }

    @Test
    void indisponibiliteCouvrantToutLeCreneauLeFermeIntegralement() {
        Creneau creneau = new Creneau(1L, 1, JOUR, LocalTime.of(9, 0), LocalTime.of(14, 0));
        Stand stand = fermer(LocalTime.of(9, 0), LocalTime.of(14, 0));

        assertThat(creneau.segmentsOuvertsMinutes(stand)).isEmpty();
        assertThat(creneau.estStandOuvert(stand)).isFalse();
        assertThat(creneau.estStandFermeIntegralement(stand)).isTrue();
    }

    @Test
    void fermetureAuMilieuLaisseDeuxSegmentsOuverts() {
        Creneau creneau = new Creneau(1L, 1, JOUR, LocalTime.of(9, 0), LocalTime.of(14, 0));
        Stand stand = fermer(LocalTime.of(11, 0), LocalTime.of(13, 0));

        assertThat(creneau.segmentsOuvertsMinutes(stand))
                .containsExactly(new int[] {0, 120}, new int[] {240, 300});
        assertThat(creneau.estStandOuvert(stand)).isTrue();
        assertThat(creneau.estStandFermeIntegralement(stand)).isFalse();
    }

    @Test
    void fermetureEnDebutDeCreneauLaisseUnSeulSegmentOuvert() {
        Creneau creneau = new Creneau(1L, 1, JOUR, LocalTime.of(9, 0), LocalTime.of(14, 0));
        Stand stand = fermer(LocalTime.of(9, 0), LocalTime.of(11, 0));

        assertThat(creneau.segmentsOuvertsMinutes(stand)).containsExactly(new int[] {120, 300});
    }

    @Test
    void fermetureDebordantLeCreneauEstClampee() {
        Creneau creneau = new Creneau(1L, 1, JOUR, LocalTime.of(9, 0), LocalTime.of(14, 0));
        // La fermeture commence avant le créneau et finit après : elle doit
        // quand même le fermer intégralement, sans erreur ni décalage négatif.
        Stand stand = fermer(LocalTime.of(8, 0), LocalTime.of(15, 0));

        assertThat(creneau.segmentsOuvertsMinutes(stand)).isEmpty();
    }

    @Test
    void indisponibiliteSurUneAutreDateEstIgnoree() {
        Creneau creneau = new Creneau(1L, 1, JOUR, LocalTime.of(9, 0), LocalTime.of(14, 0));
        Stand stand = new Stand("S", "S", java.util.Set.of(), 1, 1, false);
        stand.setIndisponibilites(List.of(
                new IndisponibiliteStand(null, JOUR.plusDays(5), LocalTime.of(9, 0), LocalTime.of(14, 0), null)));

        assertThat(creneau.segmentsOuvertsMinutes(stand)).containsExactly(new int[] {0, 300});
    }

    /**
     * A créneau crossing midnight (20:00 → 02:00) closed 00:30-01:30 the next
     * calendar day: the closure's {@code date} is the day after the créneau's
     * own {@code date}, mirroring how the créneau itself is understood to
     * cross into the next day once {@code heureFin <= heureDebut}.
     */
    @Test
    void fermetureApresMinuitDansUnCreneauQuiTraverseMinuit() {
        Creneau creneau = new Creneau(1L, 1, JOUR, LocalTime.of(20, 0), LocalTime.of(2, 0));
        Stand stand = new Stand("S", "S", java.util.Set.of(), 1, 1, false);
        stand.setIndisponibilites(List.of(
                new IndisponibiliteStand(null, JOUR.plusDays(1), LocalTime.of(0, 30), LocalTime.of(1, 30), null)));

        // 20:00 -> 00:30 is 270 min, 01:30 -> 02:00 is 30 min more, closure spans [270, 330).
        assertThat(creneau.segmentsOuvertsMinutes(stand)).containsExactly(new int[] {0, 270}, new int[] {330, 360});
    }

    @Test
    void fermetureInvalideEstIgnoreeDefensivement() {
        Creneau creneau = new Creneau(1L, 1, JOUR, LocalTime.of(9, 0), LocalTime.of(14, 0));
        Stand stand = new Stand("S", "S", java.util.Set.of(), 1, 1, false);
        // heureFin avant heureDebut : ne doit jamais fermer le créneau.
        stand.setIndisponibilites(List.of(
                new IndisponibiliteStand(null, JOUR, LocalTime.of(13, 0), LocalTime.of(11, 0), null)));

        assertThat(creneau.segmentsOuvertsMinutes(stand)).containsExactly(new int[] {0, 300});
    }

    private static Stand fermer(LocalTime debut, LocalTime fin) {
        Stand stand = new Stand("S", "S", java.util.Set.of(), 1, 1, false);
        stand.setIndisponibilites(List.of(new IndisponibiliteStand(null, JOUR, debut, fin, null)));
        return stand;
    }
}
