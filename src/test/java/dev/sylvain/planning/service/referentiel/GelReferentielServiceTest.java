package dev.sylvain.planning.service.referentiel;

import static dev.sylvain.planning.service.referentiel.ReferentialFamily.COMPETENCES;
import static dev.sylvain.planning.service.referentiel.ReferentialFamily.CRENEAUX;
import static dev.sylvain.planning.service.referentiel.ReferentialFamily.STANDS;
import static dev.sylvain.planning.service.referentiel.ReferentialFamily.TYPOLOGIES_EMPLACEMENTS;
import static org.assertj.core.api.Assertions.assertThat;

import dev.sylvain.planning.domain.EditionPhase;
import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** What each phase of the edition freezes (ADR 0115), without a container. */
class GelReferentielServiceTest {

    private static Set<ReferentialFamily> frozen(EditionPhase phase, boolean debloquee) {
        return GelReferentielService.frozenFamilies(phase, debloquee);
    }

    @Test
    void eachPhaseFreezesItsFamilies() {
        assertThat(frozen(EditionPhase.PREPARATION, false)).isEmpty();
        assertThat(frozen(EditionPhase.CALCUL, false)).containsExactlyInAnyOrder(ReferentialFamily.values());
        assertThat(frozen(EditionPhase.TERMINEE, false)).containsExactlyInAnyOrder(ReferentialFamily.values());
        assertThat(frozen(EditionPhase.PUBLIEE, false))
                .as("the structure, not the people's competences")
                .containsExactlyInAnyOrder(STANDS, CRENEAUX, TYPOLOGIES_EMPLACEMENTS);
        assertThat(frozen(EditionPhase.PUBLIEE, true))
                .as("an unblocked structure")
                .isEmpty();
    }

    @Test
    void theRefusalSaysHowToReopen() {
        assertThat(GelReferentielService.message(EditionPhase.CALCUL, EnumSet.of(STANDS)))
                .contains("phase « Calcul »", "« Stands »", "Repassez-la en Préparation");
        assertThat(GelReferentielService.message(EditionPhase.PUBLIEE, EnumSet.of(CRENEAUX)))
                .contains("publiée", "« Créneaux »", "Débloquez la structure");
        assertThat(GelReferentielService.message(EditionPhase.TERMINEE, EnumSet.of(COMPETENCES)))
                .contains("terminée", "Changez sa phase");
    }
}
