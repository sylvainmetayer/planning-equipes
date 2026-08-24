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
        assertThat(creneau.isStandOpen(stand)).isTrue();
        assertThat(creneau.isStandFullyClosed(stand)).isFalse();
    }

    @Test
    void indisponibiliteCouvrantToutLeCreneauLeFermeIntegralement() {
        Creneau creneau = new Creneau(1L, 1, JOUR, LocalTime.of(9, 0), LocalTime.of(14, 0));
        Stand stand = fermer(LocalTime.of(9, 0), LocalTime.of(14, 0));

        assertThat(creneau.segmentsOuvertsMinutes(stand)).isEmpty();
        assertThat(creneau.isStandOpen(stand)).isFalse();
        assertThat(creneau.isStandFullyClosed(stand)).isTrue();
    }

    @Test
    void fermetureAuMilieuLaisseDeuxSegmentsOuverts() {
        Creneau creneau = new Creneau(1L, 1, JOUR, LocalTime.of(9, 0), LocalTime.of(14, 0));
        Stand stand = fermer(LocalTime.of(11, 0), LocalTime.of(13, 0));

        assertThat(creneau.segmentsOuvertsMinutes(stand))
                .containsExactly(new int[] {0, 120}, new int[] {240, 300});
        assertThat(creneau.isStandOpen(stand)).isTrue();
        assertThat(creneau.isStandFullyClosed(stand)).isFalse();
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
        // The closing starts before the timeslot and ends after it: it must
        // still close it entirely, with no error and no negative offset.
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
        // heureFin before heureDebut: must never close the timeslot.
        stand.setIndisponibilites(List.of(
                new IndisponibiliteStand(null, JOUR, LocalTime.of(13, 0), LocalTime.of(11, 0), null)));

        assertThat(creneau.segmentsOuvertsMinutes(stand)).containsExactly(new int[] {0, 300});
    }

    // --- OuvertureStand: the inverse mechanic (issue #60 follow-up) --------

    @Test
    void ouvertureCouvrantToutLeCreneauLeLaisseEntierementOuvert() {
        Creneau creneau = new Creneau(1L, 1, JOUR, LocalTime.of(9, 0), LocalTime.of(14, 0));
        Stand stand = open(LocalTime.of(9, 0), LocalTime.of(14, 0));

        assertThat(creneau.segmentsOuvertsMinutes(stand)).containsExactly(new int[] {0, 300});
        assertThat(creneau.isStandOpen(stand)).isTrue();
        assertThat(creneau.isStandFullyClosed(stand)).isFalse();
    }

    @Test
    void ouvertureAuMilieuNeLaisseQueCeSegmentOuvert() {
        // The mirror of fermetureAuMilieuLaisseDeuxSegmentsOuverts: an opening
        // covering only part of the timeslot closes all the rest by default.
        Creneau creneau = new Creneau(1L, 1, JOUR, LocalTime.of(9, 0), LocalTime.of(14, 0));
        Stand stand = open(LocalTime.of(11, 0), LocalTime.of(13, 0));

        assertThat(creneau.segmentsOuvertsMinutes(stand)).containsExactly(new int[] {120, 240});
        assertThat(creneau.isStandOpen(stand)).isTrue();
        assertThat(creneau.isStandFullyClosed(stand)).isFalse();
    }

    @Test
    void ouvertureSansChevauchementFermeLeCreneauIntegralement() {
        Creneau creneau = new Creneau(1L, 1, JOUR, LocalTime.of(9, 0), LocalTime.of(14, 0));
        // The stand only opens in the evening: no overlap with this timeslot.
        Stand stand = open(LocalTime.of(20, 0), LocalTime.of(23, 0));

        assertThat(creneau.segmentsOuvertsMinutes(stand)).isEmpty();
        assertThat(creneau.isStandOpen(stand)).isFalse();
        assertThat(creneau.isStandFullyClosed(stand)).isTrue();
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
        Stand stand = open(LocalTime.of(8, 0), LocalTime.of(15, 0));

        assertThat(creneau.segmentsOuvertsMinutes(stand)).containsExactly(new int[] {0, 300});
    }

    @Test
    void ouvertureSurUneAutreDateEstIgnoree() {
        Creneau creneau = new Creneau(1L, 1, JOUR, LocalTime.of(9, 0), LocalTime.of(14, 0));
        Stand stand = new Stand("S", "S", java.util.Set.of(), 1, 1, false);
        stand.setOuvertures(List.of(
                new OuvertureStand(null, JOUR.plusDays(5), LocalTime.of(9, 0), LocalTime.of(14, 0), null)));

        // No opening that day (and no closing either): open by default.
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
        // heureFin before heureDebut: must never open the timeslot — a stand
        // with no valid opening that day stays open by default.
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

    /* ---------------- "Until closing time" (null heureFin) ---------------- */

    @Test
    void ouvertureSansHeureFinCourtJusquALaFinDuCreneau() {
        Creneau creneau = new Creneau(1L, 1, JOUR, LocalTime.of(10, 0), LocalTime.of(20, 0));

        assertThat(creneau.segmentsOuvertsMinutes(open(LocalTime.of(14, 0), null)))
                .containsExactly(new int[] {240, 600});
    }

    @Test
    void fermetureSansHeureFinFermeJusquALaFinDuCreneau() {
        Creneau creneau = new Creneau(1L, 1, JOUR, LocalTime.of(10, 0), LocalTime.of(20, 0));

        assertThat(creneau.segmentsOuvertsMinutes(fermer(LocalTime.of(14, 0), null)))
                .containsExactly(new int[] {0, 240});
    }

    /**
     * The point of the open-ended form: the same window covers a day closing at
     * 20:00 and a day closing at midnight, where a concrete end time would need
     * two entries — and could not even name midnight, since a window may not
     * cross it (hence the {@code 23:59} this replaces).
     */
    @Test
    void uneMemeOuvertureSansFinSAdapteALAmplitudeDuJour() {
        Stand stand = open(LocalTime.of(14, 0), null);

        Creneau jourCourt = new Creneau(1L, 1, JOUR, LocalTime.of(10, 0), LocalTime.of(20, 0));
        Creneau jourJusquaMinuit = new Creneau(2L, 1, JOUR, LocalTime.of(10, 0), LocalTime.MIDNIGHT);

        assertThat(jourCourt.segmentsOuvertsMinutes(stand)).containsExactly(new int[] {240, 600});
        assertThat(jourJusquaMinuit.segmentsOuvertsMinutes(stand)).containsExactly(new int[] {240, 840});
    }

    @Test
    void ouvertureSansHeureFinCommencantApresLeCreneauNeLOuvrePas() {
        Creneau matin = new Creneau(1L, 1, JOUR, LocalTime.of(10, 0), LocalTime.of(12, 0));

        assertThat(matin.segmentsOuvertsMinutes(open(LocalTime.of(14, 0), null))).isEmpty();
    }

    /* ------------------- Dated window of the next day ------------------- */

    /**
     * A window dated the day <i>after</i> a slot that does not cross midnight
     * cannot overlap it, so it must not decide that slot's mode either. Reading
     * the next day unconditionally — as this did before recurring horaires — let
     * an opening dated the following day flip a 10:00-20:00 slot to
     * closed-by-default, silently shutting a stand whose only statement that day
     * was a two-hour closure. Harmless while openings were hand-dated and rare;
     * not once a rule expands one onto every event day.
     */
    @Test
    void ouvertureDuLendemainNAffectePasUnCreneauQuiNeTraversePasMinuit() {
        Creneau journee = new Creneau(1L, 1, JOUR, LocalTime.of(10, 0), LocalTime.of(20, 0));
        Stand stand = new Stand("S", "S", java.util.Set.of(), 1, 1, false);
        stand.setIndisponibilites(List.of(
                new IndisponibiliteStand(null, JOUR, LocalTime.of(14, 0), LocalTime.of(16, 0), null)));
        stand.setOuvertures(List.of(
                new OuvertureStand(null, JOUR.plusDays(1), LocalTime.of(10, 0), LocalTime.of(20, 0), null)));

        assertThat(journee.segmentsOuvertsMinutes(stand))
                .containsExactly(new int[] {0, 240}, new int[] {360, 600});
    }

    /* --------------------- Effective windows ----------------------- */

    /**
     * With no resolution having run, the effective windows are the dated lists
     * themselves — which is what keeps every caller that never resolves anything
     * behaving exactly as it did before rules existed.
     */
    @Test
    void sansResolutionLesFenetresEffectivesSontLesFenetresDatees() {
        Stand stand = fermer(LocalTime.of(14, 0), LocalTime.of(16, 0));

        assertThat(stand.getIndisponibilitesEffectives()).isSameAs(stand.getIndisponibilites());
        assertThat(stand.getOuverturesEffectives()).isSameAs(stand.getOuvertures());
    }

    /** Once resolved, the slot reads the effective windows and not the persisted ones. */
    @Test
    void unSegmentSuitLesFenetresEffectivesQuandEllesSontRenseignees() {
        Creneau creneau = new Creneau(1L, 1, JOUR, LocalTime.of(10, 0), LocalTime.of(20, 0));
        Stand stand = new Stand("S", "S", java.util.Set.of(), 1, 1, false);
        stand.setFenetresEffectives(List.of(), List.of(
                new OuvertureStand(null, JOUR, LocalTime.of(14, 0), null, null)));

        assertThat(creneau.segmentsOuvertsMinutes(stand)).containsExactly(new int[] {240, 600});
        // The persisted lists stayed empty: a save could not freeze the expansion.
        assertThat(stand.getOuvertures()).isEmpty();
    }

    private static Stand fermer(LocalTime debut, LocalTime fin) {
        Stand stand = new Stand("S", "S", java.util.Set.of(), 1, 1, false);
        stand.setIndisponibilites(List.of(new IndisponibiliteStand(null, JOUR, debut, fin, null)));
        return stand;
    }

    private static Stand open(LocalTime debut, LocalTime fin) {
        Stand stand = new Stand("S", "S", java.util.Set.of(), 1, 1, false);
        stand.setOuvertures(List.of(new OuvertureStand(null, JOUR, debut, fin, null)));
        return stand;
    }
}
