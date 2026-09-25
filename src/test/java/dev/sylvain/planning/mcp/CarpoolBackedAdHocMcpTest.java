package dev.sylvain.planning.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.ContrainteAdHoc;
import dev.sylvain.planning.domain.DemandeCoequipier;
import dev.sylvain.planning.domain.NatureCoequipier;
import dev.sylvain.planning.domain.StatutDemandeCoequipier;
import dev.sylvain.planning.service.BusinessError;
import dev.sylvain.planning.service.espace.TeammateDeclarationRepository;
import dev.sylvain.planning.service.espace.TeammateRequestService;
import dev.sylvain.planning.service.referentiel.ContrainteAdHocService;
import dev.sylvain.planning.service.referentiel.ReferenceDataService;
import io.quarkiverse.mcp.server.ToolCallException;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@code supprimer_contrainte_ad_hoc} on a grouped arrival a validated
 * covoiturage stands behind: the assistant reads the same sentence the
 * Ajustements manuels screen does, through the same service call — and the
 * exception is still there.
 */
@QuarkusTest
class CarpoolBackedAdHocMcpTest {

    @Inject
    ParametresMcpTools tools;

    @Inject
    ReferenceDataService referenceData;

    @Inject
    TeammateDeclarationRepository demandes;

    @Inject
    TeammateRequestService carpools;

    private final List<String> animateurs = new ArrayList<>();

    private String demande;

    private String contrainte;

    @BeforeEach
    void validateACar() {
        for (String label : List.of("MCPCOV-A", "MCPCOV-B")) {
            animateurs.add(referenceData
                    .createAnimateur(new Animateur(null, label, label, LocalDate.of(1990, 1, 1), false))
                    .getId());
        }
        demande = UUID.randomUUID().toString();
        demandes.replacePending(new DemandeCoequipier(
                demande,
                animateurs.get(0),
                NatureCoequipier.COVOITURAGE,
                List.of(animateurs.get(1)),
                StatutDemandeCoequipier.EN_ATTENTE,
                null,
                Instant.now(),
                null,
                null));
        contrainte = carpools.validate(demande).request().contrainteId();
    }

    @AfterEach
    void cleanUp() {
        animateurs.forEach(referenceData::deleteAnimateur);
        animateurs.clear();
        if (referenceData.listContraintesAdHoc().stream()
                .anyMatch(c -> c.getId().equals(contrainte))) {
            referenceData.deleteContrainteAdHoc(contrainte);
        }
    }

    @Test
    void deletingACarBackedGroupedArrivalIsAToolErrorCarryingTheSentence() {
        assertThatThrownBy(() -> tools.deleteContrainteAdHoc(contrainte, null))
                .isInstanceOf(ToolCallException.class)
                .hasCauseInstanceOf(BusinessError.Conflict.class)
                .hasMessageContaining(ContrainteAdHocService.CARPOOL_BACKED);
        assertThat(referenceData.listContraintesAdHoc())
                .filteredOn(c -> c.getId().equals(contrainte))
                .singleElement()
                .extracting(ContrainteAdHoc::isIssueDeCovoiturage)
                .isEqualTo(true);
    }

    @Test
    void onceCancelledFromTheTabNothingIsLeftToDelete() {
        carpools.cancel(demande, null);

        assertThat(referenceData.listContraintesAdHoc())
                .noneMatch(c -> c.getId().equals(contrainte));
    }
}
