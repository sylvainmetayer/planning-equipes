package dev.sylvain.planning.service.referentiel;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.ContrainteAdHoc;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.PastHorizon;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.domain.TypeContrainteAdHoc;
import dev.sylvain.planning.domain.VerrouillagePlanning;
import dev.sylvain.planning.service.analyse.OuvertureStandsAnalyzer;
import dev.sylvain.planning.service.analyse.OuvertureStandsAnalyzer.RapportOuvertures;
import dev.sylvain.planning.service.analyse.PlanningDiagnosticService.ConstraintDiagnostic;
import dev.sylvain.planning.service.analyse.StaffingAnalyzer.StaffingSummary;
import dev.sylvain.planning.service.analyse.StaffingAnalyzer.TypologieStaffing;
import dev.sylvain.planning.service.analyse.StaffingService;
import dev.sylvain.planning.service.solve.ConstraintAnalysisStore;
import dev.sylvain.planning.service.solve.PlanningPersistenceService;
import dev.sylvain.planning.service.solve.PlanningService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * The coherence checklist of the referential: every anomaly the application
 * already detects, gathered in one place and recomputed on the whole edition
 * — not only on the fiche just saved.
 *
 * <p><b>No rule of its own.</b> Every line comes from an analyzer that already
 * exists, with its own sentence: the write-time warnings of
 * {@link CoherenceAnalyzer} replayed on every animateur, timeslot, stand and
 * lock; the opening report of {@link OuvertureStandsAnalyzer} as the Ouvertures
 * screen shows it; the grid check of the Créneaux screen; the contradictory or
 * untenable ad hoc exceptions exactly as Diagnostic → Problèmes words them; the
 * staffing bound of Diagnostic → Besoin. What was missing is the one list: a
 * warning read once at save time and dismissed was lost, and a timeslot left
 * outside every opening by a later edit of a <em>stand</em> was said nowhere.</p>
 *
 * <p>Two write-time warnings are not replayed as such, because the opening
 * report states the same fact on the same stand: {@code STAND_FENETRE_SANS_EFFET}
 * is the report's {@code FENETRE_SANS_EFFET}, {@code STAND_JAMAIS_OUVERT} its
 * {@code STAND_JAMAIS_OUVERT}. Listing both would count one mistake twice.</p>
 *
 * <p>The severity is the source's: a critical cause of the feasibility check
 * blocks, a write-time warning is to check, the anomalies about how rules are
 * written and the minority of an animateur are for information — a minor is a
 * modelled state, not an error to correct.</p>
 *
 * <p>The rules are in {@link #build}, static, tested without a container; this
 * bean only reads. It never runs the solver and never waits for one: the
 * referential is read as it stands, and the lock warning uses the analysis the
 * store already holds, or none.</p>
 */
@ApplicationScoped
public class CoherenceReferentielService {

    private final ReferenceDataService referenceDataService;

    private final StaffingService staffingService;

    /** Resolves the stands the write-time warnings are replayed on, without the consigne layer. */
    private final CoherenceService coherence;

    /** Behind {@code Instance} like {@link CoherenceService}'s: the plain-Java harnesses have no database. */
    private final Instance<ConstraintAnalysisStore> analyses;

    private final Instance<PlanningPersistenceService> plan;

    private final Instance<PlanningService> planning;

    @Inject
    public CoherenceReferentielService(
            ReferenceDataService referenceDataService,
            StaffingService staffingService,
            CoherenceService coherence,
            Instance<ConstraintAnalysisStore> analyses,
            Instance<PlanningPersistenceService> plan,
            Instance<PlanningService> planning) {
        this.referenceDataService = referenceDataService;
        this.staffingService = staffingService;
        this.coherence = coherence;
        this.analyses = analyses;
        this.plan = plan;
        this.planning = planning;
    }

    /** The families the checklist is grouped by, in reading order. */
    public enum CoherenceFamily {
        STANDS,
        CRENEAUX,
        ANIMATEURS,
        AJUSTEMENTS,
        CAPACITE
    }

    /** How much a line holds the edition back, most blocking first. */
    public enum CoherenceSeverity {
        BLOQUANT,
        A_VERIFIER,
        INFORMATION
    }

    /** What a line is about — the fiche or screen that corrects it follows from it. */
    public enum CoherenceSubject {
        STAND,
        CRENEAU,
        ANIMATEUR,
        CONTRAINTE_AD_HOC,
        VERROUILLAGE,
        TYPOLOGIE,
        EDITION
    }

    /**
     * One anomaly.
     *
     * @param code     the source's own name for it: a {@link TypeAvertissement}, an opening
     *                 {@link OuvertureStandsAnalyzer.AnomalyType}, a grid anomaly type, a
     *                 feasibility cause type, or {@code BESOIN_NON_COUVERT} /
     *                 {@code TYPOLOGIE_EN_MANQUE} for the staffing bound
     * @param message  the source's sentence, unchanged; an animateur is named by id only
     * @param objetId  the id of what {@code objet} designates, {@code null} for the edition
     *                 as a whole
     * @param date     the day concerned, when the source names one
     */
    @Schema(requiredProperties = {"code", "famille", "gravite", "message", "objet"})
    public record CoherenceIssue(
            CoherenceFamily famille,
            CoherenceSeverity gravite,
            String code,
            String message,
            CoherenceSubject objet,
            String objetId,
            LocalDate date) {}

    /** The counts of one family, the header of its group. */
    @Schema(requiredProperties = {"aVerifier", "bloquants", "famille", "informations"})
    public record FamilyCount(CoherenceFamily famille, int bloquants, int aVerifier, int informations) {}

    /**
     * The checklist.
     *
     * @param familles one count per family, every family listed even at zero
     * @param anomalies every line, by family, then severity, then code
     */
    @Schema(requiredProperties = {"aVerifier", "anomalies", "bloquants", "familles", "informations"})
    public record CoherenceReport(
            int bloquants,
            int aVerifier,
            int informations,
            List<FamilyCount> familles,
            List<CoherenceIssue> anomalies) {}

    /**
     * Everything {@link #build} reads, gathered once.
     *
     * @param stands       resolved against {@code creneaux}, as the solver reads them
     * @param ownHoursStands the same stands with their own hours only — rules and dated
     *                     exceptions, no consigne: the write-time warnings are replayed on
     *                     them, as {@link CoherenceService} raises them. Against the
     *                     consigne layer, every timeslot inside a band would read as
     *                     outside every opening, which is the consigne, not a mistake
     * @param placesTenues the seats of the persisted plan, read only when a lock and a
     *                     forced assignment both exist
     * @param horizon      the frozen past (ADR 0044), {@code null} when off
     * @param diagnostics  the constraints of the latest analysis held in memory, empty
     *                     without one: the lock warning then has nothing to say
     */
    public record Sources(
            List<Animateur> animateurs,
            List<Creneau> creneaux,
            List<Stand> stands,
            List<Stand> ownHoursStands,
            List<ContrainteAdHoc> contraintesAdHoc,
            List<VerrouillagePlanning> verrouillages,
            Supplier<Set<ForcedAssignmentOnLockedSchedule.PlaceTenue>> placesTenues,
            PastHorizon horizon,
            List<ConstraintDiagnostic> diagnostics,
            List<CreneauGridService.GridAnomaly> grille,
            RapportOuvertures ouvertures,
            StaffingSummary staffing) {}

    /** The checklist of the current edition, every source read now. */
    public CoherenceReport report() {
        List<Stand> stands = referenceDataService.listSolvedStands();
        List<Creneau> creneaux = referenceDataService.listCreneaux();
        return report(
                stands, creneaux, OuvertureStandsAnalyzer.analyze(stands, creneaux), staffingService.analyzeEdition());
    }

    /**
     * The checklist with what the caller already holds — the home screen has
     * read the stands and timeslots and computed both costly reports for its
     * own lines, and pays each of them once.
     *
     * @param stands   the edition's stands, resolved ({@code listSolvedStands})
     * @param creneaux the edition's timeslots
     */
    public CoherenceReport report(
            List<Stand> stands, List<Creneau> creneaux, RapportOuvertures ouvertures, StaffingSummary staffing) {
        List<VerrouillagePlanning> verrouillages = referenceDataService.listVerrouillages();
        return build(new Sources(
                referenceDataService.listAnimateurs(),
                creneaux,
                stands,
                coherence.standsOnOwnHours(creneaux),
                referenceDataService.listContraintesAdHoc(),
                verrouillages,
                () -> verrouillages.isEmpty() || !plan.isResolvable()
                        ? Set.of()
                        : plan.get().loadPlacesTenues(),
                planning.isResolvable() ? planning.get().pastHorizon() : null,
                latestDiagnostics(),
                referenceDataService.gridAnomalies(creneaux, verrouillages),
                ouvertures,
                staffing));
    }

    private List<ConstraintDiagnostic> latestDiagnostics() {
        if (!analyses.isResolvable()) {
            return List.of();
        }
        ConstraintAnalysisStore.StoredAnalysis stockee = analyses.get().latest();
        return stockee == null || stockee.diagnostic() == null
                ? List.of()
                : stockee.diagnostic().contraintes();
    }

    /* ------------------------------ The rules ------------------------------ */

    /** The checklist, decided on the sources alone. */
    public static CoherenceReport build(Sources sources) {
        List<CoherenceIssue> issues = new ArrayList<>();
        JoursEvenement jours = JoursEvenement.of(sources.creneaux());

        standIssues(sources, issues);
        timeslotIssues(sources, issues);

        for (Animateur animateur : sources.animateurs()) {
            CoherenceAnalyzer.onAnimateur(animateur, jours)
                    .forEach(avertissement -> issues.add(warning(
                            CoherenceFamily.ANIMATEURS, avertissement, CoherenceSubject.ANIMATEUR, animateur.getId())));
        }

        adHoc(sources, issues);
        for (VerrouillagePlanning verrouillage : sources.verrouillages()) {
            CoherenceAnalyzer.onVerrouillage(verrouillage, sources.diagnostics(), sources.creneaux())
                    .forEach(avertissement -> issues.add(warning(
                            CoherenceFamily.AJUSTEMENTS,
                            avertissement,
                            CoherenceSubject.VERROUILLAGE,
                            verrouillage.getId())));
        }

        capacity(sources, issues);
        return summarize(issues);
    }

    private static void standIssues(Sources sources, List<CoherenceIssue> issues) {
        for (Stand stand : sources.ownHoursStands()) {
            // The two other stand warnings are the opening report's own
            // anomalies, read below: see the class javadoc.
            CoherenceAnalyzer.onStand(null, stand, sources.creneaux()).stream()
                    .filter(avertissement -> avertissement.type() == TypeAvertissement.STAND_EXCEPTION_HORS_EVENEMENT)
                    .forEach(avertissement -> issues.add(
                            warning(CoherenceFamily.STANDS, avertissement, CoherenceSubject.STAND, stand.getId())));
        }
        for (OuvertureStandsAnalyzer.Anomaly anomalie : sources.ouvertures().anomalies()) {
            issues.add(new CoherenceIssue(
                    CoherenceFamily.STANDS,
                    anomalie.type().isInformational() ? CoherenceSeverity.INFORMATION : CoherenceSeverity.A_VERIFIER,
                    anomalie.type().name(),
                    anomalie.message(),
                    CoherenceSubject.STAND,
                    anomalie.standId(),
                    anomalie.date()));
        }
    }

    private static void timeslotIssues(Sources sources, List<CoherenceIssue> issues) {
        for (Creneau creneau : sources.creneaux()) {
            CoherenceAnalyzer.onCreneau(creneau, sources.ownHoursStands())
                    .forEach(avertissement -> issues.add(warning(
                            CoherenceFamily.CRENEAUX,
                            avertissement,
                            CoherenceSubject.CRENEAU,
                            creneau.getId() == null ? null : String.valueOf(creneau.getId()))));
        }
        for (CreneauGridService.GridAnomaly anomalie : sources.grille()) {
            issues.add(new CoherenceIssue(
                    CoherenceFamily.CRENEAUX,
                    anomalie.severite() == CreneauGridService.SeveriteGrille.ERREUR
                            ? CoherenceSeverity.BLOQUANT
                            : CoherenceSeverity.A_VERIFIER,
                    anomalie.type().name(),
                    anomalie.message(),
                    CoherenceSubject.EDITION,
                    null,
                    anomalie.date()));
        }
    }

    /** Sorts the issues and counts them, by family and overall. */
    private static CoherenceReport summarize(List<CoherenceIssue> issues) {
        issues.sort(
                Comparator.comparing((CoherenceIssue issue) -> issue.famille().ordinal())
                        .thenComparing(issue -> issue.gravite().ordinal())
                        .thenComparing(CoherenceIssue::code)
                        .thenComparing(issue -> issue.objetId() == null ? "" : issue.objetId())
                        .thenComparing(issue -> issue.date() == null ? LocalDate.MIN : issue.date()));

        List<FamilyCount> familles = new ArrayList<>();
        for (CoherenceFamily famille : CoherenceFamily.values()) {
            List<CoherenceIssue> ofFamily =
                    issues.stream().filter(issue -> issue.famille() == famille).toList();
            familles.add(new FamilyCount(
                    famille,
                    count(ofFamily, CoherenceSeverity.BLOQUANT),
                    count(ofFamily, CoherenceSeverity.A_VERIFIER),
                    count(ofFamily, CoherenceSeverity.INFORMATION)));
        }
        return new CoherenceReport(
                count(issues, CoherenceSeverity.BLOQUANT),
                count(issues, CoherenceSeverity.A_VERIFIER),
                count(issues, CoherenceSeverity.INFORMATION),
                List.copyOf(familles),
                List.copyOf(issues));
    }

    /**
     * The same four readings Diagnostic → Problèmes makes of the exceptions,
     * with the same sentences: critical there, blocking here. A forced
     * assignment whose whole scope lies in the frozen past is history and
     * says nothing — the detectors take the horizon.
     */
    private static void adHoc(Sources sources, List<CoherenceIssue> issues) {
        List<ContrainteAdHoc> contraintes = sources.contraintesAdHoc();
        if (contraintes.isEmpty()) {
            return;
        }
        for (ContrainteAdHocContradictions.Contradiction contradiction :
                ContrainteAdHocContradictions.detectAll(contraintes, sources.creneaux())) {
            issues.add(new CoherenceIssue(
                    CoherenceFamily.AJUSTEMENTS,
                    CoherenceSeverity.BLOQUANT,
                    "CONTRAINTES_AD_HOC_CONTRADICTOIRES",
                    contradiction.message(),
                    CoherenceSubject.CONTRAINTE_AD_HOC,
                    contradiction.contrainteIds().isEmpty()
                            ? null
                            : contradiction.contrainteIds().getFirst(),
                    null));
        }
        for (ForcedAssignmentOnDayOff.Conflit conflit : ForcedAssignmentOnDayOff.detectAll(
                contraintes, sources.animateurs(), sources.stands(), sources.creneaux(), sources.horizon())) {
            issues.add(forced(
                    TypeAvertissement.AFFECTATION_FORCEE_JOUR_INDISPONIBLE,
                    conflit.contrainte(),
                    conflit.message(),
                    conflit.dates()));
        }
        for (ForcedAssignmentOnExcludedSeats.Conflit conflit : ForcedAssignmentOnExcludedSeats.detectAll(
                contraintes, sources.animateurs(), sources.stands(), sources.creneaux(), sources.horizon())) {
            issues.add(forced(
                    TypeAvertissement.AFFECTATION_FORCEE_MOTIF_LEGAL,
                    conflit.contrainte(),
                    conflit.message(),
                    conflit.dates()));
        }
        // A car whose members declared different days off: the write warned,
        // the checklist keeps saying so while it stands.
        for (ContrainteAdHoc contrainte : contraintes) {
            CoherenceAnalyzer.divergentGroupedArrival(contrainte, sources.animateurs(), sources.creneaux())
                    .ifPresent(avertissement -> issues.add(warning(
                            CoherenceFamily.AJUSTEMENTS,
                            avertissement,
                            CoherenceSubject.CONTRAINTE_AD_HOC,
                            contrainte.getId())));
        }
        boolean forcees = contraintes.stream()
                .anyMatch(contrainte ->
                        contrainte != null && contrainte.getType() == TypeContrainteAdHoc.AFFECTATION_FORCEE);
        if (!sources.verrouillages().isEmpty() && forcees) {
            for (ForcedAssignmentOnLockedSchedule.Conflit conflit : ForcedAssignmentOnLockedSchedule.detectAll(
                    contraintes,
                    sources.verrouillages(),
                    sources.stands(),
                    sources.creneaux(),
                    sources.placesTenues().get(),
                    sources.horizon())) {
                issues.add(forced(
                        TypeAvertissement.AFFECTATION_FORCEE_SIEGE_VERROUILLE,
                        conflit.contrainte(),
                        conflit.message(),
                        conflit.dates()));
            }
        }
    }

    /**
     * The bound Diagnostic → Besoin retains, against the roster: the edition
     * short of people overall, then each game category short of specialists.
     * Nothing while a referential is missing — the Référentiels line carries
     * that, and a bound computed on nothing says nothing.
     */
    private static void capacity(Sources sources, List<CoherenceIssue> issues) {
        StaffingSummary staffing = sources.staffing();
        if (staffing == null || !staffing.referentielsManquants().isEmpty()) {
            return;
        }
        int animateurs = sources.animateurs().size();
        int manque = staffing.minimumTotal() - animateurs;
        if (manque > 0) {
            issues.add(new CoherenceIssue(
                    CoherenceFamily.CAPACITE,
                    CoherenceSeverity.A_VERIFIER,
                    "BESOIN_NON_COUVERT",
                    "Il manque " + manque + " animateur(s) pour atteindre le minimum retenu (" + staffing.minimumTotal()
                            + ") : " + animateurs + " saisi(s).",
                    CoherenceSubject.EDITION,
                    null,
                    null));
        }
        if (staffing.parCompetence() == null) {
            return;
        }
        for (TypologieStaffing typologie : staffing.parCompetence().parTypologie()) {
            if (typologie.manque() <= 0) {
                continue;
            }
            String libelle = typologie.label() != null ? typologie.label() : typologie.typologie();
            issues.add(new CoherenceIssue(
                    CoherenceFamily.CAPACITE,
                    CoherenceSeverity.A_VERIFIER,
                    "TYPOLOGIE_EN_MANQUE",
                    "Typologie « " + libelle + " » : il manque " + typologie.manque() + " personne(s) la "
                            + "déclarant pour tenir ses " + typologie.sieges() + " siège(s) (minimum "
                            + typologie.minimumTotal() + ", " + typologie.specialistes() + " déclarée(s)).",
                    CoherenceSubject.TYPOLOGIE,
                    typologie.typologie(),
                    null));
        }
    }

    private static CoherenceIssue forced(
            TypeAvertissement type, ContrainteAdHoc contrainte, String message, List<LocalDate> dates) {
        return new CoherenceIssue(
                CoherenceFamily.AJUSTEMENTS,
                CoherenceSeverity.BLOQUANT,
                type.name(),
                message,
                CoherenceSubject.CONTRAINTE_AD_HOC,
                contrainte.getId(),
                dates.size() == 1 ? dates.getFirst() : null);
    }

    /**
     * A write-time warning replayed: to check, except the minority of an
     * animateur, which is information — the regime it triggers is modelled,
     * nothing is wrong with it.
     */
    private static CoherenceIssue warning(
            CoherenceFamily famille, Avertissement avertissement, CoherenceSubject objet, String objetId) {
        return new CoherenceIssue(
                famille,
                avertissement.type() == TypeAvertissement.MINEUR_PENDANT_EVENEMENT
                        ? CoherenceSeverity.INFORMATION
                        : CoherenceSeverity.A_VERIFIER,
                avertissement.type().name(),
                avertissement.message(),
                objet,
                objetId,
                null);
    }

    private static int count(List<CoherenceIssue> issues, CoherenceSeverity gravite) {
        return (int) issues.stream().filter(issue -> issue.gravite() == gravite).count();
    }
}
