package dev.sylvain.planning.service.referentiel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.ContrainteAdHoc;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.TypeContrainteAdHoc;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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

    @Inject
    ReferenceDataService referenceDataService;

    private Animateur premier;
    private Animateur second;
    private Animateur troisieme;
    private Creneau matin;
    private Creneau chevauchant;

    /** The ids the application drew for the constraints a test created (ADR 0050). */
    private final List<String> contraintesCreees = new ArrayList<>();

    @BeforeEach
    void createAnimateurs() {
        premier = animateur();
        second = animateur();
        troisieme = animateur();
        matin = referenceDataService.createCreneau(
                new Creneau(null, 1, LocalDate.of(2026, 8, 1), LocalTime.of(10, 0), LocalTime.of(14, 0)));
        chevauchant = referenceDataService.createCreneau(
                new Creneau(null, 1, LocalDate.of(2026, 8, 1), LocalTime.of(12, 0), LocalTime.of(16, 0)));
    }

    @AfterEach
    void cleanUp() {
        List<String> restantes = referenceDataService.listContraintesAdHoc().stream()
                .map(ContrainteAdHoc::getId)
                .toList();
        contraintesCreees.stream().filter(restantes::contains).forEach(referenceDataService::deleteContrainteAdHoc);
        contraintesCreees.clear();
        for (Animateur animateur : List.of(premier, second, troisieme)) {
            referenceDataService.deleteAnimateur(animateur.getId());
        }
        for (Creneau creneau : List.of(matin, chevauchant)) {
            referenceDataService.deleteCreneau(creneau.getId());
        }
    }

    @Test
    void anAffinityOnAnAlreadyIncompatiblePairIsRejected() {
        String incompatibilite = create(contrainte(TypeContrainteAdHoc.INCOMPATIBILITE, premier, second));

        assertThatThrownBy(() -> create(contrainte(TypeContrainteAdHoc.AFFINITE, premier, second)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(incompatibilite)
                .hasMessageContaining("incompatible");
    }

    @Test
    void anIncompatibilityOnAPairAlreadyInAffinityIsRejectedEvenWithThePairReversed() {
        String affinite = create(contrainte(TypeContrainteAdHoc.AFFINITE, premier, second));

        // The same pair, declared the other way round: the contradiction must be seen.
        assertThatThrownBy(() -> create(contrainte(TypeContrainteAdHoc.INCOMPATIBILITE, second, premier)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(affinite);
    }

    @Test
    void anAffinityOnAnotherPairIsAccepted() {
        String incompatibilite = create(contrainte(TypeContrainteAdHoc.INCOMPATIBILITE, premier, second));

        String[] affinite = new String[1];
        assertThatCode(() -> affinite[0] = create(contrainte(TypeContrainteAdHoc.AFFINITE, premier, troisieme)))
                .doesNotThrowAnyException();

        assertThat(referenceDataService.listContraintesAdHoc())
                .extracting(ContrainteAdHoc::getId)
                .contains(incompatibilite, affinite[0]);
    }

    @Test
    void savingTheSameConstraintAgainUnderItsIdMayChangeItsType() {
        String id = create(contrainte(TypeContrainteAdHoc.INCOMPATIBILITE, premier, second));

        // Saving replaces the previous version: no coexistence, no conflict.
        ContrainteAdHoc resaisie = contrainte(TypeContrainteAdHoc.AFFINITE, premier, second);
        resaisie.setId(id);
        assertThatCode(() -> referenceDataService.createContrainteAdHoc(resaisie))
                .doesNotThrowAnyException();
    }

    @Test
    void aNewConstraintSentWithAnIdThatNamesNothingIsRefused() {
        // A new exception is sent without an id: the application draws one.
        ContrainteAdHoc inventee = contrainte(TypeContrainteAdHoc.INCOMPATIBILITE, premier, second);
        inventee.setId("AFFI-TEST-C1");

        assertThatThrownBy(() -> referenceDataService.createContrainteAdHoc(inventee))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Ajustement inconnu");
    }

    @Test
    void aForcedSeatOnAnUnavailableSlotIsRefused() {
        String indisponibilite = create(onCreneau(TypeContrainteAdHoc.INDISPONIBILITE_FORCEE, matin, premier));

        assertThatThrownBy(() -> create(onCreneau(TypeContrainteAdHoc.AFFECTATION_FORCEE, matin, premier)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(indisponibilite)
                .hasMessageContaining("indisponible");
    }

    @Test
    void twoForcedSeatsOnOverlappingCreneauxAreRefused() {
        // The overlap is read from the persisted créneaux: the constraint only
        // carries their ids.
        String premiere = create(onCreneau(TypeContrainteAdHoc.AFFECTATION_FORCEE, matin, premier));

        assertThatThrownBy(() -> create(onCreneau(TypeContrainteAdHoc.AFFECTATION_FORCEE, chevauchant, premier)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(premiere)
                .hasMessageContaining("chevauchent");
    }

    @Test
    void anIncompatiblePairForcedOntoOneCreneauIsRefused() {
        String incompatibilite = create(contrainte(TypeContrainteAdHoc.INCOMPATIBILITE, premier, second));
        String affectation = create(onCreneau(TypeContrainteAdHoc.AFFECTATION_FORCEE, matin, premier));

        assertThatThrownBy(() -> create(onCreneau(TypeContrainteAdHoc.AFFECTATION_FORCEE, matin, second)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(incompatibilite)
                .hasMessageContaining(affectation)
                .hasMessageContaining("incompatibles");
    }

    @Test
    void twoForcedSeatsOnDisjointCreneauxAreAccepted() {
        Creneau soir = referenceDataService.createCreneau(
                new Creneau(null, 1, LocalDate.of(2026, 8, 1), LocalTime.of(16, 0), LocalTime.of(20, 0)));
        create(onCreneau(TypeContrainteAdHoc.AFFECTATION_FORCEE, matin, premier));

        assertThatCode(() -> create(onCreneau(TypeContrainteAdHoc.AFFECTATION_FORCEE, soir, premier)))
                .doesNotThrowAnyException();

        // The constraint on that créneau goes first: it names it.
        cleanUpConstraints();
        referenceDataService.deleteCreneau(soir.getId());
    }

    /** Creates the constraint without an id and answers the one the application drew. */
    private String create(ContrainteAdHoc contrainte) {
        String id = referenceDataService.createContrainteAdHoc(contrainte).getId();
        contraintesCreees.add(id);
        return id;
    }

    private void cleanUpConstraints() {
        contraintesCreees.forEach(referenceDataService::deleteContrainteAdHoc);
        contraintesCreees.clear();
    }

    private static ContrainteAdHoc onCreneau(TypeContrainteAdHoc type, Creneau creneau, Animateur... animateurs) {
        ContrainteAdHoc contrainte = contrainte(type, animateurs);
        contrainte.setCreneau(creneau);
        return contrainte;
    }

    private Animateur animateur() {
        return referenceDataService.createAnimateur(
                new Animateur(null, "Prenom", "Nom", LocalDate.of(2000, 1, 1), false));
    }

    private static ContrainteAdHoc contrainte(TypeContrainteAdHoc type, Animateur... animateurs) {
        ContrainteAdHoc contrainte = new ContrainteAdHoc(null, type);
        contrainte.setAnimateursConcernes(List.of(animateurs));
        contrainte.setCreeParUtilisateurId("test");
        return contrainte;
    }
}
