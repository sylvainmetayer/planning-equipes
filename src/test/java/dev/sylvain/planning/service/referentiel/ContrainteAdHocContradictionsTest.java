package dev.sylvain.planning.service.referentiel;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.ContrainteAdHoc;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.domain.TypeContrainteAdHoc;
import dev.sylvain.planning.service.referentiel.ContrainteAdHocContradictions.Contradiction;
import dev.sylvain.planning.service.referentiel.ContrainteAdHocContradictions.TypeContradiction;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The impossible combinations of hand-entered exceptions (issue #84) that the
 * forced types' withdrawal (ADR 0117) leaves, and — just as important — the
 * near-misses that must go through: a combination
 * refused by mistake costs the user an exception they were entitled to, with
 * no way around it.
 */
class ContrainteAdHocContradictionsTest {

    private static final Creneau MATIN = creneau(1, LocalTime.of(10, 0), LocalTime.of(14, 0));

    /* --------------- Rule 1: the same pair, incompatible and preferred --------------- */

    @Test
    void anAffiniteOnAnAlreadyIncompatiblePairIsRefused() {
        ContrainteAdHoc incompatibilite = pair("C1", TypeContrainteAdHoc.INCOMPATIBILITE, "A1", "A2");
        ContrainteAdHoc affinite = pair("C2", TypeContrainteAdHoc.AFFINITE, "A1", "A2");

        assertThat(detect(affinite, incompatibilite)).singleElement().satisfies(contradiction -> {
            assertThat(contradiction.type()).isEqualTo(TypeContradiction.PAIRE_INCOMPATIBLE_ET_AFFINE);
            assertThat(contradiction.contrainteIds()).containsExactly("C1", "C2");
            assertThat(contradiction.message()).contains("C1").contains("incompatible");
        });
    }

    @Test
    void theSamePairIsSeenWhicheverWayRoundItIsTyped() {
        ContrainteAdHoc affinite = pair("C1", TypeContrainteAdHoc.AFFINITE, "A1", "A2");
        ContrainteAdHoc incompatibilite = pair("C2", TypeContrainteAdHoc.INCOMPATIBILITE, "A2", "A1");

        assertThat(detect(incompatibilite, affinite)).hasSize(1);
    }

    @Test
    void anotherPairIsAccepted() {
        ContrainteAdHoc incompatibilite = pair("C1", TypeContrainteAdHoc.INCOMPATIBILITE, "A1", "A2");
        ContrainteAdHoc affinite = pair("C2", TypeContrainteAdHoc.AFFINITE, "A1", "A3");

        assertThat(detect(affinite, incompatibilite)).isEmpty();
    }

    @Test
    void savingUnderTheSameIdReplacesTheConflictingVersion() {
        ContrainteAdHoc incompatibilite = pair("C1", TypeContrainteAdHoc.INCOMPATIBILITE, "A1", "A2");
        ContrainteAdHoc affinite = pair("C1", TypeContrainteAdHoc.AFFINITE, "A1", "A2");

        assertThat(detect(affinite, incompatibilite)).isEmpty();
    }

    /* ----------------------------- The set as a whole ----------------------------- */

    @Test
    void everyContradictionOfASetIsReportedOnceEach() {
        List<ContrainteAdHoc> contraintes = List.of(
                pair("C1", TypeContrainteAdHoc.INCOMPATIBILITE, "A1", "A2"),
                pair("C2", TypeContrainteAdHoc.AFFINITE, "A1", "A2"),
                scoped("G1", TypeContrainteAdHoc.ARRIVEE_GROUPEE, null, null, "A1", "A2", "A5"));

        assertThat(ContrainteAdHocContradictions.detectAll(contraintes))
                .extracting(Contradiction::type)
                .containsExactly(
                        TypeContradiction.PAIRE_INCOMPATIBLE_ET_AFFINE, TypeContradiction.ARRIVEE_GROUPEE_INCOMPATIBLE);
    }

    @Test
    void aConsistentSetHoldsNoContradiction() {
        List<ContrainteAdHoc> contraintes = List.of(
                pair("C1", TypeContrainteAdHoc.INCOMPATIBILITE, "A1", "A2"),
                pair("C2", TypeContrainteAdHoc.AFFINITE, "A1", "A3"),
                pair("C3", TypeContrainteAdHoc.AFFINITE, "A2", "A3"));

        assertThat(ContrainteAdHocContradictions.detectAll(contraintes)).isEmpty();
    }

    @Test
    void nullsAndUnidentifiedConstraintsAreIgnored() {
        List<ContrainteAdHoc> contraintes = new ArrayList<>();
        contraintes.add(null);
        contraintes.add(new ContrainteAdHoc(null, TypeContrainteAdHoc.INCOMPATIBILITE));
        contraintes.add(new ContrainteAdHoc("C1", null));

        assertThat(ContrainteAdHocContradictions.detectAll(contraintes)).isEmpty();
        assertThat(ContrainteAdHocContradictions.detectAll(null)).isEmpty();
        assertThat(ContrainteAdHocContradictions.detect(null, contraintes)).isEmpty();
    }

    /* --------------- Rule 5: a grouped arrival holding an incompatible pair --------------- */

    @Test
    void aGroupedArrivalHoldingAnIncompatiblePairIsRefusedWhicheverComesLast() {
        ContrainteAdHoc incompatibilite = pair("C1", TypeContrainteAdHoc.INCOMPATIBILITE, "A2", "A3");
        ContrainteAdHoc groupe = scoped("G1", TypeContrainteAdHoc.ARRIVEE_GROUPEE, null, null, "A1", "A2", "A3");

        assertThat(detect(groupe, incompatibilite)).singleElement().satisfies(contradiction -> {
            assertThat(contradiction.type()).isEqualTo(TypeContradiction.ARRIVEE_GROUPEE_INCOMPATIBLE);
            assertThat(contradiction.contrainteIds()).containsExactly("C1", "G1");
        });
        assertThat(detect(incompatibilite, groupe)).hasSize(1);
        assertThat(ContrainteAdHocContradictions.detectAll(List.of(groupe, incompatibilite)))
                .hasSize(1);
    }

    @Test
    void aGroupedArrivalHoldingOnlyOneOfAnIncompatiblePairIsAccepted() {
        ContrainteAdHoc incompatibilite = pair("C1", TypeContrainteAdHoc.INCOMPATIBILITE, "A2", "A9");
        ContrainteAdHoc groupe = scoped("G1", TypeContrainteAdHoc.ARRIVEE_GROUPEE, null, null, "A1", "A2");

        assertThat(detect(groupe, incompatibilite)).isEmpty();
    }

    @Test
    void aGroupedArrivalHoldingAPairIncompatibleOnlyOnAStandOrATimeslotIsAccepted() {
        ContrainteAdHoc groupe = scoped("G1", TypeContrainteAdHoc.ARRIVEE_GROUPEE, null, null, "A2", "A3");
        ContrainteAdHoc surUnStand = scopedPair("C1", null, "S1", "A2", "A3");
        ContrainteAdHoc surUnCreneau = scopedPair("C2", MATIN, null, "A2", "A3");

        assertThat(detect(groupe, surUnStand, surUnCreneau)).isEmpty();
        assertThat(detect(surUnStand, groupe)).isEmpty();
        assertThat(detect(surUnCreneau, groupe)).isEmpty();
    }

    private static List<Contradiction> detect(ContrainteAdHoc candidate, ContrainteAdHoc... others) {
        return ContrainteAdHocContradictions.detect(candidate, List.of(others));
    }

    private static ContrainteAdHoc pair(String id, TypeContrainteAdHoc type, String premier, String second) {
        return scoped(id, type, null, null, premier, second);
    }

    private static ContrainteAdHoc scopedPair(
            String id, Creneau creneau, String standId, String premier, String second) {
        return scoped(id, TypeContrainteAdHoc.INCOMPATIBILITE, creneau, standId, premier, second);
    }

    private static ContrainteAdHoc scoped(
            String id, TypeContrainteAdHoc type, Creneau creneau, String standId, String... animateurs) {
        ContrainteAdHoc contrainte = new ContrainteAdHoc(id, type);
        contrainte.setCreneau(creneau);
        if (standId != null) {
            Stand stand = new Stand();
            stand.setId(standId);
            contrainte.setStand(stand);
        }
        List<Animateur> cibles = new ArrayList<>();
        for (String animateurId : animateurs) {
            Animateur animateur = new Animateur();
            animateur.setId(animateurId);
            cibles.add(animateur);
        }
        contrainte.setAnimateursConcernes(cibles);
        return contrainte;
    }

    private static Creneau creneau(long id, LocalTime debut, LocalTime fin) {
        return new Creneau(id, 1, LocalDate.of(2026, 8, 1), debut, fin);
    }
}
