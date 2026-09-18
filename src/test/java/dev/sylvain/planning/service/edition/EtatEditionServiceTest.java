package dev.sylvain.planning.service.edition;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.Edition;
import dev.sylvain.planning.domain.OuvertureStand;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.service.analyse.FeasibilityAnalyzer.CauseInfaisabilite;
import dev.sylvain.planning.service.analyse.FeasibilityAnalyzer.FeasibilityReport;
import dev.sylvain.planning.service.analyse.FeasibilityAnalyzer.SeveriteInfaisabilite;
import dev.sylvain.planning.service.analyse.FeasibilityAnalyzer.TypeCauseInfaisabilite;
import dev.sylvain.planning.service.analyse.OuvertureStandsAnalyzer;
import dev.sylvain.planning.service.analyse.PlanningDiagnosticService.ConstraintDiagnostic;
import dev.sylvain.planning.service.analyse.PlanningDiagnosticService.PlanningDiagnostic;
import dev.sylvain.planning.service.analyse.StaffingAnalyzer;
import dev.sylvain.planning.service.analyse.StaffingAnalyzer.StaffingSummary;
import dev.sylvain.planning.service.edition.EtatEditionService.Facts;
import dev.sylvain.planning.service.edition.EtatEditionView.Statut;
import dev.sylvain.planning.service.publication.ConfirmationPlanningService.SyntheseConfirmations;
import dev.sylvain.planning.service.publication.PlanPublicationService.ApercuPublication;
import dev.sylvain.planning.service.solve.PlanningPersistenceService.PlanningResolution;
import dev.sylvain.planning.service.validation.ValidationPrerequisService.ProgressionValidations;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The rules of the checklist (issue #485), on hand-built facts: the
 * collection is the resource test's business, the decision is this one's.
 */
class EtatEditionServiceTest {

    private static final Edition EDITION = new Edition("2026", "Année 2026", true, null);
    private static final Instant RESOLU_LE = Instant.parse("2026-05-01T10:00:00Z");
    private static final LocalDate JOUR = LocalDate.of(2026, 7, 10);

    /** A hard rule and a medium one, as the catalogue classifies them. */
    private static final String REGLE_DURE = "posteDoitEtrePourvu";

    private static final String REGLE_MEDIUM = "standComplexeAvecReferent";

    @Test
    void anEmptyEditionHasEveryLineToDo() {
        EtatEditionView etat = EtatEditionService.assemble(emptyFacts());

        assertThat(etat.editionId()).isEqualTo("2026");
        assertThat(etat.editionNom()).isEqualTo("Année 2026");
        assertThat(List.of(
                        etat.referentiels().statut(),
                        etat.collecte().statut(),
                        etat.ouvertures().statut(),
                        etat.besoin().statut(),
                        etat.resolution().statut(),
                        etat.problemes().statut(),
                        etat.publication().statut(),
                        etat.confirmations().statut(),
                        etat.foire().statut()))
                .containsOnly(Statut.A_FAIRE);
        assertThat(etat.referentiels().stands()).isZero();
        assertThat(etat.resolution().resolue()).isFalse();
        assertThat(etat.resolution().score()).isNull();
        assertThat(etat.resolution().faisable()).isNull();
        assertThat(etat.publication().jamaisPublie()).isTrue();
    }

    @Test
    void aFilledSolvedPublishedAndAcknowledgedEditionHasEveryLineDone() {
        EtatEditionView etat = EtatEditionService.assemble(filledFacts());

        assertThat(List.of(
                        etat.referentiels().statut(),
                        etat.collecte().statut(),
                        etat.ouvertures().statut(),
                        etat.besoin().statut(),
                        etat.resolution().statut(),
                        etat.problemes().statut(),
                        etat.publication().statut(),
                        etat.confirmations().statut(),
                        etat.foire().statut()))
                .containsOnly(Statut.FAIT);
        assertThat(etat.referentiels().animateurs()).isEqualTo(2);
        assertThat(etat.besoin().minimum()).isEqualTo(1);
        assertThat(etat.besoin().manque()).isZero();
        assertThat(etat.resolution().resoluLe()).isEqualTo(RESOLU_LE);
        assertThat(etat.resolution().score()).isEqualTo("0hard/0medium/-12soft");
        assertThat(etat.resolution().scoreHorsPlancher()).isEqualTo("0hard/0medium/-2soft");
        assertThat(etat.resolution().faisable()).isTrue();
        assertThat(etat.resolution().dataStale()).isFalse();
        assertThat(etat.confirmations().confirmes()).isEqualTo(2);
    }

    @Test
    void aRunningSolveIsReportedOnTheResolutionLineAndNowhereElse() {
        Facts f = filledFacts();
        Facts facts = new Facts(
                f.edition(),
                f.stands(),
                f.animateurs(),
                f.creneaux(),
                f.collecteOuverte(),
                f.declarationsEnAttente(),
                f.declarationsTraitees(),
                f.ouvertures(),
                f.staffing(),
                f.resolution(),
                f.diagnostic(),
                f.lastDataChange(),
                true,
                f.faisabilite(),
                f.publication(),
                f.confirmations(),
                f.foireOuverte(),
                f.demandesEnAttente(),
                new ProgressionValidations(0, 0, List.of()));

        EtatEditionView etat = EtatEditionService.assemble(facts);

        assertThat(etat.resolution().solveEnCours()).isTrue();
        assertThat(etat.resolution().statut()).isEqualTo(Statut.ATTENTION);
        // Publishing is refused while a solve runs, and the count it would show
        // is read off a plan about to be rewritten: the line waits too, rather
        // than inviting a click the server turns down.
        assertThat(etat.publication().statut()).isEqualTo(Statut.ATTENTION);
    }

    /**
     * The rule analysis lives in memory: after a restart nothing has measured
     * the rules until a solve or a visit to Contraintes, and « aucun problème
     * signalé » there acknowledges a measurement that never ran. The capacity
     * causes, recomputed on every call, are counted either way.
     */
    @Test
    void withoutARuleAnalysisTheProblemsLineSaysItHasNotMeasuredAnything() {
        Facts f = filledFacts();
        Facts facts = new Facts(
                f.edition(),
                f.stands(),
                f.animateurs(),
                f.creneaux(),
                f.collecteOuverte(),
                f.declarationsEnAttente(),
                f.declarationsTraitees(),
                f.ouvertures(),
                f.staffing(),
                f.resolution(),
                null,
                f.lastDataChange(),
                f.solveEnCours(),
                f.faisabilite(),
                f.publication(),
                f.confirmations(),
                f.foireOuverte(),
                f.demandesEnAttente(),
                new ProgressionValidations(0, 0, List.of()));

        EtatEditionView etat = EtatEditionService.assemble(facts);

        assertThat(etat.problemes().reglesAnalysees()).isFalse();
        assertThat(etat.problemes().statut()).isEqualTo(Statut.ATTENTION);
        // And with the analysis in hand, nothing found is an acknowledgement.
        assertThat(EtatEditionService.assemble(filledFacts()).problemes().reglesAnalysees())
                .isTrue();
    }

    @Test
    void dataEditedAfterTheSolveMakesTheResolutionStale() {
        Facts f = filledFacts();
        Facts facts = new Facts(
                f.edition(),
                f.stands(),
                f.animateurs(),
                f.creneaux(),
                f.collecteOuverte(),
                f.declarationsEnAttente(),
                f.declarationsTraitees(),
                f.ouvertures(),
                f.staffing(),
                f.resolution(),
                f.diagnostic(),
                RESOLU_LE.plusSeconds(60),
                false,
                f.faisabilite(),
                f.publication(),
                f.confirmations(),
                f.foireOuverte(),
                f.demandesEnAttente(),
                new ProgressionValidations(0, 0, List.of()));

        EtatEditionView etat = EtatEditionService.assemble(facts);

        assertThat(etat.resolution().dataStale()).isTrue();
        assertThat(etat.resolution().statut()).isEqualTo(Statut.ATTENTION);
    }

    @Test
    void aPartialPublicationNamesHowManyPeopleToWarnAndLeavesTheAcknowledgementsAlone() {
        Facts f = filledFacts();
        Facts facts = new Facts(
                f.edition(),
                f.stands(),
                f.animateurs(),
                f.creneaux(),
                f.collecteOuverte(),
                f.declarationsEnAttente(),
                f.declarationsTraitees(),
                f.ouvertures(),
                f.staffing(),
                f.resolution(),
                f.diagnostic(),
                f.lastDataChange(),
                false,
                f.faisabilite(),
                new ApercuPublication(false, false, false, RESOLU_LE.minusSeconds(3600), 3, 0, List.of()),
                new SyntheseConfirmations(1, 1, 0, RESOLU_LE.minusSeconds(3600), false),
                f.foireOuverte(),
                2,
                new ProgressionValidations(0, 0, List.of()));

        EtatEditionView etat = EtatEditionService.assemble(facts);

        assertThat(etat.publication().statut()).isEqualTo(Statut.ATTENTION);
        assertThat(etat.publication().personnesAPrevenir()).isEqualTo(3);
        // A reminded person has still not answered.
        assertThat(etat.confirmations().statut()).isEqualTo(Statut.ATTENTION);
        assertThat(etat.confirmations().relances()).isEqualTo(1);
        assertThat(etat.foire().statut()).isEqualTo(Statut.ATTENTION);
        assertThat(etat.foire().demandesEnAttente()).isEqualTo(2);
    }

    @Test
    void problemsCountHardRulesAsBlockingAndMediumRulesAsWarnings() {
        PlanningDiagnostic diagnostic = diagnostic(
                "-2hard/-1medium/0soft",
                -2,
                List.of(
                        contrainte(REGLE_DURE, 2),
                        contrainte(REGLE_MEDIUM, 1),
                        contrainte("equilibreHeures", 4),
                        contrainte("animateurDisponible", 0)));
        FeasibilityReport faisabilite = new FeasibilityReport(
                false,
                1,
                List.of(
                        cause(SeveriteInfaisabilite.CRITIQUE),
                        cause(SeveriteInfaisabilite.ELEVE),
                        cause(SeveriteInfaisabilite.ELEVE)),
                3,
                1,
                2,
                "Trois causes.");
        Facts f = filledFacts();
        Facts facts = new Facts(
                f.edition(),
                f.stands(),
                f.animateurs(),
                f.creneaux(),
                f.collecteOuverte(),
                f.declarationsEnAttente(),
                f.declarationsTraitees(),
                f.ouvertures(),
                f.staffing(),
                f.resolution(),
                diagnostic,
                f.lastDataChange(),
                false,
                faisabilite,
                f.publication(),
                f.confirmations(),
                f.foireOuverte(),
                f.demandesEnAttente(),
                new ProgressionValidations(0, 0, List.of()));

        EtatEditionView etat = EtatEditionService.assemble(facts);

        // One critical cause and one hard rule in default; two other causes and one medium rule.
        assertThat(etat.problemes().bloquants()).isEqualTo(2);
        assertThat(etat.problemes().avertissements()).isEqualTo(3);
        assertThat(etat.problemes().statut()).isEqualTo(Statut.ATTENTION);
        assertThat(etat.resolution().faisable()).isFalse();
        assertThat(etat.resolution().statut()).isEqualTo(Statut.ATTENTION);
    }

    @Test
    void warningsWithoutABlockerAreInformationNotAnAlert() {
        PlanningDiagnostic diagnostic = diagnostic(
                "0hard/-1medium/0soft",
                0,
                List.of(contrainte(REGLE_MEDIUM, 1), contrainte("equilibreHeures", 4), contrainte(REGLE_DURE, 0)));
        FeasibilityReport faisabilite = new FeasibilityReport(
                true, 0, List.of(cause(SeveriteInfaisabilite.ELEVE)), 1, 0, 1, "Une cause élevée.");
        Facts f = filledFacts();
        Facts facts = new Facts(
                f.edition(),
                f.stands(),
                f.animateurs(),
                f.creneaux(),
                f.collecteOuverte(),
                f.declarationsEnAttente(),
                f.declarationsTraitees(),
                f.ouvertures(),
                f.staffing(),
                f.resolution(),
                diagnostic,
                f.lastDataChange(),
                false,
                faisabilite,
                f.publication(),
                f.confirmations(),
                f.foireOuverte(),
                f.demandesEnAttente(),
                new ProgressionValidations(0, 0, List.of()));

        EtatEditionView etat = EtatEditionService.assemble(facts);

        // One elevated cause and one medium rule in default, nothing blocking:
        // the line reports, it does not alert.
        assertThat(etat.problemes().bloquants()).isZero();
        assertThat(etat.problemes().avertissements()).isEqualTo(2);
        assertThat(etat.problemes().statut()).isEqualTo(Statut.INFO);
    }

    @Test
    void anOpenCollectionOrAPendingDeclarationAsksForAttention() {
        Facts f = emptyFacts();
        Facts ouverte = new Facts(
                f.edition(),
                0,
                0,
                0,
                true,
                0,
                0,
                f.ouvertures(),
                f.staffing(),
                null,
                null,
                null,
                false,
                f.faisabilite(),
                f.publication(),
                f.confirmations(),
                f.foireOuverte(),
                0,
                new ProgressionValidations(0, 0, List.of()));
        Facts enAttente = new Facts(
                f.edition(),
                0,
                0,
                0,
                false,
                2,
                5,
                f.ouvertures(),
                f.staffing(),
                null,
                null,
                null,
                false,
                f.faisabilite(),
                f.publication(),
                f.confirmations(),
                f.foireOuverte(),
                0,
                new ProgressionValidations(0, 0, List.of()));
        Facts fermee = new Facts(
                f.edition(),
                0,
                0,
                0,
                false,
                0,
                5,
                f.ouvertures(),
                f.staffing(),
                null,
                null,
                null,
                false,
                f.faisabilite(),
                f.publication(),
                f.confirmations(),
                f.foireOuverte(),
                0,
                new ProgressionValidations(0, 0, List.of()));

        assertThat(EtatEditionService.assemble(ouverte).collecte().statut()).isEqualTo(Statut.ATTENTION);
        assertThat(EtatEditionService.assemble(enAttente).collecte().statut()).isEqualTo(Statut.ATTENTION);
        assertThat(EtatEditionService.assemble(enAttente).collecte().declarationsEnAttente())
                .isEqualTo(2);
        assertThat(EtatEditionService.assemble(fermee).collecte().statut()).isEqualTo(Statut.FAIT);
    }

    @Test
    void aRosterBelowTheRetainedMinimumIsAShortfall() {
        Stand stand = new Stand("S1", "Stand un", Set.of(), 3, 3, false);
        Creneau creneau = creneau(1L);
        List<Animateur> animateurs = List.of(animateur("A1"));
        StaffingSummary staffing = new StaffingAnalyzer()
                .analyze(
                        List.of(poste("P1", stand, creneau), poste("P2", stand, creneau), poste("P3", stand, creneau)),
                        animateurs,
                        List.of(),
                        2100,
                        660,
                        List.of());
        Facts f = emptyFacts();
        Facts facts = new Facts(
                f.edition(),
                1,
                1,
                1,
                false,
                0,
                0,
                OuvertureStandsAnalyzer.analyze(List.of(stand), List.of(creneau)),
                staffing,
                null,
                null,
                null,
                false,
                f.faisabilite(),
                f.publication(),
                f.confirmations(),
                f.foireOuverte(),
                0,
                new ProgressionValidations(0, 0, List.of()));

        EtatEditionView etat = EtatEditionService.assemble(facts);

        assertThat(etat.besoin().minimum()).isEqualTo(3);
        assertThat(etat.besoin().animateurs()).isEqualTo(1);
        assertThat(etat.besoin().manque()).isEqualTo(2);
        assertThat(etat.besoin().statut()).isEqualTo(Statut.ATTENTION);
        assertThat(etat.ouvertures().statut()).isEqualTo(Statut.FAIT);
    }

    /* ------------------------------- Fixtures ------------------------------ */

    // A stand said open at an hour the grid does not have produces nothing:
    // the home page names that case rather than folding it into « anomalies ».
    @Test
    void aWindowOutsideEveryCreneauIsCountedOnItsOwnOnTheOpeningsLine() {
        Stand stand = new Stand("S1", "Stand un", Set.of(), 1, 1, false);
        stand.setOuvertures(List.of(new OuvertureStand(null, JOUR, LocalTime.of(7, 0), LocalTime.of(8, 0), null)));
        Creneau creneau = creneau(1L);
        Facts f = filledFacts();
        Facts facts = new Facts(
                f.edition(),
                1,
                2,
                1,
                false,
                0,
                0,
                OuvertureStandsAnalyzer.analyze(List.of(stand), List.of(creneau)),
                f.staffing(),
                f.resolution(),
                f.diagnostic(),
                f.lastDataChange(),
                false,
                f.faisabilite(),
                f.publication(),
                f.confirmations(),
                f.foireOuverte(),
                0,
                new ProgressionValidations(0, 0, List.of()));

        EtatEditionView.EtatOuvertures ouvertures =
                EtatEditionService.assemble(facts).ouvertures();

        assertThat(ouvertures.fenetresSansEffet()).isEqualTo(1);
        assertThat(ouvertures.anomalies()).isGreaterThanOrEqualTo(1);
        assertThat(ouvertures.statut()).isEqualTo(Statut.ATTENTION);
    }

    /* --------------------------- The relecture line -------------------------- */

    /** Nothing to read before there is a plan, whatever the grid already holds. */
    @Test
    void theReviewLineIsStillAheadWhileNothingIsSolved() {
        assertThat(EtatEditionService.assemble(emptyFacts()).relecture().statut())
                .isEqualTo(Statut.A_FAIRE);
    }

    @Test
    void theReviewLineIsUnderWayWhileSomeDaysAreLeftToRead() {
        EtatEditionView.EtatRelecture relecture = EtatEditionService.assemble(
                        withRelecture(new ProgressionValidations(3, 1, List.of())))
                .relecture();

        assertThat(relecture.statut()).isEqualTo(Statut.INFO);
        assertThat(relecture.journees()).isEqualTo(3);
        assertThat(relecture.journeesValidees()).isEqualTo(1);
    }

    @Test
    void theReviewLineIsBehindOnceEveryDayIsAccepted() {
        assertThat(EtatEditionService.assemble(withRelecture(new ProgressionValidations(3, 3, List.of())))
                        .relecture()
                        .statut())
                .isEqualTo(Statut.FAIT);
    }

    /** A solved edition nobody has started reading is a step still ahead, not a warning. */
    @Test
    void aSolvedEditionNobodyReadIsAStepStillAhead() {
        assertThat(EtatEditionService.assemble(withRelecture(new ProgressionValidations(3, 0, List.of())))
                        .relecture()
                        .statut())
                .isEqualTo(Statut.A_FAIRE);
    }

    private static Facts withRelecture(ProgressionValidations relecture) {
        Facts f = filledFacts();
        return new Facts(
                f.edition(),
                f.stands(),
                f.animateurs(),
                f.creneaux(),
                f.collecteOuverte(),
                f.declarationsEnAttente(),
                f.declarationsTraitees(),
                f.ouvertures(),
                f.staffing(),
                f.resolution(),
                f.diagnostic(),
                f.lastDataChange(),
                f.solveEnCours(),
                f.faisabilite(),
                f.publication(),
                f.confirmations(),
                f.foireOuverte(),
                f.demandesEnAttente(),
                relecture);
    }

    private static Facts emptyFacts() {
        return new Facts(
                EDITION,
                0,
                0,
                0,
                false,
                0,
                0,
                OuvertureStandsAnalyzer.analyze(List.of(), List.of()),
                new StaffingAnalyzer()
                        .analyze(
                                List.of(),
                                List.of(),
                                List.of(),
                                2100,
                                660,
                                StaffingAnalyzer.referentielsManquants(List.of(), List.of(), List.of())),
                null,
                null,
                null,
                false,
                new FeasibilityReport(true, 0, List.of(), 0, 0, 0, "Réalisable."),
                new ApercuPublication(true, true, false, null, 0, 0, List.of()),
                new SyntheseConfirmations(0, 0, 0, null, true),
                true,
                0,
                new ProgressionValidations(0, 0, List.of()));
    }

    /** One stand of one seat, one timeslot, two animateurs: solved, published, everybody answered. */
    private static Facts filledFacts() {
        Stand stand = new Stand("S1", "Stand un", Set.of(), 1, 1, false);
        Creneau creneau = creneau(1L);
        List<Animateur> animateurs = List.of(animateur("A1"), animateur("A2"));
        Instant publieLe = RESOLU_LE.plusSeconds(600);
        return new Facts(
                EDITION,
                1,
                2,
                1,
                false,
                0,
                2,
                OuvertureStandsAnalyzer.analyze(List.of(stand), List.of(creneau)),
                new StaffingAnalyzer().analyze(List.of(poste("P1", stand, creneau)), animateurs, List.of(), 2100, 660),
                new PlanningResolution(RESOLU_LE, null),
                diagnostic("0hard/0medium/-12soft", 0, List.of(contrainte("equilibreHeures", 3))),
                RESOLU_LE.minusSeconds(60),
                false,
                new FeasibilityReport(true, 0, List.of(), 0, 0, 0, "Réalisable."),
                new ApercuPublication(false, false, false, publieLe, 0, 0, List.of()),
                new SyntheseConfirmations(2, 0, 0, publieLe, false),
                true,
                0,
                new ProgressionValidations(0, 0, List.of()));
    }

    private static PlanningDiagnostic diagnostic(String score, int hardScore, List<ConstraintDiagnostic> contraintes) {
        return new PlanningDiagnostic(
                score,
                0,
                contraintes,
                new FeasibilityReport(true, 0, List.of(), 0, 0, 0, "Réalisable."),
                hardScore,
                List.of(),
                "0hard/0medium/-2soft",
                0,
                -10,
                List.of());
    }

    private static ConstraintDiagnostic contrainte(String name, int matchCount) {
        return new ConstraintDiagnostic(name, "0hard/0medium/0soft", matchCount, List.of(), null, null, List.of());
    }

    private static CauseInfaisabilite cause(SeveriteInfaisabilite severite) {
        return new CauseInfaisabilite(
                TypeCauseInfaisabilite.CRENEAU_SOUS_EFFECTIF,
                severite,
                "Cause",
                1L,
                JOUR,
                LocalTime.of(9, 0),
                LocalTime.of(12, 0),
                List.of("S1"),
                List.of(),
                2,
                1,
                1);
    }

    private static Creneau creneau(long id) {
        return new Creneau(id, 1, JOUR, LocalTime.of(9, 0), LocalTime.of(12, 0));
    }

    private static Animateur animateur(String id) {
        return new Animateur(id, "Prenom", "Nom " + id, LocalDate.of(1990, 1, 1), false);
    }

    private static PosteAffectation poste(String id, Stand stand, Creneau creneau) {
        return new PosteAffectation(id, stand, creneau);
    }
}
