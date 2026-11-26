package dev.sylvain.planning.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.sylvain.planning.domain.Edition;
import dev.sylvain.planning.domain.ParametresSolveur;
import dev.sylvain.planning.service.EditionContext;
import dev.sylvain.planning.service.edition.EditionService;
import dev.sylvain.planning.service.referentiel.ReferenceDataService;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@code modifier_parametres_solveur} keeps the half it is not given — and a
 * kept duration above a ceiling lowered since must not refuse the half it is
 * given (ADR 0051: such a value runs capped, it is not the organiser's doing).
 */
@QuarkusTest
class ParametresSolveurMcpToolsTest {

    @Inject
    ParametresMcpTools tools;

    @Inject
    ReferenceDataService referenceData;

    @Inject
    EditionService editions;

    @Inject
    EditionContext editionContext;

    private String edition;

    @BeforeEach
    void storeADurationAboveTheCeiling() {
        edition = editions.create(new Edition(null, "Budget au-dessus du plafond", false, null))
                .getId();
        // The scenario import path: the one write that stores above the ceiling.
        editionContext.executeIn(
                edition, () -> referenceData.importParametresSolveur(new ParametresSolveur(7200, null, false)));
    }

    @AfterEach
    void dropTheEdition() {
        editions.delete(edition);
    }

    @Test
    void aPlateauAloneSavesOverAKeptDurationAboveTheCeiling() {
        var view = instance(null, 600);

        assertThat(view.dureeResolutionSecondes()).isEqualTo(7200);
        assertThat(view.plateauSecondes()).isEqualTo(600);
    }

    @Test
    void aDurationChangedAboveTheCeilingIsStillRefused() {
        // A refusal comes back as a tool error carrying its sentence (@RefusMetier).
        assertThatThrownBy(() -> instance(5400, null)).hasMessageContaining("au plus 1 h");
    }

    @Test
    void aSectionIsReadByItsName() {
        var view = tools.getParametres("instance", edition);

        assertThat(view.section()).isEqualTo("instance");
        assertThat(view.parametres()).isInstanceOf(ParametresMcpTools.ParametresSolveurView.class);
    }

    @Test
    void anUnknownSectionAndAnArgumentOfAnotherSectionAreRefused() {
        assertThatThrownBy(() -> tools.getParametres("solveur", edition)).hasMessageContaining("Section inconnue");
        // dureePauseMinutes is a legal field, not an instance one.
        assertThatThrownBy(() -> tools.updateParametres(
                        "instance",
                        null,
                        null,
                        null,
                        null,
                        null,
                        25,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        600,
                        null,
                        null,
                        null,
                        null,
                        null,
                        edition))
                .hasMessageContaining("n'est pas de la section");
    }

    /** {@code modifier_parametres} on the instance section, as the tool is called. */
    private ParametresMcpTools.ParametresSolveurView instance(Integer duree, Integer plateau) {
        return (ParametresMcpTools.ParametresSolveurView) tools.updateParametres(
                        "instance",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        duree,
                        plateau,
                        null,
                        null,
                        null,
                        null,
                        edition)
                .parametres();
    }
}
