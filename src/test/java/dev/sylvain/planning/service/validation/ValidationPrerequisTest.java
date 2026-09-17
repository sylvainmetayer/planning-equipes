package dev.sylvain.planning.service.validation;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.domain.ValidationJournee;
import dev.sylvain.planning.service.analyse.FragiliteAnalyzer.AnimateurFragilite;
import dev.sylvain.planning.service.analyse.FragiliteAnalyzer.PosteFragile;
import dev.sylvain.planning.service.analyse.FragiliteAnalyzer.RapportFragilite;
import dev.sylvain.planning.service.analyse.PauseAnalyzer.JourneeAnimateurView;
import dev.sylvain.planning.service.analyse.PauseAnalyzer.PauseDueView;
import dev.sylvain.planning.service.analyse.PauseAnalyzer.RapportPauses;
import dev.sylvain.planning.service.analyse.PauseAnalyzer.SequenceView;
import dev.sylvain.planning.service.analyse.PlanningDiagnosticService.ConstraintDiagnostic;
import dev.sylvain.planning.service.analyse.PlanningDiagnosticService.PlanningDiagnostic;
import dev.sylvain.planning.service.analyse.ViolationFormatter.ViolationReference;
import dev.sylvain.planning.service.validation.ValidationPrerequisService.Prerequis;
import dev.sylvain.planning.service.validation.ValidationPrerequisService.PrerequisJournee;
import dev.sylvain.planning.service.validation.ValidationPrerequisService.ProgressionValidations;
import dev.sylvain.planning.solver.ConstraintCatalog;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The two rules the validation panel is built on, on hand-built facts: how far
 * the reading has got, and what the four prerequisites of one day say.
 *
 * <p>No container and no database: both are static functions of the reports
 * three existing screens already produce, which is the point — this screen must
 * never be able to disagree with Problèmes, Pauses or Fragilité about the same
 * day.</p>
 */
class ValidationPrerequisTest {

    private static final LocalDate JOUR = LocalDate.of(2026, 7, 8);
    private static final LocalDate LENDEMAIN = JOUR.plusDays(1);
    /** A hard rule, taken from the catalogue so the test cannot drift from it. */
    private static final String REGLE_DURE =
            ConstraintCatalog.NOMS_DURS.iterator().next();

    /* --------------------------- Progression --------------------------- */

    @Test
    void progressionCountsWholeDaysAgainstTheDaysTheGridHolds() {
        ProgressionValidations progression = ValidationPrerequisService.progression(
                jours(JOUR, LENDEMAIN, JOUR.plusDays(2)), List.of(validation(JOUR), validation(LENDEMAIN)));

        assertThat(progression.journees()).isEqualTo(3);
        assertThat(progression.journeesValidees()).isEqualTo(2);
        assertThat(progression.joursValides()).containsExactly(JOUR, LENDEMAIN);
    }

    /**
     * A validation whose day no longer carries a créneau is left out: the banner
     * reads « N sur 12 » and must never be able to say « 13 sur 12 ».
     */
    @Test
    void aValidationOnADayTheGridNoLongerHoldsIsNotCounted() {
        ProgressionValidations progression =
                ValidationPrerequisService.progression(jours(JOUR), List.of(validation(JOUR), validation(LENDEMAIN)));

        assertThat(progression.journees()).isEqualTo(1);
        assertThat(progression.journeesValidees()).isEqualTo(1);
        assertThat(progression.joursValides()).containsExactly(JOUR);
    }

    @Test
    void anEditionWithoutTimeslotsHasNoProgressionToShow() {
        ProgressionValidations progression = ValidationPrerequisService.progression(Set.of(), List.of());

        assertThat(progression.journees()).isZero();
        assertThat(progression.journeesValidees()).isZero();
    }

    /* --------------------------- Prerequisites -------------------------- */

    @Test
    void aCleanDaySatisfiesAllFourPrerequisites() {
        PrerequisJournee lu = ValidationPrerequisService.assemble(
                JOUR, null, planWithSeats(true), diagnostic(), pauses(true), fragilite(false));

        assertThat(lu.tousSatisfaits()).isTrue();
        assertThat(lu.validee()).isFalse();
        assertThat(lu.prerequis()).allMatch(Prerequis::connu).allMatch(Prerequis::satisfait);
    }

    @Test
    void anEmptySeatOfTheDayIsReportedAndOnlyForThatDay() {
        PrerequisJournee lu = ValidationPrerequisService.assemble(
                JOUR, null, planWithSeats(false), diagnostic(), pauses(true), fragilite(false));

        assertThat(prerequis(lu, ValidationPrerequisService.SIEGES_VIDES).nombre())
                .isEqualTo(1);
        assertThat(lu.tousSatisfaits()).isFalse();

        PrerequisJournee lendemain = ValidationPrerequisService.assemble(
                LENDEMAIN, null, planWithSeats(false), diagnostic(), pauses(true), fragilite(false));
        assertThat(prerequis(lendemain, ValidationPrerequisService.SIEGES_VIDES).nombre())
                .isZero();
    }

    @Test
    void hardViolationsArePlacedOnTheDayOfTheTimeslotTheyName() {
        PlanningEvenement plan = planWithSeats(true);
        PlanningDiagnostic diagnostic = diagnostic(new ViolationReference("A1 — J1", "A1", "STAND-STRAT", 1L));

        assertThat(prerequis(
                                ValidationPrerequisService.assemble(
                                        JOUR, null, plan, diagnostic, pauses(true), fragilite(false)),
                                ValidationPrerequisService.ECARTS_DURS)
                        .nombre())
                .isEqualTo(1);
        assertThat(prerequis(
                                ValidationPrerequisService.assemble(
                                        LENDEMAIN, null, plan, diagnostic, pauses(true), fragilite(false)),
                                ValidationPrerequisService.ECARTS_DURS)
                        .nombre())
                .isZero();
    }

    /**
     * The analysis lives in memory and a restart empties it. « Non vérifié » is
     * then the honest answer: claiming zero would have the panel acknowledge a
     * measurement nobody made.
     */
    @Test
    void withoutAnAnalysisTheHardViolationLineSaysItDoesNotKnow() {
        PrerequisJournee lu = ValidationPrerequisService.assemble(
                JOUR, null, planWithSeats(true), null, pauses(true), fragilite(false));

        Prerequis ecarts = prerequis(lu, ValidationPrerequisService.ECARTS_DURS);
        assertThat(ecarts.connu()).isFalse();
        assertThat(ecarts.satisfait()).isFalse();
        assertThat(lu.tousSatisfaits()).isFalse();
    }

    @Test
    void aBreakWithNobodyToTakeTheRelayIsReported() {
        PrerequisJournee lu = ValidationPrerequisService.assemble(
                JOUR, null, planWithSeats(true), diagnostic(), pauses(false), fragilite(false));

        assertThat(prerequis(lu, ValidationPrerequisService.PAUSES_NON_RELAYEES).nombre())
                .isEqualTo(1);
    }

    @Test
    void anIrreplaceableSeatOfTheDayIsReported() {
        PrerequisJournee lu = ValidationPrerequisService.assemble(
                JOUR, null, planWithSeats(true), diagnostic(), pauses(true), fragilite(true));

        assertThat(prerequis(lu, ValidationPrerequisService.POSTES_IRREMPLACABLES)
                        .nombre())
                .isEqualTo(1);
    }

    @Test
    void anAlreadyAcceptedDayCarriesItsReadingBack() {
        ValidationJournee validation = new ValidationJournee(
                "V1", JOUR, Instant.parse("2026-07-01T10:00:00Z"), "admin", "Vu avec le responsable");

        PrerequisJournee lu = ValidationPrerequisService.assemble(
                JOUR, validation, planWithSeats(true), diagnostic(), pauses(true), fragilite(false));

        assertThat(lu.validee()).isTrue();
        assertThat(lu.valideeLe()).isEqualTo(validation.valideLe());
        assertThat(lu.commentaire()).isEqualTo("Vu avec le responsable");
    }

    /* ------------------------------ Fixtures ---------------------------- */

    private static Prerequis prerequis(PrerequisJournee lu, String code) {
        return lu.prerequis().stream()
                .filter(candidat -> candidat.code().equals(code))
                .findFirst()
                .orElseThrow(() -> new AssertionError("prerequisite " + code + " is missing"));
    }

    private static Set<LocalDate> jours(LocalDate... dates) {
        return new LinkedHashSet<>(List.of(dates));
    }

    private static ValidationJournee validation(LocalDate jour) {
        return new ValidationJournee("V-" + jour, jour, Instant.EPOCH, "admin", null);
    }

    /** One stand on one timeslot of {@link #JOUR}, its single seat held or not. */
    private static PlanningEvenement planWithSeats(boolean pourvu) {
        Creneau creneau = new Creneau(1L, 1, JOUR, LocalTime.of(9, 0), LocalTime.of(13, 0));
        Stand stand = new Stand("STAND-STRAT", "Stand stratégie", Set.of(), 1, 1, false);
        PosteAffectation poste = new PosteAffectation("P1", stand, creneau);
        if (pourvu) {
            poste.setAnimateur(new Animateur("A1", "Alice", "Martin", LocalDate.of(1990, 1, 1), false));
        }
        PlanningEvenement plan = new PlanningEvenement();
        plan.setPostes(List.of(poste));
        return plan;
    }

    private static PlanningDiagnostic diagnostic(ViolationReference... references) {
        ConstraintDiagnostic contrainte = new ConstraintDiagnostic(
                REGLE_DURE, "-1hard/0medium/0soft", references.length, List.of(), null, null, List.of(references));
        return new PlanningDiagnostic(
                "0hard/0medium/0soft", 0, List.of(contrainte), null, 0, List.of(), null, 0, 0, List.of());
    }

    /** One animateur-day owing one break, relayed or not. */
    private static RapportPauses pauses(boolean relais) {
        PauseDueView pause = new PauseDueView(
                LocalTime.of(11, 0),
                LocalTime.of(11, 20),
                LocalTime.of(12, 0),
                20,
                "STAND-STRAT",
                "Stand stratégie",
                1L,
                List.of(),
                relais,
                false);
        JourneeAnimateurView journee = new JourneeAnimateurView(
                "A1",
                "Alice Martin",
                false,
                JOUR,
                1,
                List.of(new SequenceView(LocalTime.of(9, 0), LocalTime.of(13, 0), 240, List.of(pause))),
                List.of(),
                List.of());
        return new RapportPauses(false, 1, 1, relais ? 0 : 1, 0, 0, List.of(journee), null);
    }

    /** One animateur holding one seat of {@link #JOUR}, irreplaceable or not. */
    private static RapportFragilite fragilite(boolean irremplacable) {
        PosteFragile poste = new PosteFragile(
                "STAND-STRAT",
                "Stand stratégie",
                1L,
                JOUR,
                1,
                LocalTime.of(9, 0),
                LocalTime.of(13, 0),
                1,
                false,
                1,
                1,
                1,
                irremplacable ? 0 : 2,
                irremplacable);
        AnimateurFragilite animateur = new AnimateurFragilite(
                "A1", "Alice Martin", false, 1, 0, irremplacable ? 1 : 0, 0, null, List.of(poste), 0);
        return new RapportFragilite(
                List.of(animateur), List.of(), 0, 0, 1, 0, irremplacable ? 1 : 0, false, false, null);
    }
}
