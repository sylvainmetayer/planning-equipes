package dev.sylvain.planning.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import dev.sylvain.planning.domain.Creneau.SegmentOuvert;

/**
 * {@link Creneau#segmentsOuverts(Stand)} is what lets a stand whose
 * staffing varies during the day be modelled as one stand: the headcount rides
 * on the opening window, not on the stand.
 *
 * <p>The two properties that matter are that a window naming no effectif
 * behaves exactly as before the field existed — otherwise every existing
 * edition would silently change volume — and that
 * {@link Creneau#segmentsOuvertsMinutes(Stand)} keeps answering the pure
 * "when is this stand open" question its other callers ask, whatever the
 * staffing does inside.</p>
 */
class CreneauSegmentOuvertTest {

    private static final LocalDate JOUR = LocalDate.of(2026, 7, 10);

    /** 14:00 → 20:00, the shape of the real event's afternoon slot. */
    private final Creneau apresMidi = new Creneau(1L, 1, JOUR, LocalTime.of(14, 0), LocalTime.of(20, 0));

    @Test
    void sansOuvertureLeSegmentPorteLEffectifMinDuStand() {
        Stand stand = stand(3);

        assertThat(apresMidi.segmentsOuverts(stand))
                .containsExactly(new SegmentOuvert(0, 360, 3));
    }

    @Test
    void fenetreSansEffectifHeriteDeLEffectifMinDuStand() {
        Stand stand = standOuvert(3, ouverture(LocalTime.of(14, 0), LocalTime.of(20, 0), null));

        assertThat(apresMidi.segmentsOuverts(stand))
                .containsExactly(new SegmentOuvert(0, 360, 3));
    }

    @Test
    void fenetreAvecEffectifImposeLeSienPlutotQueCeluiDuStand() {
        Stand stand = standOuvert(1, ouverture(LocalTime.of(14, 0), LocalTime.of(20, 0), 4));

        assertThat(apresMidi.segmentsOuverts(stand))
                .containsExactly(new SegmentOuvert(0, 360, 4));
    }

    /**
     * The case the whole field exists for: 4 people until 19:00, 2 after —
     * one slot, two segments.
     */
    @Test
    void deuxFenetresContiguesDEffectifsDifferentsDonnentDeuxSegments() {
        Stand stand = standOuvert(1,
                ouverture(LocalTime.of(14, 0), LocalTime.of(19, 0), 4),
                ouverture(LocalTime.of(19, 0), LocalTime.of(20, 0), 2));

        assertThat(apresMidi.segmentsOuverts(stand))
                .containsExactly(new SegmentOuvert(0, 300, 4), new SegmentOuvert(300, 360, 2));
    }

    @Test
    void deuxFenetresContiguesDeMemeEffectifSontFusionnees() {
        Stand stand = standOuvert(1,
                ouverture(LocalTime.of(14, 0), LocalTime.of(19, 0), 4),
                ouverture(LocalTime.of(19, 0), LocalTime.of(20, 0), 4));

        assertThat(apresMidi.segmentsOuverts(stand))
                .containsExactly(new SegmentOuvert(0, 360, 4));
    }

    /** Overlapping windows are two statements of a need: the larger satisfies both. */
    @Test
    void surLeRecouvrementDeDeuxFenetresLEffectifLePlusHautLEmporte() {
        Stand stand = standOuvert(1,
                ouverture(LocalTime.of(14, 0), LocalTime.of(18, 0), 2),
                ouverture(LocalTime.of(16, 0), LocalTime.of(20, 0), 5));

        assertThat(apresMidi.segmentsOuverts(stand))
                .containsExactly(new SegmentOuvert(0, 120, 2), new SegmentOuvert(120, 360, 5));
    }

    @Test
    void unTrouEntreDeuxFenetresResteFerme() {
        Stand stand = standOuvert(1,
                ouverture(LocalTime.of(14, 0), LocalTime.of(16, 0), 2),
                ouverture(LocalTime.of(18, 0), LocalTime.of(20, 0), 3));

        assertThat(apresMidi.segmentsOuverts(stand))
                .containsExactly(new SegmentOuvert(0, 120, 2), new SegmentOuvert(240, 360, 3));
    }

    /**
     * The projection must stay the pure opening geometry: a caller asking
     * "when is this stand open" gets one continuous opening, not the staffing
     * profile inside it.
     */
    @Test
    void laProjectionEnMinutesRefusionneLesSegmentsQueSeulLEffectifSepare() {
        Stand stand = standOuvert(1,
                ouverture(LocalTime.of(14, 0), LocalTime.of(19, 0), 4),
                ouverture(LocalTime.of(19, 0), LocalTime.of(20, 0), 2));

        assertThat(apresMidi.segmentsOuvertsMinutes(stand)).containsExactly(new int[] {0, 360});
        assertThat(apresMidi.isStandOpen(stand)).isTrue();
        assertThat(apresMidi.isStandFullyClosed(stand)).isFalse();
    }

    @Test
    void laProjectionGardeUnTrouReelSepare() {
        Stand stand = standOuvert(1,
                ouverture(LocalTime.of(14, 0), LocalTime.of(16, 0), 2),
                ouverture(LocalTime.of(18, 0), LocalTime.of(20, 0), 3));

        assertThat(apresMidi.segmentsOuvertsMinutes(stand))
                .containsExactly(new int[] {0, 120}, new int[] {240, 360});
    }

    /** A window is clamped to the slot, and its effectif rides along with the clamped part. */
    @Test
    void uneFenetreDebordantLeCreneauEstRogneeEnGardantSonEffectif() {
        Stand stand = standOuvert(1, ouverture(LocalTime.of(10, 0), LocalTime.of(17, 0), 6));

        assertThat(apresMidi.segmentsOuverts(stand))
                .containsExactly(new SegmentOuvert(0, 180, 6));
    }

    /** An open end means "until closing", so it takes the slot's own end — effectif included. */
    @Test
    void uneFenetreSansHeureDeFinCourtJusquALaFinDuCreneau() {
        Stand stand = standOuvert(1, ouverture(LocalTime.of(16, 0), null, 3));

        assertThat(apresMidi.segmentsOuverts(stand))
                .containsExactly(new SegmentOuvert(120, 360, 3));
    }

    /** Closure mode has no windows to carry a headcount: every segment falls back to the stand. */
    @Test
    void enModeFermetureLesSegmentsPortentLEffectifMinDuStand() {
        Stand stand = stand(2);
        stand.setIndisponibilites(List.of(
                new IndisponibiliteStand(null, JOUR, LocalTime.of(16, 0), LocalTime.of(17, 0), null)));

        assertThat(apresMidi.segmentsOuverts(stand))
                .extracting(SegmentOuvert::debutMinutes, SegmentOuvert::finMinutes, SegmentOuvert::effectif)
                .containsExactly(tuple(0, 120, 2), tuple(180, 360, 2));
    }

    // --- siegesSegment / siegesSimultanes: the one seat-counting rule ---------

    @Test
    void unSegmentSansEffectifDeclareGenereToujoursUnSiege() {
        assertThat(apresMidi.siegesSegment(0)).isEqualTo(1);
        assertThat(apresMidi.siegesSegment(-2)).isEqualTo(1);
        assertThat(apresMidi.siegesSegment(1)).isEqualTo(1);
        assertThat(apresMidi.siegesSegment(3)).isEqualTo(3);
    }

    @Test
    void uneVacationDeCouverturePauseHalveLesSiegesEnArrondissantAuSuperieur() {
        Creneau pause = new Creneau(2L, 1, JOUR, LocalTime.of(12, 0), LocalTime.of(13, 0));
        pause.setCouverturePause(true);

        assertThat(pause.siegesSegment(0)).isEqualTo(1);
        assertThat(pause.siegesSegment(1)).isEqualTo(1);
        assertThat(pause.siegesSegment(3)).isEqualTo(2);
        assertThat(pause.siegesSegment(4)).isEqualTo(2);
        assertThat(pause.siegesSegment(5)).isEqualTo(3);
    }

    @Test
    void lesSiegesSimultanesSuiventLEffectifMinSansFenetre() {
        assertThat(apresMidi.siegesSimultanes(stand(3))).isEqualTo(3);
        // A stand declared at zero still gets its one seat.
        assertThat(apresMidi.siegesSimultanes(stand(0))).isEqualTo(1);
    }

    @Test
    void lesSiegesSimultanesSuiventLEffectifDeLaFenetre() {
        assertThat(apresMidi.siegesSimultanes(
                standOuvert(1, ouverture(LocalTime.of(14, 0), LocalTime.of(20, 0), 4)))).isEqualTo(4);
        // A window may also ask for less than the stand's minimum.
        assertThat(apresMidi.siegesSimultanes(
                standOuvert(3, ouverture(LocalTime.of(14, 0), LocalTime.of(20, 0), 1)))).isEqualTo(1);
    }

    @Test
    void deuxSegmentsSuccessifsDonnentLePlusChargeDesDeuxPasLeurSomme() {
        Stand stand = standOuvert(1,
                ouverture(LocalTime.of(14, 0), LocalTime.of(17, 0), 2),
                ouverture(LocalTime.of(17, 0), LocalTime.of(20, 0), 5));

        assertThat(apresMidi.siegesSimultanes(stand)).isEqualTo(5);
    }

    @Test
    void unStandFermeSurLeCreneauNAAucunSiegeSimultane() {
        Stand stand = stand(3);
        stand.setIndisponibilites(List.of(
                new IndisponibiliteStand(null, JOUR, LocalTime.of(14, 0), LocalTime.of(20, 0), null)));

        assertThat(apresMidi.siegesSimultanes(stand)).isZero();
    }

    @Test
    void lesSiegesSimultanesDUneCouverturePauseSontHalves() {
        Creneau pause = new Creneau(2L, 1, JOUR, LocalTime.of(14, 0), LocalTime.of(20, 0));
        pause.setCouverturePause(true);

        assertThat(pause.siegesSimultanes(
                standOuvert(1, ouverture(LocalTime.of(14, 0), LocalTime.of(20, 0), 5)))).isEqualTo(3);
    }

    private static Stand stand(int effectifMin) {
        return new Stand("S", "S", Set.of(), effectifMin, 10, false);
    }

    private static Stand standOuvert(int effectifMin, OuvertureStand... ouvertures) {
        Stand stand = stand(effectifMin);
        stand.setOuvertures(List.of(ouvertures));
        return stand;
    }

    private static OuvertureStand ouverture(LocalTime debut, LocalTime fin, Integer effectif) {
        return new OuvertureStand(null, JOUR, debut, fin, null, effectif);
    }
}
