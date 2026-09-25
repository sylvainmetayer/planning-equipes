package dev.sylvain.planning.service.analyse;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.Emplacement;
import dev.sylvain.planning.domain.ParametresLegaux;
import dev.sylvain.planning.domain.ParametresQualite;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.service.analyse.WalkSequenceAnalyzer.WalkSequenceReport;
import dev.sylvain.planning.service.analyse.WalkSequenceAnalyzer.WalkView;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class WalkSequenceAnalyzerTest {

    private static final LocalDate JOUR = LocalDate.of(2026, 7, 10);

    /** Two places 1 000 m apart: 20 minutes' walk at 4 km/h with a detour factor of 1.3. */
    private static final Emplacement LIEU_A = new Emplacement("A", "A", 46.6500, 2.2500);

    private static final Emplacement LIEU_B = new Emplacement("B", "B", 46.6500 + 0.0089932, 2.2500);

    private final WalkSequenceAnalyzer analyzer = new WalkSequenceAnalyzer();

    private final Animateur alice = new Animateur("alice", "alice", "ALICE", LocalDate.of(1990, 1, 1), false);

    @Test
    void aTenMinuteGapForATwentyMinuteWalkIsListedWithFiveMinutesMissing() {
        WalkSequenceReport rapport = analyze(
                poste("p1", stand("SA", LIEU_A), creneau(1, 10, 0, 14, 0)),
                poste("p2", stand("SB", LIEU_B), creneau(2, 14, 10, 18, 0)));

        assertThat(rapport.geolocated()).isTrue();
        assertThat(rapport.walks()).singleElement().satisfies(enchainement -> {
            assertThat(enchainement.animateurId()).isEqualTo("alice");
            assertThat(enchainement.date()).isEqualTo(JOUR);
            assertThat(enchainement.end()).isEqualTo(LocalTime.of(14, 0));
            assertThat(enchainement.start()).isEqualTo(LocalTime.of(14, 10));
            assertThat(enchainement.fromEmplacementId()).isEqualTo("A");
            assertThat(enchainement.toEmplacementId()).isEqualTo("B");
            assertThat(enchainement.distanceMetres()).isEqualTo(1000);
            assertThat(enchainement.walkMinutes()).isEqualTo(20);
            assertThat(enchainement.gapMinutes()).isEqualTo(10);
            assertThat(enchainement.missingMinutes()).isEqualTo(5);
            assertThat(enchainement.walkOnBreak()).isFalse();
        });
    }

    @Test
    void aZeroGapIsListedToo() {
        WalkSequenceReport rapport = analyze(
                poste("p1", stand("SA", LIEU_A), creneau(1, 10, 0, 14, 0)),
                poste("p2", stand("SB", LIEU_B), creneau(2, 14, 0, 18, 0)));

        assertThat(rapport.walks()).extracting(WalkView::missingMinutes).containsExactly(15);
    }

    @Test
    void aWalkThatEatsTheLegalBreakIsFlaggedEvenWhenItFits() {
        // Thirty minutes between the two: the walk fits, but leaves ten of the
        // twenty-minute break.
        WalkSequenceReport rapport = analyze(
                poste("p1", stand("SA", LIEU_A), creneau(1, 10, 0, 14, 0)),
                poste("p2", stand("SB", LIEU_B), creneau(2, 14, 30, 18, 0)));

        assertThat(rapport.walks()).singleElement().satisfies(enchainement -> {
            assertThat(enchainement.missingMinutes()).isZero();
            assertThat(enchainement.walkOnBreak()).isTrue();
        });
    }

    @Test
    void aGapLongEnoughForTheWalkAndTheBreakIsNotListed() {
        WalkSequenceReport rapport = analyze(
                poste("p1", stand("SA", LIEU_A), creneau(1, 10, 0, 14, 0)),
                poste("p2", stand("SB", LIEU_B), creneau(2, 15, 0, 18, 0)));

        assertThat(rapport.walks()).isEmpty();
    }

    @Test
    void aStandWithoutEmplacementOrCoordinatesProducesNoLine() {
        Emplacement sansCoordonnees = new Emplacement("X", "X", null, null);
        WalkSequenceReport rapport = analyze(
                poste("p1", stand("SA", LIEU_A), creneau(1, 10, 0, 12, 0)),
                poste("p2", stand("SN", null), creneau(2, 12, 5, 13, 0)),
                poste("p3", stand("SX", sansCoordonnees), creneau(3, 13, 5, 14, 0)));

        assertThat(rapport.walks()).isEmpty();
    }

    @Test
    void aSeatWithoutEmplacementInBetweenSplitsThePairAsTheRuleDoes() {
        // A (located) → a stand with no emplacement → B (located): the rule
        // never pairs A with B across the seat in between, nor may the reading.
        WalkSequenceReport rapport = analyze(
                poste("p1", stand("SA", LIEU_A), creneau(1, 10, 0, 12, 0)),
                poste("p2", stand("SN", null), creneau(2, 12, 2, 12, 8)),
                poste("p3", stand("SB", LIEU_B), creneau(3, 12, 10, 14, 0)));

        assertThat(rapport.geolocated()).isTrue();
        assertThat(rapport.walks()).isEmpty();
    }

    @Test
    void anEditionWithoutAnyGeolocatedEmplacementSaysSo() {
        WalkSequenceReport rapport = analyze(poste("p1", stand("SN", null), creneau(1, 10, 0, 12, 0)));

        assertThat(rapport.geolocated()).isFalse();
        assertThat(rapport.walks()).isEmpty();
    }

    private WalkSequenceReport analyze(PosteAffectation... postes) {
        ParametresLegaux legaux = new ParametresLegaux();
        legaux.setDureePauseMinutes(20);
        return analyzer.analyze(
                new PlanningEvenement(JOUR, List.of(alice), List.of(postes)), new ParametresQualite(), legaux);
    }

    private static Stand stand(String id, Emplacement emplacement) {
        Stand stand = new Stand(id, id, Set.of("JEUX"), 1, 1, false);
        stand.setEmplacement(emplacement);
        return stand;
    }

    private static Creneau creneau(long id, int hDebut, int mDebut, int hFin, int mFin) {
        return new Creneau(id, 1, JOUR, LocalTime.of(hDebut, mDebut), LocalTime.of(hFin, mFin));
    }

    private PosteAffectation poste(String id, Stand stand, Creneau creneau) {
        PosteAffectation poste = new PosteAffectation(id, stand, creneau);
        poste.setAnimateur(alice);
        return poste;
    }
}
