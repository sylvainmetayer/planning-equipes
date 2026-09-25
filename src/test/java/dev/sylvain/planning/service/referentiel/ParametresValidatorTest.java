package dev.sylvain.planning.service.referentiel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import dev.sylvain.planning.domain.ParametresLegaux;
import dev.sylvain.planning.domain.ParametresNotifications;
import dev.sylvain.planning.domain.ParametresQualite;
import dev.sylvain.planning.domain.ParametresSolveur;
import dev.sylvain.planning.service.solve.SolverBudgetBounds;
import java.time.LocalTime;
import org.junit.jupiter.api.Test;

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

    /** Default 15 min and 5 min, at most 1 h and 30 min: the instance the budget tests are read against. */
    private static final SolverBudgetBounds BOUNDS = new SolverBudgetBounds(900, 300, 3600, 1800);

    /** An edition that never saved a budget: every half of a write is a change. */
    private static final ParametresSolveur NOTHING_STORED = new ParametresSolveur();

    /**
     * The solve duration used to live in localStorage, which made it
     * inconsistent from one browser to the next; it is now persisted
     * server-side, with the same shape of CRUD as the other tunable
     * parameters.
     */
    @Test
    void aZeroOrNegativeSolveDurationIsRefused() {
        assertThatIllegalArgumentException()
                .isThrownBy(() ->
                        ParametresValidator.checkParametresSolveur(new ParametresSolveur(0), NOTHING_STORED, BOUNDS));
        assertThatIllegalArgumentException()
                .isThrownBy(() ->
                        ParametresValidator.checkParametresSolveur(new ParametresSolveur(-1), NOTHING_STORED, BOUNDS));
    }

    @Test
    void aPositiveSolveDurationUnderTheCeilingIsAccepted() {
        assertThatCode(() ->
                        ParametresValidator.checkParametresSolveur(new ParametresSolveur(120), NOTHING_STORED, BOUNDS))
                .doesNotThrowAnyException();
        assertThatCode(() ->
                        ParametresValidator.checkParametresSolveur(new ParametresSolveur(3600), NOTHING_STORED, BOUNDS))
                .doesNotThrowAnyException();
    }

    /** Nothing set follows the instance: « Revenir au défaut » must always be accepted. */
    @Test
    void anUnsetBudgetIsAccepted() {
        assertThatCode(() ->
                        ParametresValidator.checkParametresSolveur(new ParametresSolveur(), NOTHING_STORED, BOUNDS))
                .doesNotThrowAnyException();
    }

    /** Refused, not trimmed, and the message names the ceiling so the organiser knows what is allowed. */
    @Test
    void aDurationAboveTheCeilingIsRefusedCitingIt() {
        assertThatIllegalArgumentException()
                .isThrownBy(() ->
                        ParametresValidator.checkParametresSolveur(new ParametresSolveur(3601), NOTHING_STORED, BOUNDS))
                .withMessageContaining("au plus 1 h");
    }

    /**
     * A duration saved before the ceiling was lowered binds nothing it did not
     * change: the mail switch, or a plateau changed on its own, still saves —
     * launches already run the stored value capped, with a warning.
     */
    @Test
    void aStoredValueAboveTheCeilingPassesWhileItIsNotChanged() {
        ParametresSolveur stored = new ParametresSolveur(7200, 2400, false);
        assertThatCode(() -> ParametresValidator.checkParametresSolveur(
                        new ParametresSolveur(7200, 2400, true), stored, BOUNDS))
                .doesNotThrowAnyException();
        assertThatCode(() -> ParametresValidator.checkParametresSolveur(
                        new ParametresSolveur(7200, 600, false), stored, BOUNDS))
                .doesNotThrowAnyException();
        // Changed, even downwards, it is a value entered now: the ceiling holds.
        assertThatIllegalArgumentException()
                .isThrownBy(() -> ParametresValidator.checkParametresSolveur(
                        new ParametresSolveur(5400, 600, false), stored, BOUNDS))
                .withMessageContaining("au plus 1 h");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> ParametresValidator.checkParametresSolveur(
                        new ParametresSolveur(7200, 2000, false), stored, BOUNDS))
                .withMessageContaining("au plus 30 min");
    }

    /** A scenario file's budget is stored as it says, ceilings aside; the other rules still hold. */
    @Test
    void anImportedBudgetIgnoresTheCeilingsButNotTheOtherRules() {
        assertThatCode(() -> ParametresValidator.checkImportedParametresSolveur(
                        new ParametresSolveur(7200, 2400, false), BOUNDS))
                .doesNotThrowAnyException();
        assertThatIllegalArgumentException()
                .isThrownBy(() -> ParametresValidator.checkImportedParametresSolveur(
                        new ParametresSolveur(0, null, false), BOUNDS));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> ParametresValidator.checkImportedParametresSolveur(
                        new ParametresSolveur(600, 700, false), BOUNDS));
    }

    @Test
    void aPlateauAboveItsCeilingIsRefusedCitingIt() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> ParametresValidator.checkParametresSolveur(
                        new ParametresSolveur(3600, 2400, false), NOTHING_STORED, BOUNDS))
                .withMessageContaining("au plus 30 min");
    }

    @Test
    void aPlateauLongerThanTheDurationIsRefused() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> ParametresValidator.checkParametresSolveur(
                        new ParametresSolveur(600, 700, false), NOTHING_STORED, BOUNDS))
                .withMessageContaining("ne peut pas dépasser la durée");
        // Against the instance's default duration when the edition sets none.
        assertThatIllegalArgumentException()
                .isThrownBy(() -> ParametresValidator.checkParametresSolveur(
                        new ParametresSolveur(null, 1000, false), NOTHING_STORED, BOUNDS));
    }

    @Test
    void aZeroPlateauMeansNeverAndANegativeOneIsRefused() {
        assertThatCode(() -> ParametresValidator.checkParametresSolveur(
                        new ParametresSolveur(600, 0, false), NOTHING_STORED, BOUNDS))
                .doesNotThrowAnyException();
        assertThatIllegalArgumentException()
                .isThrownBy(() -> ParametresValidator.checkParametresSolveur(
                        new ParametresSolveur(600, -1, false), NOTHING_STORED, BOUNDS));
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
        assertThatIllegalArgumentException().isThrownBy(() -> ParametresValidator.checkConstraintWeight(-3));
    }

    @Test
    void aConstraintWeightAboveTheCeilingIsRefused() {
        assertThatIllegalArgumentException()
                .isThrownBy(
                        () -> ParametresValidator.checkConstraintWeight(ParametresValidator.CONSTRAINT_WEIGHT_MAX + 1));
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

    /**
     * The break length is a floor of ordre public, the mirror image of the two
     * weekly ceilings: more is the organiser's to give, less is not
     * (issue #592). One duration now, floored at the adult's twenty minutes
     * (ADR 0048).
     */
    @Test
    void aBreakShorterThanTheLegalFloorIsRefused() {
        ParametresLegaux tropCourt = new ParametresLegaux();
        tropCourt.setDureePauseMinutes(15);
        assertThatIllegalArgumentException()
                .isThrownBy(() -> ParametresValidator.checkParametresLegaux(tropCourt))
                .withMessageContaining("L3121-16");
    }

    @Test
    void aBreakLongerThanTheLegalFloorIsFree() {
        ParametresLegaux genereux = new ParametresLegaux();
        genereux.setDureePauseMinutes(45);
        assertThatCode(() -> ParametresValidator.checkParametresLegaux(genereux))
                .doesNotThrowAnyException();
    }

    /**
     * Between the two floors, the edition's value stands for adults and the
     * thirty minutes of art. L3162-3 are applied to minors at read time rather
     * than refused: twenty-five is a lawful choice, and forcing it up to thirty
     * for everybody would be the application legislating.
     */
    @Test
    void aBreakBetweenTheTwoFloorsIsAcceptedAndRaisedForMinorsOnly() {
        ParametresLegaux entreLesDeux = new ParametresLegaux();
        entreLesDeux.setDureePauseMinutes(25);
        assertThatCode(() -> ParametresValidator.checkParametresLegaux(entreLesDeux))
                .doesNotThrowAnyException();
        assertThat(entreLesDeux.dureePauseMinutes(false)).isEqualTo(25);
        assertThat(entreLesDeux.dureePauseMinutes(true)).isEqualTo(30);
    }

    @Test
    void aConstraintWeightInsideTheRangeIsAccepted() {
        assertThatCode(() -> ParametresValidator.checkConstraintWeight(1)).doesNotThrowAnyException();
        assertThatCode(() -> ParametresValidator.checkConstraintWeight(5)).doesNotThrowAnyException();
        assertThatCode(() -> ParametresValidator.checkConstraintWeight(ParametresValidator.CONSTRAINT_WEIGHT_MAX))
                .doesNotThrowAnyException();
    }

    @Test
    void aWalkingSettingIsJudgedAtTheTwoDecimalsItIsStoredWith() {
        ParametresQualite defaut = new ParametresQualite();
        // 0.001 km/h would be stored as 0.00 and refused on the next save.
        assertThatIllegalArgumentException()
                .isThrownBy(() -> ParametresValidator.checkParametresQualite(defaut.withTrajet(0.001, 1.3, 5)));
        // 0.995 is stored as 1.00, and 0.994 as 0.99, under the floor of 1.
        assertThatCode(() -> ParametresValidator.checkParametresQualite(defaut.withTrajet(4.0, 0.995, 5)))
                .doesNotThrowAnyException();
        assertThatIllegalArgumentException()
                .isThrownBy(() -> ParametresValidator.checkParametresQualite(defaut.withTrajet(4.0, 0.994, 5)));
        assertThatCode(() -> ParametresValidator.checkParametresQualite(defaut.withTrajet(0.005, 1.3, 5)))
                .doesNotThrowAnyException();
    }
}
