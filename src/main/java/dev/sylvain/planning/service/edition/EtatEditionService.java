package dev.sylvain.planning.service.edition;

import dev.sylvain.planning.domain.DeclarationDisponibilite;
import dev.sylvain.planning.domain.Edition;
import dev.sylvain.planning.domain.StatutDeclaration;
import dev.sylvain.planning.service.EditionContext;
import dev.sylvain.planning.service.ReferenceDataChangeTracker;
import dev.sylvain.planning.service.analyse.FeasibilityAnalyzer;
import dev.sylvain.planning.service.analyse.FeasibilityAnalyzer.FeasibilityReport;
import dev.sylvain.planning.service.analyse.OuvertureStandsAnalyzer;
import dev.sylvain.planning.service.analyse.OuvertureStandsAnalyzer.RapportOuvertures;
import dev.sylvain.planning.service.analyse.PlanningDiagnosticService.ConstraintDiagnostic;
import dev.sylvain.planning.service.analyse.PlanningDiagnosticService.PlanningDiagnostic;
import dev.sylvain.planning.service.analyse.StaffingAnalyzer.StaffingSummary;
import dev.sylvain.planning.service.analyse.StaffingService;
import dev.sylvain.planning.service.edition.EtatEditionView.EtatBesoin;
import dev.sylvain.planning.service.edition.EtatEditionView.EtatCollecte;
import dev.sylvain.planning.service.edition.EtatEditionView.EtatConfirmations;
import dev.sylvain.planning.service.edition.EtatEditionView.EtatFoire;
import dev.sylvain.planning.service.edition.EtatEditionView.EtatOuvertures;
import dev.sylvain.planning.service.edition.EtatEditionView.EtatProblemes;
import dev.sylvain.planning.service.edition.EtatEditionView.EtatPublication;
import dev.sylvain.planning.service.edition.EtatEditionView.EtatReferentiels;
import dev.sylvain.planning.service.edition.EtatEditionView.EtatResolution;
import dev.sylvain.planning.service.edition.EtatEditionView.Statut;
import dev.sylvain.planning.service.espace.DeclarationDisponibiliteService;
import dev.sylvain.planning.service.espace.DemandeEchangeService;
import dev.sylvain.planning.service.publication.ConfirmationPlanningService;
import dev.sylvain.planning.service.publication.ConfirmationPlanningService.SyntheseConfirmations;
import dev.sylvain.planning.service.publication.PlanPublicationService;
import dev.sylvain.planning.service.publication.PlanPublicationService.ApercuPublication;
import dev.sylvain.planning.service.referentiel.ReferenceDataService;
import dev.sylvain.planning.service.solve.ConstraintAnalysisStore;
import dev.sylvain.planning.service.solve.ConstraintAnalysisStore.StoredAnalysis;
import dev.sylvain.planning.service.solve.PlanningPersistenceService;
import dev.sylvain.planning.service.solve.PlanningPersistenceService.PlanningResolution;
import dev.sylvain.planning.service.solve.SolverJobService;
import dev.sylvain.planning.solver.ConstraintCatalog;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Where the current edition stands in its cycle — the one aggregation the
 * home screen and the MCP tool {@code etat_edition} read (issue #485).
 *
 * <p>Nothing here is computed for the first time: every figure already had a
 * screen and a route. What was missing was the answer to "where am I?", which
 * asked the organiser to open nine screens and remember what each one said.
 * This service asks the nine services and lets {@link #assemble} decide the
 * state of each line. The decision is static and reads only the
 * {@link Facts}: the rules are tested on hand-built facts, the collection
 * through the resource test.</p>
 *
 * <p>It never fails on an empty edition — the first screen a new user sees is
 * this one — and it never touches the solver: a running solve is a state to
 * report, not a lock to wait for. Every read here is one the screens already
 * make while a solve runs.</p>
 */
@ApplicationScoped
public class EtatEditionService {

    @Inject
    EditionService editionService;

    @Inject
    EditionContext editionContext;

    @Inject
    ReferenceDataService referenceDataService;

    @Inject
    DeclarationDisponibiliteService declarationService;

    @Inject
    StaffingService staffingService;

    @Inject
    FeasibilityAnalyzer feasibilityAnalyzer;

    @Inject
    ConstraintAnalysisStore analysisStore;

    @Inject
    PlanningPersistenceService persistenceService;

    @Inject
    ReferenceDataChangeTracker changeTracker;

    @Inject
    SolverJobService solverJobService;

    @Inject
    PlanPublicationService publicationService;

    @Inject
    ConfirmationPlanningService confirmationService;

    @Inject
    DemandeEchangeService demandeEchangeService;

    /**
     * Everything {@link #assemble} decides on, read once per call.
     *
     * @param resolution        {@code null} while nothing was ever solved
     * @param diagnostic        the last analysis of the persisted plan held in
     *                          memory, {@code null} after a restart or before any solve
     * @param lastDataChange    when the reference data last moved, {@code null}
     *                          while nothing moved during this server run
     * @param solveEnCours      a solve holds this very edition
     * @param faisabilite       the pre-solve capacity check, always available
     * @param publication       what the next publication would announce
     * @param confirmations     the acknowledgements of the published plan
     */
    public record Facts(
            Edition edition,
            int stands,
            int animateurs,
            int creneaux,
            boolean collecteOuverte,
            int declarationsEnAttente,
            int declarationsTraitees,
            RapportOuvertures ouvertures,
            StaffingSummary staffing,
            PlanningResolution resolution,
            PlanningDiagnostic diagnostic,
            Instant lastDataChange,
            boolean solveEnCours,
            FeasibilityReport faisabilite,
            ApercuPublication publication,
            SyntheseConfirmations confirmations,
            boolean foireOuverte,
            int demandesEnAttente) {}

    /** The state of the current edition. */
    public EtatEditionView etat() {
        return assemble(facts());
    }

    private Facts facts() {
        Edition edition = editionService.editionCourante();
        List<DeclarationDisponibilite> declarations = declarationService.list();
        int enAttente = (int) declarations.stream()
                .filter(declaration -> declaration.getStatut() == StatutDeclaration.EN_ATTENTE)
                .count();
        StoredAnalysis analysis = analysisStore.latest();
        return new Facts(
                edition,
                referenceDataService.listStands().size(),
                referenceDataService.listAnimateurs().size(),
                referenceDataService.listCreneaux().size(),
                // The window as it applies today, not the switch alone: a
                // collection « open from 1 to 10 June », read in September, is
                // closed for everybody the server answers, and a line saying
                // « ouverte » kept an edition « à vérifier » for ever.
                declarationService.isCollecteOuverte(),
                enAttente,
                declarations.size() - enAttente,
                OuvertureStandsAnalyzer.analyze(
                        referenceDataService.listSolvedStands(), referenceDataService.listCreneaux()),
                staffingService.analyzeEdition(),
                persistenceService.loadResolution(),
                analysis == null ? null : analysis.diagnostic(),
                changeTracker.lastModifiedAt(),
                solveRunning(edition.getId()),
                feasibilityAnalyzer.analyze(
                        referenceDataService.listAnimateurs(),
                        referenceDataService.listSolvedStands(),
                        referenceDataService.listCreneaux(),
                        referenceDataService.listContraintesAdHoc()),
                publicationService.apercu(),
                confirmationService.synthese(),
                demandeEchangeService.isFoireOpen(),
                demandeEchangeService.pendingDemandes().size());
    }

    /**
     * Scoped to this edition, like the publication's own check: a solve on
     * the fallback variant does not make the edition being prepared "busy".
     */
    private boolean solveRunning(String editionId) {
        Optional<SolverJobService.SolverJob> actif = solverJobService.findActive();
        return actif.isPresent() && editionId.equals(actif.get().getEditionId());
    }

    /* ------------------------------ The rules ------------------------------ */

    /** The state of each line, decided on the facts alone. */
    public static EtatEditionView assemble(Facts facts) {
        boolean referentielsSaisis = facts.stands() > 0 && facts.animateurs() > 0 && facts.creneaux() > 0;
        return new EtatEditionView(
                facts.edition().getId(),
                facts.edition().getNom(),
                referentiels(facts, referentielsSaisis),
                collecte(facts),
                ouvertures(facts),
                besoin(facts),
                resolution(facts),
                problemes(facts, referentielsSaisis),
                publication(facts),
                confirmations(facts),
                foire(facts));
    }

    private static EtatReferentiels referentiels(Facts facts, boolean saisis) {
        return new EtatReferentiels(
                facts.stands(), facts.animateurs(), facts.creneaux(), saisis ? Statut.FAIT : Statut.A_FAIRE);
    }

    /**
     * A pending declaration is a decision to take; an open window is a step
     * not finished — the guide says to close it before solving. Closed with
     * nothing ever received is the step still ahead; closed after decisions
     * were taken is the step behind.
     */
    private static EtatCollecte collecte(Facts facts) {
        Statut statut;
        if (facts.declarationsEnAttente() > 0 || facts.collecteOuverte()) {
            statut = Statut.ATTENTION;
        } else if (facts.declarationsTraitees() > 0) {
            statut = Statut.FAIT;
        } else {
            statut = Statut.A_FAIRE;
        }
        return new EtatCollecte(
                facts.collecteOuverte(), facts.declarationsEnAttente(), facts.declarationsTraitees(), statut);
    }

    /** Nothing to read without stands and timeslots; an anomaly is a schedule the solver would misread. */
    private static EtatOuvertures ouvertures(Facts facts) {
        RapportOuvertures rapport = facts.ouvertures();
        int anomalies = rapport.anomalies().size();
        Statut statut;
        if (facts.stands() == 0 || facts.creneaux() == 0) {
            statut = Statut.A_FAIRE;
        } else if (anomalies > 0) {
            statut = Statut.ATTENTION;
        } else {
            statut = Statut.FAIT;
        }
        return new EtatOuvertures(anomalies, rapport.standsJamaisOuverts(), statut);
    }

    /** The roster against the bound the staffing screen retains — the same figure it shows as « Minimum retenu ». */
    private static EtatBesoin besoin(Facts facts) {
        StaffingSummary staffing = facts.staffing();
        int minimum = staffing.minimumTotal();
        int manque = Math.max(0, minimum - facts.animateurs());
        Statut statut;
        if (!staffing.referentielsManquants().isEmpty()) {
            statut = Statut.A_FAIRE;
        } else if (manque > 0) {
            statut = Statut.ATTENTION;
        } else {
            statut = Statut.FAIT;
        }
        return new EtatBesoin(facts.animateurs(), minimum, manque, statut);
    }

    /**
     * A running solve is reported, never waited for. Without a plan the step
     * is ahead; with one, stale data or a hard score above zero is something
     * to look at; a missing analysis (a restart) is not an alert, the date
     * is still there.
     */
    private static EtatResolution resolution(Facts facts) {
        PlanningResolution resolution = facts.resolution();
        boolean resolue = resolution != null;
        Instant resoluLe = resolue ? resolution.resoluLe() : null;
        boolean dataStale = resolue
                && facts.lastDataChange() != null
                && resoluLe != null
                && facts.lastDataChange().isAfter(resoluLe);
        PlanningDiagnostic diagnostic = resolue ? facts.diagnostic() : null;
        Boolean faisable = diagnostic == null ? null : diagnostic.hardScore() == 0;
        Statut statut;
        if (facts.solveEnCours()) {
            statut = Statut.ATTENTION;
        } else if (!resolue) {
            statut = Statut.A_FAIRE;
        } else if (dataStale || Boolean.FALSE.equals(faisable)) {
            statut = Statut.ATTENTION;
        } else {
            statut = Statut.FAIT;
        }
        return new EtatResolution(
                resolue,
                resoluLe,
                diagnostic == null ? null : diagnostic.score(),
                diagnostic == null ? null : diagnostic.scoreHorsPlancher(),
                faisable,
                dataStale,
                facts.solveEnCours(),
                statut);
    }

    /**
     * The same two sources the Problèmes tab merges: the capacity causes, at
     * any time, and the rules the last analysis found in default. A hard rule
     * blocks, a medium one warns; soft rules are not problems. Over an edition
     * with a referential missing the check says "feasible" for want of
     * anything to check, which would read as done — hence "to do".
     */
    private static EtatProblemes problemes(Facts facts, boolean referentielsSaisis) {
        // The counts, never the listed causes: that list is capped at ten for
        // reading, and counting it reported the size of the cap — « 10
        // bloquant(s) » on an edition with sixty timeslots short of somebody.
        int bloquants = facts.faisabilite().causesCritiques();
        int avertissements = facts.faisabilite().causesElevees();
        if (facts.diagnostic() != null && facts.resolution() != null) {
            for (ConstraintDiagnostic contrainte : facts.diagnostic().contraintes()) {
                if (contrainte.matchCount() <= 0) {
                    continue;
                }
                if (ConstraintCatalog.NOMS_DURS.contains(contrainte.name())) {
                    bloquants++;
                } else if (isMedium(contrainte.name())) {
                    avertissements++;
                }
            }
        }
        // A plan is saved, and no analysis is in memory: nothing has looked at
        // the rules since this process started. Saying « aucun problème
        // signalé » there acknowledges a measurement that never ran.
        boolean reglesAnalysees = facts.resolution() == null || facts.diagnostic() != null;
        Statut statut;
        if (!referentielsSaisis) {
            statut = Statut.A_FAIRE;
        } else if (bloquants > 0 || avertissements > 0 || !reglesAnalysees) {
            statut = Statut.ATTENTION;
        } else {
            statut = Statut.FAIT;
        }
        return new EtatProblemes(bloquants, avertissements, reglesAnalysees, statut);
    }

    private static boolean isMedium(String constraintName) {
        ConstraintCatalog.ConstraintDefinition definition = ConstraintCatalog.PAR_NOM.get(constraintName);
        return definition != null && definition.niveau() == ConstraintCatalog.Niveau.MEDIUM;
    }

    /**
     * Nothing to publish, or never published, is the step ahead; people to warn
     * is the step to redo.
     *
     * <p>While a solve runs, neither: publishing is refused outright
     * ({@code PlanPublicationService.publier} answers a conflict), and the
     * count would be read off a plan about to be rewritten. The line then says
     * what the « Dernière résolution » line says — wait — rather than inviting
     * a click that will be turned down.</p>
     */
    private static EtatPublication publication(Facts facts) {
        ApercuPublication apercu = facts.publication();
        Statut statut;
        if (facts.solveEnCours()) {
            // The count travels as it stands — a reader over MCP still gets the
            // figure — but the line is « à vérifier », and the screen words it
            // as a wait rather than as an invitation to publish.
            return new EtatPublication(
                    apercu.jamaisPublie(), apercu.dernierePublicationLe(), apercu.nombreConcernes(), Statut.ATTENTION);
        }
        if (apercu.planVide() || apercu.jamaisPublie()) {
            statut = Statut.A_FAIRE;
        } else if (apercu.nombreConcernes() > 0) {
            statut = Statut.ATTENTION;
        } else {
            statut = Statut.FAIT;
        }
        return new EtatPublication(
                apercu.jamaisPublie(), apercu.dernierePublicationLe(), apercu.nombreConcernes(), statut);
    }

    /** Somebody reminded is still somebody who has not answered. */
    private static EtatConfirmations confirmations(Facts facts) {
        SyntheseConfirmations synthese = facts.confirmations();
        Statut statut;
        if (synthese.jamaisPublie()) {
            statut = Statut.A_FAIRE;
        } else if (synthese.silencieux() > 0 || synthese.relances() > 0) {
            statut = Statut.ATTENTION;
        } else {
            statut = Statut.FAIT;
        }
        return new EtatConfirmations(synthese.confirmes(), synthese.relances(), synthese.silencieux(), statut);
    }

    /**
     * Open or closed is the organiser's choice, not a state to correct: only
     * a request waiting for a decision asks for something. Before the first
     * publication there is nothing to trade.
     */
    private static EtatFoire foire(Facts facts) {
        Statut statut;
        if (facts.confirmations().jamaisPublie()) {
            statut = Statut.A_FAIRE;
        } else if (facts.demandesEnAttente() > 0) {
            statut = Statut.ATTENTION;
        } else {
            statut = Statut.FAIT;
        }
        return new EtatFoire(facts.foireOuverte(), facts.demandesEnAttente(), statut);
    }
}
