package dev.sylvain.planning.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.ContrainteAdHoc;
import dev.sylvain.planning.domain.TypeContrainteAdHoc;

/**
 * {@link ReferenceDataService#createContrainteAdHoc} validation for the
 * AFFINITE / INCOMPATIBILITE contradiction (issue #80): a pair declared on
 * both sides must be refused at entry time with an explicit message, never
 * silently arbitrated by the score.
 */
@QuarkusTest
class ReferenceDataServiceContrainteAdHocTest {

    private static final String PREFIXE = "AFFI-TEST-";

    @Inject
    ReferenceDataService referenceDataService;

    private Animateur premier;
    private Animateur second;
    private Animateur troisieme;

    @BeforeEach
    void creerAnimateurs() {
        premier = animateur(PREFIXE + "A1");
        second = animateur(PREFIXE + "A2");
        troisieme = animateur(PREFIXE + "A3");
    }

    @AfterEach
    void nettoyer() {
        referenceDataService.listContraintesAdHoc().stream()
                .map(ContrainteAdHoc::getId)
                .filter(id -> id.startsWith(PREFIXE))
                .forEach(referenceDataService::deleteContrainteAdHoc);
        for (Animateur animateur : List.of(premier, second, troisieme)) {
            referenceDataService.deleteAnimateur(animateur.getId());
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
