package dev.sylvain.planning.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;

import dev.sylvain.planning.domain.ParametresLegaux;
import dev.sylvain.planning.domain.ParametresSolveur;

/**
 * Finding C1 of the HR compliance audit: `updateParametresLegaux` only checked
 * for positivity, so an administrator could save 100 h a week without the
 * slightest warning — and the solver would then produce a "valid" planning (a
 * zero hard score) that was plainly illegal.
 *
 * <p>The rules are pure functions: an accepted value is a call that returns.
 * These tests used to read a {@code NullPointerException} — the service was
 * built without a repository — as proof that the validation had passed.</p>
 */
class ParametresValidatorTest {

    @Test
    void plusDe48HeuresPourUnMajeurEstRefuse() {
        // Art. L3121-20, a public-order provision.
        assertThatIllegalArgumentException()
                .isThrownBy(() -> ParametresValidator.checkParametresLegaux(new ParametresLegaux(100 * 60, 35 * 60)))
                .withMessageContaining("L3121-20");
    }

    @Test
    void plusDe35HeuresPourUnMineurEstRefuse() {
        // Art. L3162-1.
        assertThatIllegalArgumentException()
                .isThrownBy(() -> ParametresValidator.checkParametresLegaux(new ParametresLegaux(48 * 60, 40 * 60)))
                .withMessageContaining("L3162-1");
    }

    @Test
    void uneValeurLegaleNulleOuNegativeEstRefusee() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> ParametresValidator.checkParametresLegaux(new ParametresLegaux(0, 35 * 60)));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> ParametresValidator.checkParametresLegaux(new ParametresLegaux(48 * 60, -1)));
    }

    @Test
    void uneValeurInferieureAuPlafondLegalResteLibre() {
        // Stricter than the law: nothing must stand in its way.
        assertThatCode(() -> ParametresValidator.checkParametresLegaux(new ParametresLegaux(35 * 60, 20 * 60)))
                .doesNotThrowAnyException();
    }

    /**
     * The solve duration used to live in localStorage, which made it
     * inconsistent from one browser to the next; it is now persisted
     * server-side, with the same shape of CRUD as the other tunable
     * parameters.
     */
    @Test
    void uneDureeDeResolutionNulleOuNegativeEstRefusee() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> ParametresValidator.checkParametresSolveur(new ParametresSolveur(0)));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> ParametresValidator.checkParametresSolveur(new ParametresSolveur(-1)));
    }

    @Test
    void uneDureeDeResolutionPositiveEstAcceptee() {
        assertThatCode(() -> ParametresValidator.checkParametresSolveur(new ParametresSolveur(120)))
                .doesNotThrowAnyException();
    }
}
