package dev.sylvain.planning.service.referentiel;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;

import java.time.LocalTime;

import dev.sylvain.planning.domain.ParametresLegaux;
import dev.sylvain.planning.domain.ParametresNotifications;
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

    /**
     * A weight of zero would switch the rule off in fact while the Contraintes
     * screen kept showing it as active — and, for a legal rule, without the
     * confirmation that protects it. Disabling goes through the toggle.
     */
    @Test
    void aConstraintWeightBelowOneIsRefused() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> ParametresValidator.checkConstraintWeight(0))
                .withMessageContaining("désactivez-la");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> ParametresValidator.checkConstraintWeight(-3));
    }

    @Test
    void aConstraintWeightAboveTheCeilingIsRefused() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> ParametresValidator.checkConstraintWeight(
                        ParametresValidator.CONSTRAINT_WEIGHT_MAX + 1));
    }

    /**
     * The failure this bound exists for: a sending time the hourly job can
     * never fall inside produces no reminder at all, and nothing on the screen
     * says so. Accepting it would let an organiser configure the reminders,
     * believe them on, and send nothing all week.
     */
    @Test
    void aSendingTimeTheSchedulerCannotHonourIsRefused() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> ParametresValidator.checkParametresNotifications(
                        new ParametresNotifications(true, LocalTime.of(23, 30), 72, 3)))
                .withMessageContaining("une fois par heure");
    }

    @Test
    void theLatestHonourableSendingTimeIsAccepted() {
        assertThatCode(() -> ParametresValidator.checkParametresNotifications(
                new ParametresNotifications(true, ParametresNotifications.HEURE_RAPPEL_VEILLE_MAX, 72, 3)))
                .doesNotThrowAnyException();
        assertThatCode(() -> ParametresValidator.checkParametresNotifications(new ParametresNotifications()))
                .doesNotThrowAnyException();
    }

    @Test
    void aReminderDelayOfZeroIsRefused() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> ParametresValidator.checkParametresNotifications(
                        new ParametresNotifications(true, LocalTime.of(18, 0), 0, 3)));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> ParametresValidator.checkParametresNotifications(
                        new ParametresNotifications(true, LocalTime.of(18, 0), 72, 0)));
    }

    @Test
    void aConstraintWeightInsideTheRangeIsAccepted() {
        assertThatCode(() -> ParametresValidator.checkConstraintWeight(1)).doesNotThrowAnyException();
        assertThatCode(() -> ParametresValidator.checkConstraintWeight(5)).doesNotThrowAnyException();
        assertThatCode(() -> ParametresValidator.checkConstraintWeight(ParametresValidator.CONSTRAINT_WEIGHT_MAX))
                .doesNotThrowAnyException();
    }
}
