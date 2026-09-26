package dev.sylvain.planning.service.edition;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.ContrainteAdHoc;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.DeclarationDisponibilite;
import dev.sylvain.planning.domain.DemandeEchange;
import dev.sylvain.planning.domain.Edition;
import dev.sylvain.planning.domain.ParametresNotifications;
import dev.sylvain.planning.domain.Stand;
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
import dev.sylvain.planning.service.backup.BackupRun;
import dev.sylvain.planning.service.backup.BackupService;
import dev.sylvain.planning.service.edition.EtatEditionView.EtatATraiter;
import dev.sylvain.planning.service.edition.EtatEditionView.EtatBesoin;
import dev.sylvain.planning.service.edition.EtatEditionView.EtatCoherence;
import dev.sylvain.planning.service.edition.EtatEditionView.EtatCollecte;
import dev.sylvain.planning.service.edition.EtatEditionView.EtatConfirmations;
import dev.sylvain.planning.service.edition.EtatEditionView.EtatEvenement;
import dev.sylvain.planning.service.edition.EtatEditionView.EtatFoire;
import dev.sylvain.planning.service.edition.EtatEditionView.EtatJour;
import dev.sylvain.planning.service.edition.EtatEditionView.EtatOuvertures;
import dev.sylvain.planning.service.edition.EtatEditionView.EtatProblemes;
import dev.sylvain.planning.service.edition.EtatEditionView.EtatPublication;
import dev.sylvain.planning.service.edition.EtatEditionView.EtatReferentiels;
import dev.sylvain.planning.service.edition.EtatEditionView.EtatRelecture;
import dev.sylvain.planning.service.edition.EtatEditionView.EtatResolution;
import dev.sylvain.planning.service.edition.EtatEditionView.Phase;
import dev.sylvain.planning.service.edition.EtatEditionView.Statut;
import dev.sylvain.planning.service.espace.DeclarationDisponibiliteService;
import dev.sylvain.planning.service.espace.DemandeEchangeService;
import dev.sylvain.planning.service.espace.JourJClock;
import dev.sylvain.planning.service.espace.JourJService;
import dev.sylvain.planning.service.espace.TimeslotWindows;
import dev.sylvain.planning.service.mural.AffichageMuralService;
import dev.sylvain.planning.service.mural.AffichageMuralView;
import dev.sylvain.planning.service.notification.AlerteEchangeJob;
import dev.sylvain.planning.service.notification.JournalNotificationsRepository;
import dev.sylvain.planning.service.notification.JournalNotificationsRepository.Alerte;
import dev.sylvain.planning.service.publication.ConfirmationPlanningService;
import dev.sylvain.planning.service.publication.ConfirmationPlanningService.SyntheseConfirmations;
import dev.sylvain.planning.service.publication.PlanPublicationService;
import dev.sylvain.planning.service.publication.PlanPublicationService.ApercuPublication;
import dev.sylvain.planning.service.referentiel.CoherenceAnalyzer;
import dev.sylvain.planning.service.referentiel.CoherenceReferentielService;
import dev.sylvain.planning.service.referentiel.CoherenceReferentielService.CoherenceReport;
import dev.sylvain.planning.service.referentiel.GelReferentielService;
import dev.sylvain.planning.service.referentiel.JoursEvenement;
import dev.sylvain.planning.service.referentiel.ReferenceDataService;
import dev.sylvain.planning.service.referentiel.ReferentialFamily;
import dev.sylvain.planning.service.solve.ConstraintAnalysisStore;
import dev.sylvain.planning.service.solve.ConstraintAnalysisStore.StoredAnalysis;
import dev.sylvain.planning.service.solve.PlanningPersistenceService;
import dev.sylvain.planning.service.solve.PlanningPersistenceService.PlanningResolution;
import dev.sylvain.planning.service.solve.PlanningService;
import dev.sylvain.planning.service.solve.SolverJobService;
import dev.sylvain.planning.service.validation.ValidationPrerequisService;
import dev.sylvain.planning.service.validation.ValidationPrerequisService.ProgressionValidations;
import dev.sylvain.planning.solver.ConstraintCatalog;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

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
 * this one — and it never <em>runs</em> the solver: a running solve is a state
 * to report, not a lock to wait for. Every read here is one the screens
 * already make while a solve runs.</p>
 */
@ApplicationScoped
public class EtatEditionService {

    private final EditionService editionService;

    private final EditionContext editionContext;

    private final ReferenceDataService referenceDataService;

    private final DeclarationDisponibiliteService declarationService;

    private final StaffingService staffingService;

    private final FeasibilityAnalyzer feasibilityAnalyzer;

    /**
     * For its {@code pastHorizon()} alone — see the note above on never
     * touching the solver: reading the moment the frozen past is judged against
     * (ADR 0044) starts nothing and waits for nothing.
     */
    private final PlanningService planningService;

    private final ConstraintAnalysisStore analysisStore;

    private final PlanningPersistenceService persistenceService;

    private final ReferenceDataChangeTracker changeTracker;

    private final SolverJobService solverJobService;

    private final PlanPublicationService publicationService;

    private final ConfirmationPlanningService confirmationService;

    private final DemandeEchangeService demandeEchangeService;

    private final ValidationPrerequisService prerequisService;

    private final CoherenceReferentielService coherenceService;

    private final JourJClock jourJClock;

    private final GelReferentielService gelService;

    /** The wall display's reading of the day under way: open stands and empty seats. */
    private final AffichageMuralService affichageMuralService;

    /** The mode jour J's reading of who is absent. */
    private final JourJService jourJService;

    /** The alerts the nightly jobs left behind. */
    private final JournalNotificationsRepository journalNotifications;

    private final BackupService backupService;

    @Inject
    public EtatEditionService(
            EditionService editionService,
            EditionContext editionContext,
            ReferenceDataService referenceDataService,
            DeclarationDisponibiliteService declarationService,
            StaffingService staffingService,
            FeasibilityAnalyzer feasibilityAnalyzer,
            PlanningService planningService,
            ConstraintAnalysisStore analysisStore,
            PlanningPersistenceService persistenceService,
            ReferenceDataChangeTracker changeTracker,
            SolverJobService solverJobService,
            PlanPublicationService publicationService,
            ConfirmationPlanningService confirmationService,
            DemandeEchangeService demandeEchangeService,
            ValidationPrerequisService prerequisService,
            CoherenceReferentielService coherenceService,
            JourJClock jourJClock,
            GelReferentielService gelService,
            AffichageMuralService affichageMuralService,
            JourJService jourJService,
            JournalNotificationsRepository journalNotifications,
            BackupService backupService) {
        this.editionService = editionService;
        this.editionContext = editionContext;
        this.referenceDataService = referenceDataService;
        this.declarationService = declarationService;
        this.staffingService = staffingService;
        this.feasibilityAnalyzer = feasibilityAnalyzer;
        this.planningService = planningService;
        this.analysisStore = analysisStore;
        this.persistenceService = persistenceService;
        this.changeTracker = changeTracker;
        this.solverJobService = solverJobService;
        this.publicationService = publicationService;
        this.confirmationService = confirmationService;
        this.demandeEchangeService = demandeEchangeService;
        this.prerequisService = prerequisService;
        this.coherenceService = coherenceService;
        this.jourJClock = jourJClock;
        this.gelService = gelService;
        this.affichageMuralService = affichageMuralService;
        this.jourJService = jourJService;
        this.journalNotifications = journalNotifications;
        this.backupService = backupService;
    }

    /** How many alerts of each kind the journal is read for: its own ceiling, never a screenful. */
    private static final int NIGHT_ALERTS_READ = 500;

    /**
     * How far ahead « journées proches » looks, in days, today included —
     * today and the six days after it: a week is the time left to read a day
     * before it is worked. A constant rather than a
     * setting, until somebody asks for another horizon.
     */
    public static final int UPCOMING_DAYS_HORIZON = 7;

    /**
     * After how many days without an answer since the publication somebody is
     * worth a reminder — the default of the « silencieux depuis » filter of the
     * Animateurs page ({@code SILENCE_JOURS_DEFAUT}), which the link opens on.
     */
    public static final int SILENCE_DAYS = 3;

    /**
     * What « À traiter aujourd'hui » reads besides the other blocks' facts.
     *
     * @param maintenant           now, on the recette clock when it is frozen
     * @param declarations         when each pending availability declaration was submitted
     * @param echanges             since when each swap request has waited on the organisation
     * @param joursEvenement       the days carrying a timeslot
     * @param relancesAutomatiques the nightly sends are armed on this edition
     * @param delaiRelanceHeures   how long a silence lasts before the nightly reminder
     * @param alertesNuit          the alerts the nightly jobs left in the journal
     * @param derniereSauvegarde   the last nightly backup attempt, {@code null} when none is configured
     * @param jour                 the day under way as the wall display and the mode jour J
     *                             count it, read only while the event runs — {@code null} otherwise
     * @param jourEnCours          the journée under way as those two screens name it
     *                             ({@link TimeslotWindows#currentDay}): still the last day at one
     *                             in the morning while its night shift runs — what the phase is
     *                             judged on, where {@code aujourdhui} stays the calendar date
     */
    public record TodayFacts(
            LocalDate aujourdhui,
            Instant maintenant,
            List<Instant> declarations,
            List<Instant> echanges,
            int ancienneteEchangeJours,
            List<LocalDate> joursEvenement,
            boolean relancesAutomatiques,
            int delaiRelanceHeures,
            List<Alerte> alertesNuit,
            BackupRun derniereSauvegarde,
            JourFacts jour,
            LocalDate jourEnCours) {

        /** No shift running past midnight: the journée under way is the calendar date. */
        public TodayFacts(
                LocalDate aujourdhui,
                Instant maintenant,
                List<Instant> declarations,
                List<Instant> echanges,
                int ancienneteEchangeJours,
                List<LocalDate> joursEvenement,
                boolean relancesAutomatiques,
                int delaiRelanceHeures,
                List<Alerte> alertesNuit,
                BackupRun derniereSauvegarde,
                JourFacts jour) {
            this(
                    aujourdhui,
                    maintenant,
                    declarations,
                    echanges,
                    ancienneteEchangeJours,
                    joursEvenement,
                    relancesAutomatiques,
                    delaiRelanceHeures,
                    alertesNuit,
                    derniereSauvegarde,
                    jour,
                    aujourdhui);
        }

        /** Nothing armed, nothing left by the night, no day read — what the rules' tests start from. */
        public TodayFacts(
                LocalDate aujourdhui,
                Instant maintenant,
                List<Instant> declarations,
                List<Instant> echanges,
                int ancienneteEchangeJours,
                List<LocalDate> joursEvenement) {
            this(
                    aujourdhui,
                    maintenant,
                    declarations,
                    echanges,
                    ancienneteEchangeJours,
                    joursEvenement,
                    false,
                    ParametresNotifications.DELAI_RELANCE_HEURES_PAR_DEFAUT,
                    List.of(),
                    null,
                    null);
        }
    }

    /**
     * The day under way, as the services that already count it say it.
     *
     * @param date          the journée under way, as the wall display reads it
     * @param standsOuverts stands holding at least one seat that day
     * @param placesVides   seats of that day nobody holds
     * @param absents       animateurs marked absent on one of its timeslots
     */
    public record JourFacts(LocalDate date, int standsOuverts, int placesVides, int absents) {}

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
     * @param coherence         the coherence checklist of the referential
     * @param today             what « À traiter aujourd'hui » reads on top of the rest
     */
    public record Facts(
            Edition edition,
            int stands,
            int animateurs,
            int creneaux,
            int typologiesOrphelines,
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
            int demandesEnAttente,
            ProgressionValidations relecture,
            CoherenceReport coherence,
            TodayFacts today,
            List<GelReferentielService.EtatGel> gel) {

        /** The facts of an edition whose referential nothing freezes — what the rules' tests start from. */
        public Facts(
                Edition edition,
                int stands,
                int animateurs,
                int creneaux,
                int typologiesOrphelines,
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
                int demandesEnAttente,
                ProgressionValidations relecture,
                CoherenceReport coherence,
                TodayFacts today) {
            this(
                    edition,
                    stands,
                    animateurs,
                    creneaux,
                    typologiesOrphelines,
                    collecteOuverte,
                    declarationsEnAttente,
                    declarationsTraitees,
                    ouvertures,
                    staffing,
                    resolution,
                    diagnostic,
                    lastDataChange,
                    solveEnCours,
                    faisabilite,
                    publication,
                    confirmations,
                    foireOuverte,
                    demandesEnAttente,
                    relecture,
                    coherence,
                    today,
                    List.of());
        }
    }

    /** The state of the current edition. */
    public EtatEditionView etat() {
        return assemble(facts());
    }

    private Facts facts() {
        Edition edition = editionService.editionCourante();
        List<DeclarationDisponibilite> declarations = declarationService.list();
        List<Instant> declarationsEnAttente = declarations.stream()
                .filter(declaration -> declaration.getStatut() == StatutDeclaration.EN_ATTENTE)
                .map(DeclarationDisponibilite::getCreeLe)
                .toList();
        int enAttente = declarationsEnAttente.size();
        List<DemandeEchange> demandes = demandeEchangeService.pendingDemandes();
        StoredAnalysis analysis = analysisStore.latest();
        // Read once and handed to every reader below: the stands, resolved,
        // and the timeslots feed the counts, the opening report, the
        // feasibility check and the coherence checklist alike.
        List<Stand> stands = referenceDataService.listSolvedStands();
        List<Creneau> creneaux = referenceDataService.listCreneaux();
        List<Animateur> animateurs = referenceDataService.listAnimateurs();
        // One reading of the clock: today's date and the instant ages are
        // measured to can then never straddle midnight.
        LocalDateTime moment = jourJClock.dateTime();
        // Computed once and read twice: by their own lines, and by the
        // coherence checklist that lists their anomalies.
        RapportOuvertures ouvertures = OuvertureStandsAnalyzer.analyze(stands, creneaux);
        StaffingSummary staffing = staffingService.analyzeEdition();
        List<LocalDate> joursEvenement = creneaux.stream()
                .map(Creneau::getDate)
                .filter(Objects::nonNull)
                .distinct()
                .sorted()
                .toList();
        ParametresNotifications notifications = referenceDataService.getParametresNotifications();
        List<ContrainteAdHoc> contraintes = referenceDataService.listContraintesAdHoc();
        // The journée under way as the wall display and the mode jour J name
        // it: at one in the morning, the last day's night shift still runs.
        LocalDate jourEnCours = dayUnderWayOf(creneaux, moment);
        return new Facts(
                edition,
                stands.size(),
                animateurs.size(),
                creneaux.size(),
                CoherenceAnalyzer.countOrphanTypologies(referenceDataService.listTypologies(), stands, animateurs),
                // The window as it applies today, not the switch alone: a
                // collection « open from 1 to 10 June », read in September, is
                // closed for everybody the server answers, and a line saying
                // « ouverte » kept an edition « à vérifier » for ever.
                declarationService.isCollecteOuverte(),
                enAttente,
                declarations.size() - enAttente,
                ouvertures,
                staffing,
                persistenceService.loadResolution(),
                analysis == null ? null : analysis.diagnostic(),
                changeTracker.lastModifiedAt(),
                solveRunning(edition.getId()),
                feasibilityAnalyzer.analyze(
                        animateurs,
                        stands,
                        creneaux,
                        contraintes,
                        FeasibilityAnalyzer.encadrementMineursActif(referenceDataService.getContraintesDesactivees()),
                        new FeasibilityAnalyzer.PlanContext(
                                referenceDataService.listVerrouillages(),
                                persistenceService::loadPlacesTenues,
                                planningService.pastHorizon())),
                publicationService.apercu(),
                confirmationService.synthese(),
                demandeEchangeService.isFoireOpen(),
                demandes.size(),
                prerequisService.progression(),
                coherenceService.report(stands, creneaux, ouvertures, staffing),
                new TodayFacts(
                        moment.toLocalDate(),
                        // The real clock's reading is the machine's local time,
                        // so the system zone turns it back into the very instant
                        // the nightly swap alert compares against — that job's
                        // own zone only decides when it runs, never an age.
                        moment.atZone(ZoneId.systemDefault()).toInstant(),
                        declarationsEnAttente,
                        demandes.stream()
                                .map(AlerteEchangeJob::waitingSince)
                                .filter(Objects::nonNull)
                                .toList(),
                        notifications.ancienneteEchangeJours(),
                        joursEvenement,
                        notifications.actives(),
                        notifications.delaiRelanceHeures(),
                        stillStanding(
                                journalNotifications.alertes(NIGHT_ALERTS_READ),
                                animateurs,
                                () -> Set.copyOf(confirmationService.unconfirmed())),
                        backupService.lastConfiguredRun(),
                        phase(joursEvenement, jourEnCours) == Phase.EVENEMENT
                                ? dayUnderWay(creneaux, contraintes)
                                : null,
                        jourEnCours),
                gelService.etat());
    }

    /**
     * The journée under way at {@code moment}, read the way the wall display
     * and the mode jour J read it — {@link TimeslotWindows#currentDay}: a
     * 22:00–02:00 shift keeps its evening's date until it ends, and a moment
     * no timeslot covers falls back on the calendar date.
     */
    static LocalDate dayUnderWayOf(List<Creneau> creneaux, LocalDateTime moment) {
        return TimeslotWindows.currentDay(creneaux, moment);
    }

    /**
     * The alerts of the night that still say something true. A day-before
     * reminder that could not leave is about a fiche without an address: once
     * the address is there, the night's next run writes to it. A reminder of
     * the silent that could not leave — the night's or a manual one, no
     * address or a failed send — is about somebody who has not answered: once
     * they have, there is nobody left to chase. The other kinds pass as they
     * are; so do the alerts about a fiche since deleted, which nothing reads
     * any more — the day-before count leaves them out below.
     *
     * @param silencieux the ids still silent on the published plan, read only
     *                   when a reminder alert is there to be checked
     */
    static List<Alerte> stillStanding(
            List<Alerte> alertes, List<Animateur> animateurs, Supplier<Set<String>> silencieux) {
        Set<String> sansAdresse = animateurs.stream()
                .filter(animateur ->
                        animateur.getEmail() == null || animateur.getEmail().isBlank())
                .map(Animateur::getId)
                .collect(Collectors.toSet());
        String rappel = JournalNotificationsRepository.Type.RAPPEL_VEILLE_INJOIGNABLE.name();
        String relance = JournalNotificationsRepository.Type.RELANCE_INJOIGNABLE.name();
        Set<String> encoreSilencieux =
                alertes.stream().anyMatch(alerte -> relance.equals(alerte.type())) ? silencieux.get() : Set.of();
        return alertes.stream()
                .filter(alerte -> !rappel.equals(alerte.type()) || sansAdresse.contains(alerte.animateurId()))
                .filter(alerte -> !relance.equals(alerte.type()) || encoreSilencieux.contains(alerte.animateurId()))
                .toList();
    }

    /**
     * The day under way, read by the two screens that already answer it: the
     * wall display for the stands it shows and their empty seats, the mode
     * jour J for the absences — over the timeslots and the ad hoc constraints
     * this call already read. Only ever called while the event runs.
     */
    private JourFacts dayUnderWay(List<Creneau> creneaux, List<ContrainteAdHoc> contraintes) {
        AffichageMuralView vue = affichageMuralService.currentEditionView();
        int placesVides = vue.stands().stream()
                .flatMap(stand -> stand.vacations().stream())
                .mapToInt(AffichageMuralView.MuralShift::emptySeats)
                .sum();
        return new JourFacts(
                vue.jour(),
                vue.stands().size(),
                placesVides,
                jourJService.absentCount(vue.jour(), creneaux, contraintes));
    }

    /** Before the first day, from the first to the last, after the last; before, too, without any day. */
    static Phase phase(List<LocalDate> joursEvenement, LocalDate aujourdhui) {
        JoursEvenement jours = new JoursEvenement(joursEvenement);
        LocalDate premier = jours.first();
        LocalDate dernier = jours.last();
        if (premier == null || aujourdhui.isBefore(premier)) {
            return Phase.PREPARATION;
        }
        return aujourdhui.isAfter(dernier) ? Phase.APRES : Phase.EVENEMENT;
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
                gel(facts.gel()),
                coherence(facts),
                collecte(facts),
                ouvertures(facts),
                besoin(facts),
                resolution(facts),
                problemes(facts, referentielsSaisis),
                relecture(facts),
                publication(facts),
                confirmations(facts),
                foire(facts),
                aTraiter(facts),
                evenement(facts.today()));
    }

    /** The freeze, family by family — see {@link EtatEditionView.EtatGelReferentiel} for its state. */
    static EtatEditionView.EtatGelReferentiel gel(List<GelReferentielService.EtatGel> familles) {
        boolean preparationFigee = familles.stream()
                        .filter(famille -> famille.famille() == ReferentialFamily.STANDS
                                || famille.famille() == ReferentialFamily.CRENEAUX)
                        .filter(GelReferentielService.EtatGel::fige)
                        .count()
                == 2;
        return new EtatEditionView.EtatGelReferentiel(
                familles, preparationFigee ? EtatEditionView.Statut.FAIT : EtatEditionView.Statut.INFO);
    }

    /**
     * The event's bounds, and whether it is over: its last day is behind
     * today. The days are the ones carrying a timeslot, derived rather than
     * stored: without any, the edition has no end to be past.
     */
    static EtatEvenement evenement(TodayFacts today) {
        JoursEvenement jours = new JoursEvenement(today.joursEvenement());
        // last() is null on an edition without a timeslot: read it once and test that.
        LocalDate premier = jours.first();
        LocalDate dernier = jours.last();
        // Judged on the journée under way, not the calendar date: at one in
        // the morning after the last day, its night shift still runs.
        boolean termine = dernier != null && dernier.isBefore(today.jourEnCours());
        Phase phase = phase(today.joursEvenement(), today.jourEnCours());
        EtatJour jour = null;
        if (phase == Phase.EVENEMENT && today.jour() != null) {
            JourFacts facts = today.jour();
            // Ranked on the calendar from the first day: a day without a
            // timeslot in between still counts, as it does on a wall calendar.
            int numero = (int) ChronoUnit.DAYS.between(premier, facts.date()) + 1;
            jour = new EtatJour(
                    facts.date(),
                    numero,
                    facts.standsOuverts(),
                    facts.placesVides(),
                    facts.absents(),
                    today.echanges().size());
        }
        return new EtatEvenement(premier, dernier, termine, today.aujourdhui(), phase, jour);
    }

    private static EtatReferentiels referentiels(Facts facts, boolean saisis) {
        Statut statut;
        if (!saisis) {
            statut = Statut.A_FAIRE;
        } else {
            statut = facts.typologiesOrphelines() > 0 ? Statut.ATTENTION : Statut.FAIT;
        }
        return new EtatReferentiels(
                facts.stands(), facts.animateurs(), facts.creneaux(), facts.typologiesOrphelines(), statut);
    }

    /**
     * Something to fix or to check is « à vérifier »; information alone — a
     * minor, rules that overlap — is read, never acted upon. An empty edition
     * has nothing to be incoherent about: the Référentiels line already says
     * the step is ahead, and this one does not repeat it.
     */
    static EtatCoherence coherence(Facts facts) {
        CoherenceReport rapport = facts.coherence();
        Statut statut;
        if (rapport.bloquants() > 0 || rapport.aVerifier() > 0) {
            statut = Statut.ATTENTION;
        } else if (rapport.informations() > 0) {
            statut = Statut.INFO;
        } else {
            statut = Statut.FAIT;
        }
        return new EtatCoherence(rapport.bloquants(), rapport.aVerifier(), rapport.informations(), statut);
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

    /**
     * Nothing to read without stands and timeslots; an anomaly is a schedule the
     * solver would misread. The anomalies about how the rules are written —
     * overlapping rules or windows, a rule no day reads — are counted with the
     * others but only for information: the resolution settles them, and a line
     * « à vérifier » nobody can clear without rewriting a deliberate peak rule
     * would teach the reader to skip it.
     */
    private static EtatOuvertures ouvertures(Facts facts) {
        RapportOuvertures rapport = facts.ouvertures();
        int anomalies = rapport.anomalies().size();
        int fenetresSansEffet = (int) rapport.anomalies().stream()
                .filter(anomalie -> anomalie.type() == OuvertureStandsAnalyzer.AnomalyType.FENETRE_SANS_EFFET)
                .count();
        int informations = (int) rapport.anomalies().stream()
                .filter(anomalie -> anomalie.type().isInformational())
                .count();
        Statut statut;
        if (facts.stands() == 0 || facts.creneaux() == 0) {
            statut = Statut.A_FAIRE;
        } else if (anomalies > informations) {
            statut = Statut.ATTENTION;
        } else if (informations > 0) {
            statut = Statut.INFO;
        } else {
            statut = Statut.FAIT;
        }
        return new EtatOuvertures(anomalies, fenetresSansEffet, rapport.standsJamaisOuverts(), informations, statut);
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
                statut,
                diagnostic == null || diagnostic.lecture() == null ? List.of() : diagnostic.lecture());
    }

    /**
     * The same two sources the Problèmes tab merges: the capacity causes, at
     * any time, and the rules the last analysis found in default. A hard rule
     * blocks, a medium one warns; soft rules are not problems. Over an edition
     * with a referential missing the check says "feasible" for want of
     * anything to check, which would read as done — hence "to do".
     *
     * <p>Warnings alone are {@code INFO}, not {@code ATTENTION}: a plan with
     * zero medium rules in default is all but unreachable on a real event, so
     * an alert on that count is an alert nobody can ever clear, and the reader
     * learns to scroll past the one line that will one day be a refusal. What
     * blocks is a hard rule or a critical capacity cause — or a measurement
     * that never ran, which is not an acknowledgement to give.</p>
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
        return new EtatProblemes(
                bloquants,
                avertissements,
                reglesAnalysees,
                statutProblemes(referentielsSaisis, bloquants, avertissements, reglesAnalysees));
    }

    private static Statut statutProblemes(
            boolean referentielsSaisis, int bloquants, int avertissements, boolean reglesAnalysees) {
        if (!referentielsSaisis) {
            return Statut.A_FAIRE;
        }
        if (bloquants > 0 || !reglesAnalysees) {
            return Statut.ATTENTION;
        }
        return avertissements > 0 ? Statut.INFO : Statut.FAIT;
    }

    private static boolean isMedium(String constraintName) {
        ConstraintCatalog.ConstraintDefinition definition = ConstraintCatalog.PAR_NOM.get(constraintName);
        return definition != null && definition.niveau() == ConstraintCatalog.Niveau.MEDIUM;
    }

    /**
     * The relecture is a step of its own between a plan and its publication: it
     * only begins once there is a plan to read, and it is behind only when
     * every day of the edition has been accepted.
     */
    private static EtatRelecture relecture(Facts facts) {
        ProgressionValidations progression = facts.relecture();
        Statut statut;
        if (facts.resolution() == null || progression.journees() == 0) {
            statut = Statut.A_FAIRE;
        } else if (progression.journeesValidees() >= progression.journees()) {
            statut = Statut.FAIT;
        } else if (progression.journeesValidees() > 0) {
            statut = Statut.INFO;
        } else {
            statut = Statut.A_FAIRE;
        }
        return new EtatRelecture(progression.journees(), progression.journeesValidees(), statut);
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
                    apercu.jamaisPublie(),
                    apercu.dernierePublicationLe(),
                    apercu.nombreConcernes(),
                    Statut.ATTENTION,
                    apercu.envoisEnEchec());
        }
        if (apercu.planVide() || apercu.jamaisPublie()) {
            statut = Statut.A_FAIRE;
        } else if (apercu.nombreConcernes() > 0 || apercu.envoisEnEchec() > 0) {
            // A publication whose mails bounced is not « à jour »: nothing
            // changed for those people, they simply never received it.
            statut = Statut.ATTENTION;
        } else {
            statut = Statut.FAIT;
        }
        return new EtatPublication(
                apercu.jamaisPublie(),
                apercu.dernierePublicationLe(),
                apercu.nombreConcernes(),
                statut,
                apercu.envoisEnEchec());
    }

    /**
     * Somebody reminded is still somebody who has not answered — but a silence
     * an hour after the publication is not yet one: the line only asks for
     * attention once the reminder delay set in Paramètres › E-mails has run
     * out, the delay after which the nightly job itself would have written.
     * Before that, the figures are read for information.
     */
    static EtatConfirmations confirmations(Facts facts) {
        SyntheseConfirmations synthese = facts.confirmations();
        TodayFacts today = facts.today();
        Statut statut;
        if (synthese.jamaisPublie()) {
            statut = Statut.A_FAIRE;
        } else if (synthese.silencieux() == 0 && synthese.relances() == 0) {
            statut = Statut.FAIT;
        } else if (synthese.dernierePublicationLe() == null
                || Duration.between(synthese.dernierePublicationLe(), today.maintenant())
                                .compareTo(Duration.ofHours(today.delaiRelanceHeures()))
                        >= 0) {
            statut = Statut.ATTENTION;
        } else {
            statut = Statut.INFO;
        }
        return new EtatConfirmations(
                synthese.confirmes(),
                synthese.relances(),
                synthese.silencieux(),
                statut,
                today.relancesAutomatiques(),
                today.delaiRelanceHeures());
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

    /**
     * « À traiter aujourd'hui », decided on the facts alone. Every subject is
     * a count the screen hides at zero; the block as a whole says nothing when
     * all of them are.
     *
     * <p>Two subjects wait for a first publication — nobody is silent and
     * nobody is to be told before anything was sent — and two go quiet while a
     * solve runs: the data it reads is the new one, and the people to tell
     * are read off a plan about to be rewritten. The days to read wait for a
     * plan too: there is nothing to accept on an edition never solved.</p>
     */
    static EtatATraiter aTraiter(Facts facts) {
        TodayFacts today = facts.today();
        LocalDate aujourdhui = today.aujourdhui();

        Duration threshold = Duration.ofDays(today.ancienneteEchangeJours());
        int enAlerte = (int) today.echanges().stream()
                .filter(depuis -> Duration.between(depuis, today.maintenant()).compareTo(threshold) >= 0)
                .count();

        List<LocalDate> unread = List.of();
        if (facts.resolution() != null) {
            Set<LocalDate> relues = new HashSet<>(facts.relecture().joursValides());
            // Today counts as the first of the horizon's days.
            LocalDate last = aujourdhui.plusDays(UPCOMING_DAYS_HORIZON - 1L);
            unread = today.joursEvenement().stream()
                    .filter(jour -> !jour.isBefore(aujourdhui) && !jour.isAfter(last))
                    .filter(jour -> !relues.contains(jour))
                    .sorted()
                    .toList();
        }

        SyntheseConfirmations confirmations = facts.confirmations();
        boolean silenceEcoule = !confirmations.jamaisPublie()
                && confirmations.dernierePublicationLe() != null
                && Duration.between(confirmations.dernierePublicationLe(), today.maintenant())
                                .compareTo(Duration.ofDays(SILENCE_DAYS))
                        > 0;

        ApercuPublication publication = facts.publication();
        int aPrevenir = publication.jamaisPublie() || facts.solveEnCours() ? 0 : publication.nombreConcernes();

        BackupRun sauvegarde = today.derniereSauvegarde();
        boolean sauvegardeEnEchec = sauvegarde != null && sauvegarde.ranAtLeastOnce() && !sauvegarde.succeeded();

        return new EtatATraiter(
                aujourdhui,
                today.declarations().size(),
                today.declarations().stream()
                        .filter(Objects::nonNull)
                        .min(Instant::compareTo)
                        .orElse(null),
                today.echanges().size(),
                enAlerte,
                today.ancienneteEchangeJours(),
                today.echanges().stream().min(Instant::compareTo).orElse(null),
                UPCOMING_DAYS_HORIZON,
                unread,
                silenceEcoule ? confirmations.silencieux() : 0,
                SILENCE_DAYS,
                !facts.solveEnCours() && resolution(facts).dataStale(),
                aPrevenir,
                unsentDayBeforeReminders(today.alertesNuit(), aujourdhui),
                unsentReminders(today.alertesNuit(), confirmations),
                sauvegardeEnEchec,
                sauvegardeEnEchec ? sauvegarde.attemptedAt() : null);
    }

    /**
     * The day-before reminders the nightly job could not send, for a day still
     * ahead — among the alerts {@link #stillStanding} kept, so about a fiche
     * still without an address: past that day, the person has worked it and
     * the alert says nothing any more. The day is the second half of the alert's key
     * ({@code animateurId|date}); a key that does not read as one is left out
     * rather than guessed.
     */
    static int unsentDayBeforeReminders(List<Alerte> alertes, LocalDate aujourdhui) {
        return (int) alertes.stream()
                .filter(alerte -> JournalNotificationsRepository.Type.RAPPEL_VEILLE_INJOIGNABLE
                        .name()
                        .equals(alerte.type()))
                .map(alerte -> dayOfKey(alerte.cle()))
                .filter(jour -> jour != null && !jour.isBefore(aujourdhui))
                .count();
    }

    /**
     * The reminders of the silent that could not leave — the night's or a
     * manual one — since the last publication, among the alerts
     * {@link #stillStanding} kept, so about somebody still silent: one about
     * an older plan was about a schedule the publication since has replaced.
     */
    static int unsentReminders(List<Alerte> alertes, SyntheseConfirmations confirmations) {
        Instant publication = confirmations.dernierePublicationLe();
        if (confirmations.jamaisPublie() || publication == null) {
            return 0;
        }
        return (int) alertes.stream()
                .filter(alerte -> JournalNotificationsRepository.Type.RELANCE_INJOIGNABLE
                        .name()
                        .equals(alerte.type()))
                .filter(alerte ->
                        alerte.declencheLe() != null && !alerte.declencheLe().isBefore(publication))
                .count();
    }

    private static LocalDate dayOfKey(String cle) {
        int separateur = cle == null ? -1 : cle.lastIndexOf('|');
        if (separateur < 0) {
            return null;
        }
        try {
            return LocalDate.parse(cle.substring(separateur + 1));
        } catch (DateTimeParseException _) {
            return null;
        }
    }
}
