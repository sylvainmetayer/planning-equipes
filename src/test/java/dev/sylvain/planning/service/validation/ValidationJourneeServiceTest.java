package dev.sylvain.planning.service.validation;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.sylvain.planning.domain.PastHorizon;
import dev.sylvain.planning.domain.TypeVerrouillage;
import dev.sylvain.planning.domain.ValidationJournee;
import dev.sylvain.planning.domain.VerrouillagePlanning;
import dev.sylvain.planning.service.BusinessError;
import dev.sylvain.planning.service.referentiel.ReferenceDataService;
import dev.sylvain.planning.service.validation.ValidationJourneeService.DemandeValidation;
import dev.sylvain.planning.service.validation.ValidationJourneeService.KeepReason;
import dev.sylvain.planning.service.validation.ValidationJourneeService.KeptReading;
import dev.sylvain.planning.service.validation.ValidationJourneeService.ReadingsOutcome;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The one rule where the review mark and the lock meet: a solve that
 * recomputed an accepted day withdraws the reading, <b>unless</b> the day was
 * frozen — in the past or under a {@code JOUR} lock, the solver could not have
 * moved anything there, so the reading still describes what is in place — or a
 * solve started from the plan in place gave it back unchanged.
 *
 * <p>Exercised on the service rather than through a real solve on purpose: what
 * is under test is which readings survive which days moving, not whether the
 * solver happens to move one. {@code PlanningServiceIncrementalTest} pins the
 * other half — which days a solve is said to have moved.</p>
 */
@QuarkusTest
class ValidationJourneeServiceTest {

    private static final LocalDate JOUR = LocalDate.of(2026, 7, 8);

    @Inject
    ValidationJourneeService validationService;

    @Inject
    ReferenceDataService referenceDataService;

    @BeforeEach
    void seedTheSampleEdition() {
        String sample = given().when()
                .get("/api/planning/sample?name=scenario.yml")
                .then()
                .statusCode(200)
                .extract()
                .asString();
        given().contentType("application/json")
                .body(sample)
                .when()
                .post("/api/solve?seconds=3")
                .then()
                .statusCode(200);
    }

    @AfterEach
    void resetDatabase() {
        given().when().post("/api/planning/reset").then().statusCode(200);
    }

    @Test
    void aMovedDayLosesItsReading() {
        validationService.accept(new DemandeValidation(JOUR, null, false));

        assertThat(validationService.withdrawAfterSolve(Set.of(JOUR), Set.of(), false))
                .isEqualTo(new ReadingsOutcome(1, 0, 0, 0));
        assertThat(validationService.list()).isEmpty();
    }

    @Test
    void aDayThatDidNotMoveKeepsItsReadingAndSaysSo() {
        validationService.accept(new DemandeValidation(JOUR, null, false));

        assertThat(validationService.withdrawAfterSolve(Set.of(JOUR.plusDays(1)), Set.of(), false))
                .isEqualTo(new ReadingsOutcome(0, 0, 0, 1));
        assertThat(validationService.list()).hasSize(1);
    }

    @Test
    void aFrozenDayKeepsItsReadingEvenWhenSomethingMoved() {
        validationService.accept(new DemandeValidation(JOUR, null, true));

        assertThat(validationService.withdrawAfterSolve(Set.of(JOUR), Set.of(), false))
                .isEqualTo(new ReadingsOutcome(0, 0, 1, 0));
        assertThat(validationService.list()).hasSize(1);
    }

    /**
     * A lock on a stand freezes part of a day; the rest of it is exactly what
     * nobody has read since. Only a {@code JOUR} lock covers the whole reading.
     */
    @Test
    void aLockOnSomethingSmallerThanTheDayDoesNotSaveTheReading() {
        VerrouillagePlanning surLeStand = new VerrouillagePlanning();
        surLeStand.setType(TypeVerrouillage.STAND);
        surLeStand.setStandId("STAND-STRAT");
        referenceDataService.createVerrouillage(surLeStand);
        validationService.accept(new DemandeValidation(JOUR, null, false));

        assertThat(validationService
                        .withdrawAfterSolve(Set.of(JOUR), Set.of(), false)
                        .withdrawn())
                .isEqualTo(1);
        assertThat(validationService.list()).isEmpty();
    }

    @Test
    void nothingIsWithdrawnWhenNoDayMoved() {
        validationService.accept(new DemandeValidation(JOUR, null, false));

        assertThat(validationService
                        .withdrawAfterSolve(Set.of(), Set.of(), false)
                        .withdrawn())
                .isZero();
        assertThat(validationService.withdrawAfterSolve(null, null, false).withdrawn())
                .isZero();
        assertThat(validationService.list()).hasSize(1);
    }

    /**
     * « Recommencer de zéro » throws away the plan that was read: a day it
     * recomputed loses its reading even when it landed on the same crew.
     */
    @Test
    void aColdStartWithdrawsTheReadingOfADayThatDidNotMove() {
        validationService.accept(new DemandeValidation(JOUR, null, false));

        assertThat(validationService.withdrawAfterSolve(Set.of(), Set.of(), true))
                .isEqualTo(new ReadingsOutcome(1, 0, 0, 0));
        assertThat(validationService.list()).isEmpty();
    }

    /** What a cold start never recomputes keeps its reading: a day in the frozen past… */
    @Test
    void aColdStartKeepsTheReadingOfAPastDay() {
        validationService.accept(new DemandeValidation(JOUR, null, false));

        assertThat(validationService.withdrawAfterSolve(Set.of(JOUR), Set.of(JOUR), true))
                .isEqualTo(new ReadingsOutcome(0, 1, 0, 0));
        assertThat(validationService.list()).hasSize(1);
    }

    /** …and a day under a {@code JOUR} lock. */
    @Test
    void aColdStartKeepsTheReadingOfALockedDay() {
        validationService.accept(new DemandeValidation(JOUR, null, true));

        assertThat(validationService.withdrawAfterSolve(Set.of(JOUR), Set.of(), true))
                .isEqualTo(new ReadingsOutcome(0, 0, 1, 0));
        assertThat(validationService.list()).hasSize(1);
    }

    /**
     * Asked before the solve, the confirmation names the days the solve will
     * keep. A day is past once its last timeslot has started — 14:00 here.
     */
    @Test
    void theReadingsAColdStartWouldKeepAreThePastAndTheLockedOnes() {
        validationService.accept(new DemandeValidation(JOUR, null, false));

        assertThat(validationService.keptByColdStart(null)).isEmpty();
        assertThat(validationService.keptByColdStart(new PastHorizon(JOUR, LocalTime.of(10, 0))))
                .isEmpty();
        assertThat(validationService.keptByColdStart(new PastHorizon(JOUR, LocalTime.of(14, 0))))
                .containsExactly(new KeptReading(JOUR, KeepReason.PASSE));

        validationService.accept(new DemandeValidation(JOUR, null, true));

        assertThat(validationService.keptByColdStart(null)).containsExactly(new KeptReading(JOUR, KeepReason.VERROU));
    }

    @Test
    void anEditionWithoutReadingsHasNothingToReport() {
        assertThat(validationService.withdrawAfterSolve(Set.of(JOUR), Set.of(), true))
                .isEqualTo(ReadingsOutcome.NONE);
        assertThat(validationService.keptByColdStart(null)).isEmpty();
    }

    /** Already frozen is not a lock this validation laid down, and it says so. */
    @Test
    void askingForALockOnAnAlreadyFrozenDayLaysNoneDown() {
        VerrouillagePlanning verrouillage = new VerrouillagePlanning();
        verrouillage.setType(TypeVerrouillage.JOUR);
        verrouillage.setJour(JOUR);
        referenceDataService.createVerrouillage(verrouillage);

        assertThat(validationService
                        .accept(new DemandeValidation(JOUR, null, true))
                        .verrouPose())
                .isFalse();
        assertThat(referenceDataService.listVerrouillages()).hasSize(1);
    }

    @Test
    void aCommentLongerThanTheFieldAllowsIsRefused() {
        String trop = "x".repeat(ValidationJourneeService.COMMENTAIRE_MAX + 1);

        assertThatThrownBy(() -> validationService.accept(new DemandeValidation(JOUR, trop, false)))
                .isInstanceOf(BusinessError.Invalid.class)
                .hasMessageContaining("trop long");
    }

    /** A blank comment is no comment: the row must not carry an empty string. */
    @Test
    void aBlankCommentIsStoredAsNone() {
        ValidationJournee validation = validationService
                .accept(new DemandeValidation(JOUR, "   ", false))
                .validation();

        assertThat(validation.commentaire()).isNull();
    }

    @Test
    void aMissingDayIsRefused() {
        assertThatThrownBy(() -> validationService.accept(new DemandeValidation(null, null, false)))
                .isInstanceOf(BusinessError.Invalid.class);
        assertThatThrownBy(() -> validationService.accept(null)).isInstanceOf(BusinessError.Invalid.class);
    }

    @Test
    void withdrawingAnUnknownReadingIsReportedRatherThanAccepted() {
        assertThatThrownBy(() -> validationService.withdraw("inconnu")).isInstanceOf(BusinessError.NotFound.class);
        assertThatThrownBy(() -> validationService.withdraw(" ")).isInstanceOf(BusinessError.Invalid.class);
    }
}
