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

    // --- OuvertureStand: the inverse mechanic (issue #60 follow-up) --------

    @Test
    void ouvertureCouvrantToutLeCreneauLeLaisseEntierementOuvert() {
        Creneau creneau = new Creneau(1L, 1, JOUR, LocalTime.of(9, 0), LocalTime.of(14, 0));
        Stand stand = ouvrir(LocalTime.of(9, 0), LocalTime.of(14, 0));

        assertThat(creneau.segmentsOuvertsMinutes(stand)).containsExactly(new int[] {0, 300});
        assertThat(creneau.estStandOuvert(stand)).isTrue();
        assertThat(creneau.estStandFermeIntegralement(stand)).isFalse();
    }

    @Test
    void ouvertureAuMilieuNeLaisseQueCeSegmentOuvert() {
        // Inverse de fermetureAuMilieuLaisseDeuxSegmentsOuverts : une ouverture
        // ne couvrant qu'une partie du créneau ferme tout le reste par défaut.
        Creneau creneau = new Creneau(1L, 1, JOUR, LocalTime.of(9, 0), LocalTime.of(14, 0));
        Stand stand = ouvrir(LocalTime.of(11, 0), LocalTime.of(13, 0));

        assertThat(creneau.segmentsOuvertsMinutes(stand)).containsExactly(new int[] {120, 240});
        assertThat(creneau.estStandOuvert(stand)).isTrue();
        assertThat(creneau.estStandFermeIntegralement(stand)).isFalse();
    }

    @Test
    void ouvertureSansChevauchementFermeLeCreneauIntegralement() {
        Creneau creneau = new Creneau(1L, 1, JOUR, LocalTime.of(9, 0), LocalTime.of(14, 0));
        // Le stand n'ouvre que le soir : aucun chevauchement avec ce créneau.
        Stand stand = ouvrir(LocalTime.of(20, 0), LocalTime.of(23, 0));

        assertThat(creneau.segmentsOuvertsMinutes(stand)).isEmpty();
        assertThat(creneau.estStandOuvert(stand)).isFalse();
        assertThat(creneau.estStandFermeIntegralement(stand)).isTrue();
    }

    @Test
    void deuxOuverturesSeChevauchantSontFusionnees() {
        Creneau creneau = new Creneau(1L, 1, JOUR, LocalTime.of(9, 0), LocalTime.of(18, 0));
        Stand stand = new Stand("S", "S", java.util.Set.of(), 1, 1, false);
        stand.setOuvertures(List.of(
                new OuvertureStand(null, JOUR, LocalTime.of(10, 0), LocalTime.of(13, 0), null),
                new OuvertureStand(null, JOUR, LocalTime.of(12, 0), LocalTime.of(15, 0), null)));

        assertThat(creneau.segmentsOuvertsMinutes(stand)).containsExactly(new int[] {60, 360});
    }

    @Test
    void ouvertureDebordantLeCreneauEstClampee() {
        Creneau creneau = new Creneau(1L, 1, JOUR, LocalTime.of(9, 0), LocalTime.of(14, 0));
        Stand stand = ouvrir(LocalTime.of(8, 0), LocalTime.of(15, 0));

        assertThat(creneau.segmentsOuvertsMinutes(stand)).containsExactly(new int[] {0, 300});
    }

    @Test
    void ouvertureSurUneAutreDateEstIgnoree() {
        Creneau creneau = new Creneau(1L, 1, JOUR, LocalTime.of(9, 0), LocalTime.of(14, 0));
        Stand stand = new Stand("S", "S", java.util.Set.of(), 1, 1, false);
        stand.setOuvertures(List.of(
                new OuvertureStand(null, JOUR.plusDays(5), LocalTime.of(9, 0), LocalTime.of(14, 0), null)));

        // Pas d'ouverture ce jour-là (et pas de fermeture non plus) : ouvert par défaut.
        assertThat(creneau.segmentsOuvertsMinutes(stand)).containsExactly(new int[] {0, 300});
    }

    @Test
    void ouvertureApresMinuitDansUnCreneauQuiTraverseMinuit() {
        Creneau creneau = new Creneau(1L, 1, JOUR, LocalTime.of(20, 0), LocalTime.of(2, 0));
        Stand stand = new Stand("S", "S", java.util.Set.of(), 1, 1, false);
        stand.setOuvertures(List.of(
                new OuvertureStand(null, JOUR.plusDays(1), LocalTime.of(0, 30), LocalTime.of(1, 30), null)));

        assertThat(creneau.segmentsOuvertsMinutes(stand)).containsExactly(new int[] {270, 330});
    }

    @Test
    void ouvertureInvalideEstIgnoreeDefensivement() {
        Creneau creneau = new Creneau(1L, 1, JOUR, LocalTime.of(9, 0), LocalTime.of(14, 0));
        Stand stand = new Stand("S", "S", java.util.Set.of(), 1, 1, false);
        // heureFin avant heureDebut : ne doit jamais ouvrir le créneau — un
        // stand sans ouverture valide ce jour-là reste ouvert par défaut.
        stand.setOuvertures(List.of(
                new OuvertureStand(null, JOUR, LocalTime.of(13, 0), LocalTime.of(11, 0), null)));

        assertThat(creneau.segmentsOuvertsMinutes(stand)).containsExactly(new int[] {0, 300});
    }

    /**
     * A stand can be in "opening" mode one day and "closure" mode another
     * without the two interfering — the per-day branch only looks at
     * {@link OuvertureStand} entries matching that day's date.
     */
    @Test
    void ouvertureUnJourEtFermetureUnAutreJourNeSeMelangentPas() {
        Creneau creneauOuvert = new Creneau(1L, 1, JOUR, LocalTime.of(9, 0), LocalTime.of(20, 0));
        Creneau creneauFerme = new Creneau(2L, 2, JOUR.plusDays(1), LocalTime.of(9, 0), LocalTime.of(20, 0));
        Stand stand = new Stand("S", "S", java.util.Set.of(), 1, 1, false);
        stand.setOuvertures(List.of(new OuvertureStand(null, JOUR, LocalTime.of(18, 0), LocalTime.of(20, 0), null)));
        stand.setIndisponibilites(List.of(
                new IndisponibiliteStand(null, JOUR.plusDays(1), LocalTime.of(9, 0), LocalTime.of(11, 0), null)));

        assertThat(creneauOuvert.segmentsOuvertsMinutes(stand)).containsExactly(new int[] {540, 660});
        assertThat(creneauFerme.segmentsOuvertsMinutes(stand)).containsExactly(new int[] {120, 660});
    }

    private static Stand fermer(LocalTime debut, LocalTime fin) {
        Stand stand = new Stand("S", "S", java.util.Set.of(), 1, 1, false);
        stand.setIndisponibilites(List.of(new IndisponibiliteStand(null, JOUR, debut, fin, null)));
        return stand;
    }

    private static Stand ouvrir(LocalTime debut, LocalTime fin) {
        Stand stand = new Stand("S", "S", java.util.Set.of(), 1, 1, false);
        stand.setOuvertures(List.of(new OuvertureStand(null, JOUR, debut, fin, null)));
        return stand;
    }
}
