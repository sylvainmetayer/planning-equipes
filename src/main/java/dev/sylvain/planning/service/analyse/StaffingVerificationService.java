package dev.sylvain.planning.service.analyse;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.NiveauCompetence;
import dev.sylvain.planning.domain.PastHorizon;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.service.BusinessError;
import dev.sylvain.planning.service.EditionContext;
import dev.sylvain.planning.service.referentiel.ReferenceDataService;
import dev.sylvain.planning.service.referentiel.TypologieItem;
import dev.sylvain.planning.service.solve.PlanningService;
import dev.sylvain.planning.service.solve.ProblemBuilder.Seats;
import dev.sylvain.planning.service.solve.SolverJobService;
import dev.sylvain.planning.solver.ConstraintCatalog;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.Month;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.jboss.logging.Logger;

/**
 * Whether the staffing floor really holds: a solve of the edition's seats, under
 * the edition's rules, by a <b>made-up team</b> of the size asked — adults,
 * available every day, competent on every game category.
 *
 * <p>The floor of {@link StaffingAnalyzer} is a proof that no smaller team can
 * do; it says nothing of whether that team can. A team that clears every bound
 * may still fail on what the bounds leave out — the daily rest after a late
 * evening, the breaks a long stretch owes, a meal window the grid leaves no room
 * for. Only the solver checks them all, and only a solve with exactly that many
 * people turns « at least N » into « N, and N suffices » — the floor is then
 * the exact answer, which {@code StaffingVerificationSolveTest} shows on a
 * week where N people staff every seat and N − 1 cannot.</p>
 *
 * <p>The team is made up so that nothing but the headcount is under test: with
 * the edition's real animateurs, a failure could come from a missing competence
 * or a declared day off, which the competence rows and the unavailability
 * budget of the same screen already measure. The edition is never written:
 * neither its plan, nor its animateurs, nor the solver queue — the problem is
 * built in memory and dropped once read. The ad hoc exceptions and the
 * published plan are left out too, since they name people this team does not
 * contain ({@code SolveRunner#prepareHypothetical}).</p>
 *
 * <p>One check at a time across the application, and none while a solve holds
 * the solver: a solve takes every core it is given, and two at once would only
 * slow both. A solve launched while a check runs is not held back — the check
 * is the one that can wait. A check that finds no complete plan within its
 * ceiling proves nothing: the solver stops at the first plan breaking no hard
 * rule, so a failure is a time budget spent, never a proof that the team is
 * short. The last result of each edition is kept in memory — losing it on a
 * restart costs one click, persisting it would cost a table for a figure that
 * goes stale with the next edit of a stand.</p>
 */
@ApplicationScoped
public class StaffingVerificationService {

    private static final Logger LOG = Logger.getLogger(StaffingVerificationService.class);

    /** Above this, the question is no longer « does the floor hold » but a load test. */
    static final int EFFECTIF_MAX = 5000;

    /** Where a check stands. */
    public enum VerificationState {
        EN_COURS,
        TERMINEE,
        ECHEC
    }

    /**
     * One check of the floor.
     *
     * @param effectif         the size of the made-up team
     * @param sieges           the seats it had to fill
     * @param realisable       whether the solve reached a plan breaking no hard
     *                         rule — every seat filled included — {@code null}
     *                         until it ends
     * @param siegesNonPourvus seats left empty by the best plan found
     * @param scoreDur         the hard score of that plan, {@code 0} when feasible
     * @param reglesEnDefaut   the hard rules it still breaks, by catalogue name
     * @param dureeSecondes    how long the solve took
     * @param plafondSecondes  the time it was given at most
     * @param erreur           why the check could not run, on {@code ECHEC}
     */
    @Schema(requiredProperties = {"effectif", "etat", "lanceeLe", "plafondSecondes", "reglesEnDefaut", "sieges"})
    public record StaffingVerification(
            VerificationState etat,
            int effectif,
            int sieges,
            Instant lanceeLe,
            Instant termineeLe,
            Long dureeSecondes,
            long plafondSecondes,
            Boolean realisable,
            Integer siegesNonPourvus,
            Long scoreDur,
            List<String> reglesEnDefaut,
            String erreur) {

        public StaffingVerification {
            reglesEnDefaut = reglesEnDefaut == null ? List.of() : List.copyOf(reglesEnDefaut);
        }

        static StaffingVerification started(int effectif, int sieges, long plafondSecondes) {
            return new StaffingVerification(
                    VerificationState.EN_COURS,
                    effectif,
                    sieges,
                    Instant.now(),
                    null,
                    null,
                    plafondSecondes,
                    null,
                    null,
                    null,
                    List.of(),
                    null);
        }

        StaffingVerification finished(boolean feasible, int empty, long hard, List<String> rules) {
            Instant end = Instant.now();
            return new StaffingVerification(
                    VerificationState.TERMINEE,
                    effectif,
                    sieges,
                    lanceeLe,
                    end,
                    Duration.between(lanceeLe, end).toSeconds(),
                    plafondSecondes,
                    feasible,
                    empty,
                    hard,
                    rules,
                    null);
        }

        StaffingVerification failed(String message) {
            Instant end = Instant.now();
            return new StaffingVerification(
                    VerificationState.ECHEC,
                    effectif,
                    sieges,
                    lanceeLe,
                    end,
                    Duration.between(lanceeLe, end).toSeconds(),
                    plafondSecondes,
                    null,
                    null,
                    null,
                    List.of(),
                    message);
        }
    }

    private final PlanningService planningService;
    private final ReferenceDataService referenceDataService;
    private final StaffingService staffingService;
    private final SolverJobService solverJobService;
    private final EditionContext editionContext;
    private final long plafondSecondes;

    private final Map<String, StaffingVerification> parEdition = new ConcurrentHashMap<>();
    private final AtomicBoolean enCours = new AtomicBoolean();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "staffing-verification");
        thread.setDaemon(true);
        return thread;
    });

    @Inject
    public StaffingVerificationService(
            PlanningService planningService,
            ReferenceDataService referenceDataService,
            StaffingService staffingService,
            SolverJobService solverJobService,
            EditionContext editionContext,
            @ConfigProperty(name = "planning.staffing.verification.seconds-limit", defaultValue = "600")
                    long plafondSecondes) {
        this.planningService = planningService;
        this.referenceDataService = referenceDataService;
        this.staffingService = staffingService;
        this.solverJobService = solverJobService;
        this.editionContext = editionContext;
        this.plafondSecondes = plafondSecondes;
    }

    /**
     * Starts a check of the current edition and returns at once, the solve
     * running in the background.
     *
     * @param effectif the size of the team, {@code null} for the floor the
     *                 staffing screen shows
     * @throws BusinessError.Conflict when a check is already running, in any
     *                                edition, or a solve holds the solver
     * @throws BusinessError.Invalid  when the edition has no seat to fill, or
     *                                the size is out of range
     */
    public StaffingVerification start(Integer effectif) {
        String edition = editionContext.editionIdCourant();
        Seats seats = planningService.buildSeatsFromReferenceData();
        if (seats.postes().isEmpty()) {
            throw new BusinessError.Invalid(
                    "Aucun siège à pourvoir : saisissez des stands et des créneaux avant de vérifier le besoin.");
        }
        // The seats just built, not a second build: the floor is read on them.
        int taille =
                effectif != null ? effectif : staffingService.analyze(seats).minimumTotal();
        if (taille < 1 || taille > EFFECTIF_MAX) {
            throw new BusinessError.Invalid(
                    "L'effectif à vérifier doit être compris entre 1 et " + EFFECTIF_MAX + " : " + taille + ".");
        }
        // The solver is shared by every edition, one job at a time: a check
        // would take the cores a running solve was budgeted on.
        if (solverJobService.findActive().isPresent()) {
            throw new BusinessError.Conflict(
                    "Une résolution est en cours : lancez la vérification du besoin une fois qu'elle est terminée.");
        }
        if (!enCours.compareAndSet(false, true)) {
            throw new BusinessError.Conflict(
                    "Une vérification du besoin est déjà en cours : attendez qu'elle se termine avant d'en lancer une autre.");
        }
        StaffingVerification previous = parEdition.get(edition);
        StaffingVerification started = null;
        try {
            PlanningEvenement problem = problem(seats, team(taille, referenceDataService.listTypologies()));
            // Every read of the edition happens here, on the request: the
            // solve below runs on a thread that designates no edition.
            planningService.prepareHypothetical(problem);
            started = StaffingVerification.started(taille, seats.postes().size(), plafondSecondes);
            parEdition.put(edition, started);
            StaffingVerification launched = started;
            executor.submit(() -> run(edition, problem, launched));
            return started;
        } catch (RuntimeException e) {
            // Nothing runs: neither the lock nor an « en cours » nobody will
            // ever finish may outlive the refusal.
            if (started != null) {
                if (previous == null) {
                    parEdition.remove(edition, started);
                } else {
                    parEdition.replace(edition, started, previous);
                }
            }
            enCours.set(false);
            throw e;
        }
    }

    /** The last check of the current edition, running or finished. */
    public Optional<StaffingVerification> current() {
        return Optional.ofNullable(parEdition.get(editionContext.editionIdCourant()));
    }

    private void run(String edition, PlanningEvenement problem, StaffingVerification started) {
        try {
            PlanningEvenement solved = planningService.solvePreparedUntilFeasible(problem, plafondSecondes);
            int empty = (int) solved.getPostes().stream()
                    .filter(poste -> poste.getAnimateur() == null)
                    .count();
            long hard = solved.getScore() == null ? 0 : solved.getScore().hardScore();
            List<String> rules = hard < 0 ? brokenHardRules(solved) : List.of();
            parEdition.put(edition, started.finished(hard == 0 && empty == 0, empty, hard, rules));
        } catch (RuntimeException e) {
            LOG.warnf(e, "Staffing check of edition %s failed", edition);
            String cause =
                    e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            parEdition.put(edition, started.failed("La vérification n'a pas pu aboutir : " + cause));
        } finally {
            enCours.set(false);
        }
    }

    /**
     * The hard rules the best plan still breaks, the most broken first. The
     * diagnostic reads the plan it is handed and nothing else, so it needs no
     * edition on this thread.
     */
    private List<String> brokenHardRules(PlanningEvenement solved) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        planningService.diagnose(solved).contraintes().stream()
                .filter(diagnostic -> diagnostic.matchCount() > 0)
                .filter(diagnostic -> ConstraintCatalog.NOMS_DURS.contains(diagnostic.name()))
                .forEach(diagnostic -> counts.merge(diagnostic.name(), diagnostic.matchCount(), Integer::sum));
        return counts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .map(Map.Entry::getKey)
                .toList();
    }

    /**
     * {@code effectif} adults, available every day, holding every game
     * category of the referential — the ninja one included, so a stand
     * proposing none is open to them too. Born long enough ago to be an adult
     * on any date an edition may hold.
     */
    public static List<Animateur> team(int effectif, List<TypologieItem> typologies) {
        Map<String, NiveauCompetence> competences = new LinkedHashMap<>();
        String ninja = null;
        for (TypologieItem typologie : typologies) {
            competences.put(typologie.id(), NiveauCompetence.AUTONOME);
            if (typologie.ninja() && ninja == null) {
                ninja = typologie.id();
            }
        }
        List<Animateur> team = new ArrayList<>(effectif);
        for (int index = 1; index <= effectif; index++) {
            String id = "verification-" + index;
            Animateur animateur = new Animateur(id, "Fictif", id, LocalDate.of(1970, Month.JANUARY, 1), false);
            animateur.setCompetences(new LinkedHashMap<>(competences));
            animateur.applyNinjaTypologie(ninja);
            team.add(animateur);
        }
        return team;
    }

    /**
     * The edition's seats and the made-up team, with a horizon on the eve of
     * the event: the question is about the whole grid, so no seat is frozen
     * as past, even on an edition already under way.
     */
    public static PlanningEvenement problem(Seats seats, List<Animateur> team) {
        List<PosteAffectation> postes = seats.postes();
        LocalDate dateDebut = seats.creneaux().stream()
                .map(Creneau::getDate)
                .filter(Objects::nonNull)
                .min(LocalDate::compareTo)
                .orElse(null);
        PlanningEvenement problem = new PlanningEvenement(dateDebut, team, postes, new ArrayList<>());
        problem.setVerrouillages(List.of());
        // The eve of the event, at midnight: nothing has started, whatever the clock says.
        LocalDate eve = dateDebut == null ? LocalDate.of(1970, Month.JANUARY, 1) : dateDebut.minusDays(1);
        problem.setPastHorizon(new PastHorizon(eve, LocalTime.MIDNIGHT));
        return problem;
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}
