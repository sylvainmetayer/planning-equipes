package dev.sylvain.planning.service;

import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;

import dev.sylvain.planning.domain.ParametresSolveur;

/**
 * The Débogage tab's solver duration used to live in localStorage, which
 * looked inconsistent across browsers; it is now persisted server-side,
 * same CRUD shape as the other admin-configurable parameters. Validation is
 * checked before any write, so this can be built without a repository (see
 * the class javadoc of {@link ReferenceDataService}).
 */
class ReferenceDataServiceParametresSolveurTest {

    private final ReferenceDataService service = new ReferenceDataService();

    @Test
    void uneValeurNulleOuNegativeEstRefusee() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.updateParametresSolveur(new ParametresSolveur(0)));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.updateParametresSolveur(new ParametresSolveur(-1)));
    }
}
