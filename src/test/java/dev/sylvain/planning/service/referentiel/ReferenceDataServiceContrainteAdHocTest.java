package dev.sylvain.planning.service.referentiel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.ContrainteAdHoc;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.TypeContrainteAdHoc;

/**
 * {@link ReferenceDataService#createContrainteAdHoc} refusing an exception
 * that contradicts one already recorded (issues #80 and #84), end to end:
 * through the facade, against constraints and créneaux actually persisted.
 *
 * <p>What each combination means, and the near-misses that must go through,
 * are covered by {@link ContrainteAdHocContradictionsTest} on the plain rules;
 * this class checks that the entry point reads the recorded set — including
 * the créneaux, which is what tells two forced assignments apart.</p>
 */
@QuarkusTest
class ReferenceDataServiceContrainteAdHocTest {

    private static final String PREFIXE = "AFFI-TEST-";

    @Inject
    ReferenceDataService referenceDataService;

    private Animateur premier;
    private Animateur second;
    private Animateur troisieme;
    private Creneau matin;
    private Creneau chevauchant;

    @BeforeEach
    void createAnimateurs() {
        premier = animateur(PREFIXE + "A1");
        second = animateur(PREFIXE + "A2");
        troisieme = animateur(PREFIXE + "A3");
        matin = referenceDataService.createCreneau(
                new Creneau(null, 1, LocalDate.of(2026, 8, 1), LocalTime.of(10, 0), LocalTime.of(14, 0)));
        chevauchant = referenceDataService.createCreneau(
                new Creneau(null, 1, LocalDate.of(2026, 8, 1), LocalTime.of(12, 0), LocalTime.of(16, 0)));
    }

    @AfterEach
    void cleanUp() {
        referenceDataService.listContraintesAdHoc().stream()
                .map(ContrainteAdHoc::getId)
                .filter(id -> id.startsWith(PREFIXE))
                .forEach(referenceDataService::deleteContrainteAdHoc);
        for (Animateur animateur : List.of(premier, second, troisieme)) {
            referenceDataService.deleteAnimateur(animateur.getId());
        }
        for (Creneau creneau : List.of(matin, chevauchant)) {
            referenceDataService.deleteCreneau(creneau.getId());
        }
    }

    @Test
    void affiniteSurUnePaireDejaIncompatibleEstRejetee() {
        referenceDataService.createContrainteAdHoc(
                contrainte(PREFIXE + "C1", TypeContrainteAdHoc.INCOMPATIBILITE, premier, second));

        assertThatThrownBy(() -> referenceDataService.createContrainteAdHoc(
                contrainte(PREFIXE + "C2", TypeContrainteAdHoc.AFFINITE, premier, second)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(PREFIXE + "C1")
                .hasMessageContaining("incompatible");
    }

    @Test
    void incompatibiliteSurUnePaireDejaEnAffiniteEstRejeteeMemeAvecLaPaireInversee() {
        referenceDataService.createContrainteAdHoc(
                contrainte(PREFIXE + "C1", TypeContrainteAdHoc.AFFINITE, premier, second));

        // The same pair, declared the other way round: the contradiction must be seen.
        assertThatThrownBy(() -> referenceDataService.createContrainteAdHoc(
                contrainte(PREFIXE + "C2", TypeContrainteAdHoc.INCOMPATIBILITE, second, premier)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(PREFIXE + "C1");
    }

    @Test
    void affiniteSurUneAutrePaireEstAcceptee() {
        referenceDataService.createContrainteAdHoc(
                contrainte(PREFIXE + "C1", TypeContrainteAdHoc.INCOMPATIBILITE, premier, second));

        assertThatCode(() -> referenceDataService.createContrainteAdHoc(
                contrainte(PREFIXE + "C2", TypeContrainteAdHoc.AFFINITE, premier, troisieme)))
                .doesNotThrowAnyException();

        assertThat(referenceDataService.listContraintesAdHoc())
                .extracting(ContrainteAdHoc::getId)
                .contains(PREFIXE + "C1", PREFIXE + "C2");
    }

    @Test
    void resaisirLaMemeContrainteSousSonIdPeutChangerDeType() {
        referenceDataService.createContrainteAdHoc(
                contrainte(PREFIXE + "C1", TypeContrainteAdHoc.INCOMPATIBILITE, premier, second));

        // Saving replaces the previous version: no coexistence, no conflict.
        assertThatCode(() -> referenceDataService.createContrainteAdHoc(
                contrainte(PREFIXE + "C1", TypeContrainteAdHoc.AFFINITE, premier, second)))
                .doesNotThrowAnyException();
    }

    @Test
    void aForcedSeatOnAnUnavailableSlotIsRefused() {
        referenceDataService.createContrainteAdHoc(
                onCreneau(PREFIXE + "C1", TypeContrainteAdHoc.INDISPONIBILITE_FORCEE, matin, premier));

        assertThatThrownBy(() -> referenceDataService.createContrainteAdHoc(
                onCreneau(PREFIXE + "C2", TypeContrainteAdHoc.AFFECTATION_FORCEE, matin, premier)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(PREFIXE + "C1")
                .hasMessageContaining("indisponible");
    }

    @Test
    void twoForcedSeatsOnOverlappingCreneauxAreRefused() {
        // The overlap is read from the persisted créneaux: the constraint only
        // carries their ids.
        referenceDataService.createContrainteAdHoc(
                onCreneau(PREFIXE + "C1", TypeContrainteAdHoc.AFFECTATION_FORCEE, matin, premier));

        assertThatThrownBy(() -> referenceDataService.createContrainteAdHoc(
                onCreneau(PREFIXE + "C2", TypeContrainteAdHoc.AFFECTATION_FORCEE, chevauchant, premier)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(PREFIXE + "C1")
                .hasMessageContaining("chevauchent");
    }

    @Test
    void anIncompatiblePairForcedOntoOneCreneauIsRefused() {
        referenceDataService.createContrainteAdHoc(
                contrainte(PREFIXE + "C1", TypeContrainteAdHoc.INCOMPATIBILITE, premier, second));
        referenceDataService.createContrainteAdHoc(
                onCreneau(PREFIXE + "C2", TypeContrainteAdHoc.AFFECTATION_FORCEE, matin, premier));

        assertThatThrownBy(() -> referenceDataService.createContrainteAdHoc(
                onCreneau(PREFIXE + "C3", TypeContrainteAdHoc.AFFECTATION_FORCEE, matin, second)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(PREFIXE + "C1")
                .hasMessageContaining(PREFIXE + "C2")
                .hasMessageContaining("incompatibles");
    }

    @Test
    void twoForcedSeatsOnDisjointCreneauxAreAccepted() {
        Creneau soir = referenceDataService.createCreneau(
                new Creneau(null, 1, LocalDate.of(2026, 8, 1), LocalTime.of(16, 0), LocalTime.of(20, 0)));
        referenceDataService.createContrainteAdHoc(
                onCreneau(PREFIXE + "C1", TypeContrainteAdHoc.AFFECTATION_FORCEE, matin, premier));

        assertThatCode(() -> referenceDataService.createContrainteAdHoc(
                onCreneau(PREFIXE + "C2", TypeContrainteAdHoc.AFFECTATION_FORCEE, soir, premier)))
                .doesNotThrowAnyException();

        referenceDataService.deleteCreneau(soir.getId());
    }

    private static ContrainteAdHoc onCreneau(String id, TypeContrainteAdHoc type, Creneau creneau,
            Animateur... animateurs) {
        ContrainteAdHoc contrainte = contrainte(id, type, animateurs);
        contrainte.setCreneau(creneau);
        return contrainte;
    }

    private Animateur animateur(String id) {
        return referenceDataService.createAnimateur(
                new Animateur(id, "Prenom", "Nom", LocalDate.of(2000, 1, 1), false));
    }

    private static ContrainteAdHoc contrainte(String id, TypeContrainteAdHoc type, Animateur... animateurs) {
        ContrainteAdHoc contrainte = new ContrainteAdHoc(id, type);
        contrainte.setAnimateursConcernes(List.of(animateurs));
        contrainte.setCreeParUtilisateurId("test");
        return contrainte;
    }
}
