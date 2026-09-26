package dev.sylvain.planning.service.edition;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.Edition;
import dev.sylvain.planning.domain.FenetreHoraire;
import dev.sylvain.planning.domain.HoraireStand;
import dev.sylvain.planning.domain.ModeHoraire;
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
import dev.sylvain.planning.service.analyse.ScoreReading;
import dev.sylvain.planning.service.analyse.StaffingAnalyzer;
import dev.sylvain.planning.service.analyse.StaffingAnalyzer.StaffingSummary;
import dev.sylvain.planning.service.backup.BackupRun;
import dev.sylvain.planning.service.edition.EtatEditionService.Facts;
import dev.sylvain.planning.service.edition.EtatEditionService.JourFacts;
import dev.sylvain.planning.service.edition.EtatEditionService.TodayFacts;
import dev.sylvain.planning.service.edition.EtatEditionView.Phase;
import dev.sylvain.planning.service.edition.EtatEditionView.Statut;
import dev.sylvain.planning.service.notification.JournalNotificationsRepository.Alerte;
import dev.sylvain.planning.service.publication.ConfirmationPlanningService.SyntheseConfirmations;
import dev.sylvain.planning.service.publication.PlanPublicationService.ApercuPublication;
import dev.sylvain.planning.service.referentiel.CoherenceReferentielService.CoherenceReport;
import dev.sylvain.planning.service.referentiel.HoraireStandResolver;
import dev.sylvain.planning.service.solve.PlanningPersistenceService.PlanningResolution;
import dev.sylvain.planning.service.validation.ValidationPrerequisService.ProgressionValidations;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
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

    /** A day where nothing waits: no declaration, no swap request, no day of the edition ahead. */
    private static final TodayFacts NOTHING_TODAY =
            new TodayFacts(JOUR, JOUR.atTime(9, 0).toInstant(ZoneOffset.UTC), List.of(), List.of(), 7, List.of());

    /** A referential with nothing to say about itself. */
    private static final CoherenceReport NO_ISSUE = new CoherenceReport(0, 0, 0, List.of(), List.of());

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

    /**
     * A game category a stand proposes and nobody holds leaves that stand to
     * the polyvalents alone: the step is entered, and still worth a look.
     */
    @Test
    void anOrphanTypologieTurnsTheReferentialLineToCheck() {
        Facts f = filledFacts();
        Facts facts = new Facts(
                f.edition(),
                f.stands(),
                f.animateurs(),
                f.creneaux(),
                2,
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
                f.relecture(),
                f.coherence(),
                f.today());

        EtatEditionView etat = EtatEditionService.assemble(facts);

        assertThat(etat.referentiels().typologiesOrphelines()).isEqualTo(2);
        assertThat(etat.referentiels().statut()).isEqualTo(Statut.ATTENTION);
        assertThat(EtatEditionService.assemble(filledFacts()).referentiels().statut())
                .isEqualTo(Statut.FAIT);
    }

    @Test
    void aRunningSolveIsReportedOnTheResolutionLineAndNowhereElse() {
        Facts f = filledFacts();
        Facts facts = new Facts(
                f.edition(),
                f.stands(),
                f.animateurs(),
                f.creneaux(),
                f.typologiesOrphelines(),
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
                new ProgressionValidations(0, 0, List.of()),
                NO_ISSUE,
                NOTHING_TODAY);

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
                f.typologiesOrphelines(),
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
                new ProgressionValidations(0, 0, List.of()),
                NO_ISSUE,
                NOTHING_TODAY);

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
                f.typologiesOrphelines(),
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
                new ProgressionValidations(0, 0, List.of()),
                NO_ISSUE,
                NOTHING_TODAY);

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
                f.typologiesOrphelines(),
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
                new ProgressionValidations(0, 0, List.of()),
                NO_ISSUE,
                NOTHING_TODAY);

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
                f.typologiesOrphelines(),
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
                new ProgressionValidations(0, 0, List.of()),
                NO_ISSUE,
                NOTHING_TODAY);

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
                f.typologiesOrphelines(),
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
                new ProgressionValidations(0, 0, List.of()),
                NO_ISSUE,
                NOTHING_TODAY);

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
                new ProgressionValidations(0, 0, List.of()),
                NO_ISSUE,
                NOTHING_TODAY);
        Facts enAttente = new Facts(
                f.edition(),
                0,
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
                new ProgressionValidations(0, 0, List.of()),
                NO_ISSUE,
                NOTHING_TODAY);
        Facts fermee = new Facts(
                f.edition(),
                0,
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
                new ProgressionValidations(0, 0, List.of()),
                NO_ISSUE,
                NOTHING_TODAY);

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
                0,
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
                new ProgressionValidations(0, 0, List.of()),
                NO_ISSUE,
                NOTHING_TODAY);

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
                0,
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
                new ProgressionValidations(0, 0, List.of()),
                NO_ISSUE,
                NOTHING_TODAY);

        EtatEditionView.EtatOuvertures ouvertures =
                EtatEditionService.assemble(facts).ouvertures();

        assertThat(ouvertures.fenetresSansEffet()).isEqualTo(1);
        assertThat(ouvertures.anomalies()).isGreaterThanOrEqualTo(1);
        assertThat(ouvertures.statut()).isEqualTo(Statut.ATTENTION);
    }

    /** Overlapping rules are settled by the resolver: counted, said, and never « à vérifier » on their own. */
    @Test
    void overlappingRulesAloneLeaveTheOpeningsLineForInformation() {
        Stand stand = new Stand("S1", "Stand un", Set.of(), 1, 4, false);
        stand.setHoraires(List.of(
                HoraireStand.everyDay(
                        ModeHoraire.OUVERTURE, new FenetreHoraire(LocalTime.of(9, 0), LocalTime.of(12, 0), 1)),
                HoraireStand.everyDay(
                        ModeHoraire.OUVERTURE, new FenetreHoraire(LocalTime.of(9, 0), LocalTime.of(12, 0), 2))));
        List<Creneau> creneaux = List.of(creneau(1L));
        HoraireStandResolver.apply(List.of(stand), creneaux);
        Facts f = filledFacts();
        Facts facts = new Facts(
                f.edition(),
                1,
                2,
                1,
                0,
                false,
                0,
                0,
                OuvertureStandsAnalyzer.analyze(List.of(stand), creneaux),
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
                new ProgressionValidations(0, 0, List.of()),
                NO_ISSUE,
                NOTHING_TODAY);

        EtatEditionView.EtatOuvertures ouvertures =
                EtatEditionService.assemble(facts).ouvertures();

        assertThat(ouvertures.anomalies()).isEqualTo(1);
        assertThat(ouvertures.informations()).isEqualTo(1);
        assertThat(ouvertures.statut()).isEqualTo(Statut.INFO);
    }

    /* ------------------------ « À traiter aujourd'hui » ------------------------ */

    private static final Instant MAINTENANT = JOUR.atTime(9, 0).toInstant(ZoneOffset.UTC);

    /** The filled edition, judged on {@code today}, with the given publication and confirmations. */
    private static EtatEditionView.EtatATraiter aTraiter(
            TodayFacts today,
            ApercuPublication publication,
            SyntheseConfirmations confirmations,
            ProgressionValidations relecture,
            boolean solveEnCours,
            Instant lastDataChange) {
        Facts f = filledFacts();
        return EtatEditionService.assemble(new Facts(
                        f.edition(),
                        f.stands(),
                        f.animateurs(),
                        f.creneaux(),
                        f.typologiesOrphelines(),
                        f.collecteOuverte(),
                        today.declarations().size(),
                        f.declarationsTraitees(),
                        f.ouvertures(),
                        f.staffing(),
                        f.resolution(),
                        f.diagnostic(),
                        lastDataChange,
                        solveEnCours,
                        f.faisabilite(),
                        publication,
                        confirmations,
                        f.foireOuverte(),
                        today.echanges().size(),
                        relecture,
                        NO_ISSUE,
                        today))
                .aTraiter();
    }

    private static EtatEditionView.EtatATraiter aTraiter(TodayFacts today) {
        Facts f = filledFacts();
        return aTraiter(today, f.publication(), f.confirmations(), f.relecture(), false, f.lastDataChange());
    }

    private static TodayFacts today(List<Instant> declarations, List<Instant> echanges, List<LocalDate> jours) {
        return new TodayFacts(JOUR, MAINTENANT, declarations, echanges, 7, jours);
    }

    @Test
    void aSolvedPublishedAnsweredEditionHasNothingToDoToday() {
        EtatEditionView.EtatATraiter bloc =
                EtatEditionService.assemble(filledFacts()).aTraiter();

        assertThat(bloc.declarationsEnAttente()).isZero();
        assertThat(bloc.echangesAArbitrer()).isZero();
        assertThat(bloc.journeesNonRelues()).isEmpty();
        assertThat(bloc.silencieuxARelancer()).isZero();
        assertThat(bloc.donneesModifiees()).isFalse();
        assertThat(bloc.personnesAPrevenir()).isZero();
        assertThat(bloc.aujourdhui()).isEqualTo(JOUR);
    }

    @Test
    void pendingDeclarationsAreCountedWithTheOldestSubmission() {
        Instant ancienne = MAINTENANT.minus(java.time.Duration.ofDays(5));
        EtatEditionView.EtatATraiter bloc =
                aTraiter(today(List.of(MAINTENANT.minusSeconds(60), ancienne), List.of(), List.of()));

        assertThat(bloc.declarationsEnAttente()).isEqualTo(2);
        assertThat(bloc.plusAncienneDeclaration()).isEqualTo(ancienne);
    }

    /** Older than the notification setting: an alert; younger: information. */
    @Test
    void aSwapRequestOlderThanTheSettingIsAnAlertTheOthersAreInformation() {
        EtatEditionView.EtatATraiter bloc = aTraiter(today(
                List.of(),
                List.of(MAINTENANT.minus(java.time.Duration.ofDays(8)), MAINTENANT.minus(java.time.Duration.ofDays(2))),
                List.of()));

        assertThat(bloc.echangesAArbitrer()).isEqualTo(2);
        assertThat(bloc.echangesEnAlerte()).isEqualTo(1);
        assertThat(bloc.seuilAncienneteJours()).isEqualTo(7);
        assertThat(bloc.plusAncienEchange()).isEqualTo(MAINTENANT.minus(java.time.Duration.ofDays(8)));
    }

    /** Seven days in all, today included: J+6 is the last one, J+7 is not close, yesterday is history. */
    @Test
    void theHorizonIsSevenDaysTodayIncluded() {
        EtatEditionView.EtatATraiter bloc = aTraiter(today(
                List.of(),
                List.of(),
                List.of(
                        JOUR.minusDays(1),
                        JOUR,
                        JOUR.plusDays(3),
                        JOUR.plusDays(6),
                        JOUR.plusDays(7),
                        JOUR.plusDays(10))));

        assertThat(bloc.journeesNonRelues()).containsExactly(JOUR, JOUR.plusDays(3), JOUR.plusDays(6));
        assertThat(bloc.horizonJours()).isEqualTo(EtatEditionService.UPCOMING_DAYS_HORIZON);
    }

    @Test
    void aDayAlreadyAcceptedIsNotToRead() {
        Facts f = filledFacts();
        EtatEditionView.EtatATraiter bloc = aTraiter(
                today(List.of(), List.of(), List.of(JOUR, JOUR.plusDays(1))),
                f.publication(),
                f.confirmations(),
                new ProgressionValidations(2, 1, List.of(JOUR)),
                false,
                f.lastDataChange());

        assertThat(bloc.journeesNonRelues()).containsExactly(JOUR.plusDays(1));
    }

    /** « Aujourd'hui » is the recette clock's: frozen after the event, no day is close any more. */
    @Test
    void theDayJudgedOnIsTheOneTheClockGives() {
        TodayFacts apres = new TodayFacts(
                JOUR.plusDays(30),
                MAINTENANT.plus(java.time.Duration.ofDays(30)),
                List.of(),
                List.of(),
                7,
                List.of(JOUR, JOUR.plusDays(3)));

        EtatEditionView.EtatATraiter bloc = aTraiter(apres);

        assertThat(bloc.aujourdhui()).isEqualTo(JOUR.plusDays(30));
        assertThat(bloc.journeesNonRelues()).isEmpty();
    }

    /** Somebody reminded for this publication is RELANCE, not NON_VU: only the never-reminded are counted. */
    @Test
    void theSilentAreToRemindOnlyOnceTheSilenceIsLongEnoughAndNeverTheAlreadyReminded() {
        Facts f = filledFacts();
        Instant ilYaCinqJours = MAINTENANT.minus(java.time.Duration.ofDays(5));
        Instant hier = MAINTENANT.minus(java.time.Duration.ofDays(1));

        EtatEditionView.EtatATraiter ancien = aTraiter(
                NOTHING_TODAY,
                f.publication(),
                new SyntheseConfirmations(10, 4, 6, ilYaCinqJours, false),
                f.relecture(),
                false,
                f.lastDataChange());
        EtatEditionView.EtatATraiter recent = aTraiter(
                NOTHING_TODAY,
                f.publication(),
                new SyntheseConfirmations(10, 4, 6, hier, false),
                f.relecture(),
                false,
                f.lastDataChange());

        assertThat(ancien.silencieuxARelancer()).isEqualTo(6);
        assertThat(ancien.silenceJours()).isEqualTo(EtatEditionService.SILENCE_DAYS);
        assertThat(recent.silencieuxARelancer()).isZero();
    }

    @Test
    void beforeAnyPublicationNobodyIsSilentNorToTell() {
        EtatEditionView.EtatATraiter bloc = aTraiter(
                NOTHING_TODAY,
                new ApercuPublication(true, false, false, null, 12, 0, List.of()),
                new SyntheseConfirmations(0, 0, 0, null, true),
                filledFacts().relecture(),
                false,
                filledFacts().lastDataChange());

        assertThat(bloc.silencieuxARelancer()).isZero();
        assertThat(bloc.personnesAPrevenir()).isZero();
    }

    @Test
    void peopleToTellAfterAFirstPublicationAreCounted() {
        Facts f = filledFacts();
        EtatEditionView.EtatATraiter bloc = aTraiter(
                NOTHING_TODAY,
                new ApercuPublication(false, false, false, RESOLU_LE, 4, 0, List.of()),
                f.confirmations(),
                f.relecture(),
                false,
                f.lastDataChange());

        assertThat(bloc.personnesAPrevenir()).isEqualTo(4);
    }

    /** Stale data is said, except while a solve runs: it is reading the new data. */
    @Test
    void staleDataIsSaidExceptWhileASolveRuns() {
        Facts f = filledFacts();
        Instant apresResolution = RESOLU_LE.plusSeconds(3600);

        assertThat(aTraiter(NOTHING_TODAY, f.publication(), f.confirmations(), f.relecture(), false, apresResolution)
                        .donneesModifiees())
                .isTrue();
        EtatEditionView.EtatATraiter enCours = aTraiter(
                NOTHING_TODAY,
                new ApercuPublication(false, false, true, RESOLU_LE, 4, 0, List.of()),
                f.confirmations(),
                f.relecture(),
                true,
                apresResolution);
        assertThat(enCours.donneesModifiees()).isFalse();
        assertThat(enCours.personnesAPrevenir()).isZero();
    }

    /* --------------------------- The coherence line -------------------------- */

    @Test
    void anEmptyChecklistIsDoneEvenOnAnEmptyEdition() {
        // The Référentiels line says the step is ahead; this one does not repeat it.
        assertThat(EtatEditionService.assemble(emptyFacts()).coherence().statut())
                .isEqualTo(Statut.FAIT);
    }

    @Test
    void aLineToCheckOrABlockingOneMakesTheCoherenceLineToBeChecked() {
        assertThat(coherenceWith(new CoherenceReport(1, 0, 3, List.of(), List.of()))
                        .statut())
                .isEqualTo(Statut.ATTENTION);
        assertThat(coherenceWith(new CoherenceReport(0, 2, 0, List.of(), List.of()))
                        .statut())
                .isEqualTo(Statut.ATTENTION);
    }

    @Test
    void informationAloneLeavesTheCoherenceLineForInformation() {
        EtatEditionView.EtatCoherence coherence = coherenceWith(new CoherenceReport(0, 0, 4, List.of(), List.of()));

        assertThat(coherence.statut()).isEqualTo(Statut.INFO);
        assertThat(coherence.informations()).isEqualTo(4);
    }

    private static EtatEditionView.EtatCoherence coherenceWith(CoherenceReport rapport) {
        Facts f = filledFacts();
        return EtatEditionService.assemble(new Facts(
                        f.edition(),
                        f.stands(),
                        f.animateurs(),
                        f.creneaux(),
                        f.typologiesOrphelines(),
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
                        f.relecture(),
                        rapport,
                        NOTHING_TODAY))
                .coherence();
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
                f.typologiesOrphelines(),
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
                relecture,
                NO_ISSUE,
                NOTHING_TODAY);
    }

    private static Facts emptyFacts() {
        return new Facts(
                EDITION,
                0,
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
                new ProgressionValidations(0, 0, List.of()),
                NO_ISSUE,
                NOTHING_TODAY);
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
                0,
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
                new ProgressionValidations(0, 0, List.of()),
                NO_ISSUE,
                NOTHING_TODAY);
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

    /**
     * The archive link appears once the last day carrying a timeslot is
     * behind today — not on it, and never for an edition with no timeslot,
     * which has no end to be past.
     */
    @Test
    void theEventIsOverOnlyOnceItsLastDayIsBehindToday() {
        assertThat(EtatEditionService.evenement(todayWithDays(List.of())))
                .isEqualTo(new EtatEditionView.EtatEvenement(null, null, false, JOUR, Phase.PREPARATION, null));
        assertThat(EtatEditionService.evenement(todayWithDays(List.of(JOUR.minusDays(3), JOUR))))
                .isEqualTo(
                        new EtatEditionView.EtatEvenement(JOUR.minusDays(3), JOUR, false, JOUR, Phase.EVENEMENT, null));
        assertThat(EtatEditionService.evenement(todayWithDays(List.of(JOUR.plusDays(1))))
                        .termine())
                .isFalse();
        assertThat(EtatEditionService.evenement(todayWithDays(List.of(JOUR.minusDays(3), JOUR.minusDays(1))))
                        .termine())
                .isTrue();
        assertThat(EtatEditionService.assemble(emptyFacts()).evenement().termine())
                .isFalse();
    }

    /* ------------------------------ Phases ---------------------------------- */

    /** A day the event runs, with the given day under way and the given night. */
    private static TodayFacts duringTheEvent(
            List<LocalDate> jours, JourFacts jour, List<Instant> echanges, List<Alerte> alertes, BackupRun sauvegarde) {
        return new TodayFacts(JOUR, MAINTENANT, List.of(), echanges, 7, jours, false, 72, alertes, sauvegarde, jour);
    }

    @Test
    void thePhaseIsPreparationBeforeTheFirstDayAndWithoutAnyDay() {
        assertThat(EtatEditionService.phase(List.of(), JOUR)).isEqualTo(Phase.PREPARATION);
        assertThat(EtatEditionService.phase(List.of(JOUR.plusDays(1), JOUR.plusDays(4)), JOUR))
                .isEqualTo(Phase.PREPARATION);
    }

    @Test
    void thePhaseIsTheEventFromTheFirstDayToTheLastBothIncluded() {
        List<LocalDate> jours = List.of(JOUR.minusDays(2), JOUR.plusDays(2));

        assertThat(EtatEditionService.phase(jours, JOUR.minusDays(2))).isEqualTo(Phase.EVENEMENT);
        assertThat(EtatEditionService.phase(jours, JOUR)).isEqualTo(Phase.EVENEMENT);
        assertThat(EtatEditionService.phase(jours, JOUR.plusDays(2))).isEqualTo(Phase.EVENEMENT);
        assertThat(EtatEditionService.phase(jours, JOUR.plusDays(3))).isEqualTo(Phase.APRES);
    }

    /** « J5 · 60 stands ouverts · 23 places vides · 0 absent · 2 échanges à arbitrer ». */
    @Test
    void duringTheEventTheDayUnderWayIsReadWithItsRank() {
        TodayFacts today = duringTheEvent(
                List.of(JOUR.minusDays(4), JOUR.minusDays(1), JOUR, JOUR.plusDays(3)),
                new JourFacts(JOUR, 60, 23, 0),
                List.of(MAINTENANT.minusSeconds(60), MAINTENANT.minusSeconds(120)),
                List.of(),
                null);

        EtatEditionView.EtatEvenement evenement = EtatEditionService.evenement(today);

        assertThat(evenement.phase()).isEqualTo(Phase.EVENEMENT);
        assertThat(evenement.aujourdhui()).isEqualTo(JOUR);
        // Counted on the calendar from the first day: a day without a timeslot still counts.
        assertThat(evenement.jour()).isEqualTo(new EtatEditionView.EtatJour(JOUR, 5, 60, 23, 0, 2));
    }

    @Test
    void outsideTheEventThereIsNoDayToRead() {
        TodayFacts avant =
                duringTheEvent(List.of(JOUR.plusDays(1)), new JourFacts(JOUR, 60, 23, 0), List.of(), List.of(), null);

        assertThat(EtatEditionService.evenement(avant).jour()).isNull();
        assertThat(EtatEditionService.evenement(avant).phase()).isEqualTo(Phase.PREPARATION);
    }

    /**
     * A night shift on the last day, 22:00–02:00: at one in the morning the
     * calendar says the day after, the wall display and the mode jour J still
     * say the last day — and so does the phase, which does not close the
     * event under a shift still running.
     */
    @Test
    void theNightShiftOfTheLastDayKeepsTheEventRunningPastMidnight() {
        LocalDate dernier = JOUR.plusDays(2);
        List<Creneau> creneaux = List.of(
                new Creneau(1L, 0, JOUR, LocalTime.of(9, 0), LocalTime.of(18, 0)),
                new Creneau(2L, 0, dernier, LocalTime.of(14, 0), LocalTime.of(22, 0)),
                new Creneau(3L, 0, dernier, LocalTime.of(22, 0), LocalTime.of(2, 0)));
        List<LocalDate> jours = List.of(JOUR, dernier);

        LocalDate enCours =
                EtatEditionService.dayUnderWayOf(creneaux, dernier.plusDays(1).atTime(1, 0));
        TodayFacts nuit = new TodayFacts(
                dernier.plusDays(1),
                MAINTENANT,
                List.of(),
                List.of(),
                7,
                jours,
                false,
                72,
                List.of(),
                null,
                null,
                enCours);

        assertThat(enCours).isEqualTo(dernier);
        assertThat(EtatEditionService.evenement(nuit).phase()).isEqualTo(Phase.EVENEMENT);
        assertThat(EtatEditionService.evenement(nuit).termine()).isFalse();
        // Once the shift is over, the calendar date is the day again, and the event is behind.
        LocalDate apres =
                EtatEditionService.dayUnderWayOf(creneaux, dernier.plusDays(1).atTime(3, 0));
        assertThat(apres).isEqualTo(dernier.plusDays(1));
        assertThat(EtatEditionService.phase(jours, apres)).isEqualTo(Phase.APRES);
    }

    /* ------------------------- The alerts of the night ------------------------ */

    private static Animateur fiche(String id, String email) {
        Animateur animateur = new Animateur();
        animateur.setId(id);
        animateur.setEmail(email);
        return animateur;
    }

    /**
     * An alert says something true only while its cause stands: a fiche given
     * an address since is written to by the next night, and somebody who has
     * answered since has nobody left to chase them.
     */
    @Test
    void anAlertOfTheNightCountsOnlyWhileItsCauseStands() {
        List<Alerte> alertes = List.of(
                alerte("RAPPEL_VEILLE_INJOIGNABLE", "A1|" + JOUR, MAINTENANT),
                new Alerte("RAPPEL_VEILLE_INJOIGNABLE", "A2|" + JOUR, MAINTENANT, "libellé", "WARNING", "A2"),
                new Alerte("RELANCE_INJOIGNABLE", "A3|x", MAINTENANT, "libellé", "WARNING", "A3"),
                new Alerte("RELANCE_INJOIGNABLE", "A4|x", MAINTENANT, "libellé", "ALERTE", "A4"),
                new Alerte("ALERTE_ECHANGE", "D1", MAINTENANT, "libellé", "WARNING", null));
        List<Animateur> animateurs = List.of(
                fiche("A1", " "), fiche("A2", "a2@example.org"), fiche("A3", null), fiche("A4", "a4@example.org"));

        List<Alerte> restantes = EtatEditionService.stillStanding(alertes, animateurs, () -> Set.of("A4"));

        assertThat(restantes).extracting(Alerte::cle).containsExactly("A1|" + JOUR, "A4|x", "D1");
    }

    /** Without a reminder alert to check, who is still silent is not even read. */
    @Test
    void theSilentAreReadOnlyWhenAReminderAlertIsThere() {
        List<Alerte> alertes = List.of(alerte("ALERTE_ECHANGE", "D1", MAINTENANT));

        List<Alerte> restantes = EtatEditionService.stillStanding(alertes, List.of(), () -> {
            throw new AssertionError("read for nothing");
        });

        assertThat(restantes).hasSize(1);
    }

    private static Alerte alerte(String type, String cle, Instant declencheLe) {
        return new Alerte(type, cle, declencheLe, "libellé", "WARNING", "A1");
    }

    /** A reminder for a day still ahead must be made by hand; one for a day gone says nothing any more. */
    @Test
    void anUnsentDayBeforeReminderCountsWhileItsDayIsAhead() {
        List<Alerte> alertes = List.of(
                alerte("RAPPEL_VEILLE_INJOIGNABLE", "A1|" + JOUR, MAINTENANT),
                alerte("RAPPEL_VEILLE_INJOIGNABLE", "A2|" + JOUR.plusDays(1), MAINTENANT),
                alerte("RAPPEL_VEILLE_INJOIGNABLE", "A3|" + JOUR.minusDays(1), MAINTENANT),
                alerte("RAPPEL_VEILLE_INJOIGNABLE", "illisible", MAINTENANT),
                alerte("ALERTE_ECHANGE", "D1", MAINTENANT));

        EtatEditionView.EtatATraiter bloc = aTraiter(duringTheEvent(List.of(), null, List.of(), alertes, null));

        assertThat(bloc.rappelsNonEnvoyes()).isEqualTo(2);
    }

    /** An unsent reminder of the silent is about the published plan; one about an older plan is not. */
    @Test
    void anUnsentReminderCountsOnlySinceTheLastPublication() {
        Facts f = filledFacts();
        Instant publieLe = f.confirmations().dernierePublicationLe();
        List<Alerte> alertes = List.of(
                alerte("RELANCE_INJOIGNABLE", "A1|x", publieLe.plusSeconds(3600)),
                alerte("RELANCE_INJOIGNABLE", "A2|x", publieLe.minusSeconds(3600)));

        EtatEditionView.EtatATraiter bloc = aTraiter(duringTheEvent(List.of(), null, List.of(), alertes, null));

        assertThat(bloc.relancesNonEnvoyees()).isEqualTo(1);
    }

    @Test
    void aFailedNightlyBackupIsToHandleWithItsTime() {
        Instant nuit = MAINTENANT.minus(Duration.ofHours(5));
        EtatEditionView.EtatATraiter echec = aTraiter(duringTheEvent(
                List.of(), null, List.of(), List.of(), new BackupRun(nuit, false, null, "disque plein")));
        EtatEditionView.EtatATraiter reussie = aTraiter(
                duringTheEvent(List.of(), null, List.of(), List.of(), new BackupRun(nuit, true, "dump.sql", null)));
        EtatEditionView.EtatATraiter sansSauvegarde =
                aTraiter(duringTheEvent(List.of(), null, List.of(), List.of(), null));

        assertThat(echec.sauvegardeEnEchec()).isTrue();
        assertThat(echec.sauvegardeEchecLe()).isEqualTo(nuit);
        assertThat(reussie.sauvegardeEnEchec()).isFalse();
        assertThat(sansSauvegarde.sauvegardeEnEchec()).isFalse();
        assertThat(sansSauvegarde.sauvegardeEchecLe()).isNull();
    }

    /* ----------------------------- Acknowledgements ---------------------------- */

    private static EtatEditionView.EtatConfirmations confirmationsAfter(Duration depuisPublication, boolean armees) {
        Facts f = filledFacts();
        Instant publieLe = MAINTENANT.minus(depuisPublication);
        TodayFacts today =
                new TodayFacts(JOUR, MAINTENANT, List.of(), List.of(), 7, List.of(), armees, 72, List.of(), null, null);
        return EtatEditionService.assemble(new Facts(
                        f.edition(),
                        f.stands(),
                        f.animateurs(),
                        f.creneaux(),
                        f.typologiesOrphelines(),
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
                        f.publication(),
                        new SyntheseConfirmations(10, 0, 143, publieLe, false),
                        f.foireOuverte(),
                        0,
                        f.relecture(),
                        NO_ISSUE,
                        today))
                .confirmations();
    }

    /** An hour after the publication, 143 silent people are not an alert yet: the reminder delay has not run out. */
    @Test
    void theSilentOnlyCallForAttentionPastTheReminderDelay() {
        assertThat(confirmationsAfter(Duration.ofHours(1), true).statut()).isEqualTo(Statut.INFO);
        assertThat(confirmationsAfter(Duration.ofHours(72), true).statut()).isEqualTo(Statut.ATTENTION);
    }

    @Test
    void theAcknowledgementsSayWhetherTheReminderIsArmedAndAfterHowLong() {
        EtatEditionView.EtatConfirmations eteintes = confirmationsAfter(Duration.ofHours(1), false);

        assertThat(eteintes.relancesAutomatiques()).isFalse();
        assertThat(eteintes.delaiRelanceHeures()).isEqualTo(72);
        assertThat(confirmationsAfter(Duration.ofHours(1), true).relancesAutomatiques())
                .isTrue();
    }

    /** The home screen reads the score in sentences: the reading travels with the line. */
    @Test
    void theResolutionCarriesTheReadingOfTheScore() {
        Facts f = filledFacts();
        List<ScoreReading.ScoreSentence> lecture = ScoreReading.read(f.diagnostic());

        assertThat(EtatEditionService.assemble(withDiagnostic(f, f.diagnostic().withReading(lecture)))
                        .resolution()
                        .lecture())
                .isEqualTo(lecture)
                .isNotEmpty();
        assertThat(EtatEditionService.assemble(withDiagnostic(f, null))
                        .resolution()
                        .lecture())
                .isEmpty();
    }

    private static Facts withDiagnostic(Facts f, PlanningDiagnostic diagnostic) {
        return new Facts(
                f.edition(),
                f.stands(),
                f.animateurs(),
                f.creneaux(),
                f.typologiesOrphelines(),
                f.collecteOuverte(),
                f.declarationsEnAttente(),
                f.declarationsTraitees(),
                f.ouvertures(),
                f.staffing(),
                f.resolution(),
                diagnostic,
                f.lastDataChange(),
                false,
                f.faisabilite(),
                f.publication(),
                f.confirmations(),
                f.foireOuverte(),
                0,
                f.relecture(),
                NO_ISSUE,
                f.today());
    }

    private static TodayFacts todayWithDays(List<LocalDate> jours) {
        return new TodayFacts(JOUR, JOUR.atTime(9, 0).toInstant(ZoneOffset.UTC), List.of(), List.of(), 7, jours);
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
