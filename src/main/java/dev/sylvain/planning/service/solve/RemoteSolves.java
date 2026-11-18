package dev.sylvain.planning.service.solve;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.sylvain.planning.config.ConfigSolverWorker;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.service.BusinessError;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Solves run on a worker instead of here, and the machine that worker lives
 * on (ADR 0086).
 *
 * <p>Two shapes, chosen by the instance's configuration. A <b>permanent</b>
 * worker is an address and a secret; there is nothing to start. An
 * <b>on-demand</b> one is a machine a launcher creates behind an HTTP hook the
 * operator provides: this class asks for it before a solve, keeps it for the
 * solves that follow, and asks for its end once it has been idle long enough.
 * The application holds no credential able to create a machine — the launcher
 * does, outside it — and every connection starts here: the worker and the
 * launcher never call back.</p>
 *
 * <p>A remote solve that fails <b>fails the job</b>, with its reason; nothing
 * falls back on solving here, which would silently double the wait the
 * operator was told to expect. The persisted plan is never touched by a
 * failure.</p>
 */
@ApplicationScoped
public class RemoteSolves {

    private static final Logger LOG = Logger.getLogger(RemoteSolves.class);

    private static final String UNAVAILABLE = "Machine de calcul indisponible : ";

    private static final int STOP_ATTEMPTS = 3;

    /** What the Solveur screen shows of the machine. */
    public enum MachineState {
        /** No machine to speak of: the worker is permanent. */
        PERMANENT,
        ABSENT,
        PREPARING,
        READY,
        SOLVING
    }

    /**
     * @param configured      whether a solve can run elsewhere at all
     * @param onDemand        whether a machine is started and stopped for it
     * @param remoteByDefault where a solve runs when its launch does not say
     * @param state           {@code null} when nothing is configured
     * @param stopsAt         when an idle machine will be stopped
     * @param billedHoursThisMonth machine hours of the month, each machine
     *                        rounded up to the hour like an hourly invoice
     * @param lastStopError   why the last attempt to stop the machine failed —
     *                        the one failure that costs money in silence
     */
    public record ComputeMachine(
            boolean configured,
            boolean onDemand,
            boolean remoteByDefault,
            MachineState state,
            Instant stopsAt,
            Long billedHoursThisMonth,
            String lastStopError) {}

    private final ConfigSolverWorker config;
    private final RemoteSolveRepository repository;
    private final ObjectMapper json;
    private final SolveExchange exchange;
    private final String version;
    private final HttpClient http =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final SecureRandom random = new SecureRandom();

    /** How often the hook is asked whether the machine is ready, and the worker where its solve stands. */
    private Duration pollEvery = Duration.ofSeconds(1);

    /** The job solving on the worker right now. */
    private final AtomicReference<String> solvingJob = new AtomicReference<>();

    private volatile String lastStopError;
    private ScheduledExecutorService idleWatch;

    @Inject
    public RemoteSolves(
            ConfigSolverWorker config,
            RemoteSolveRepository repository,
            ObjectMapper json,
            @ConfigProperty(name = "quarkus.application.version") String version) {
        this.config = config;
        this.repository = repository;
        this.json = json;
        this.exchange = new SolveExchange(json);
        this.version = version;
    }

    /** For the tests: a second between two polls is the production pace, not a test's. */
    void pollEvery(Duration pace) {
        this.pollEvery = pace;
    }

    // --- Configuration --------------------------------------------------------

    public boolean configured() {
        return permanent() || onDemand();
    }

    private boolean permanent() {
        return config.url().isPresent();
    }

    private boolean onDemand() {
        return config.hookUrl().isPresent();
    }

    public boolean remoteByDefault() {
        return configured() && "remote".equalsIgnoreCase(config.defaultPlace());
    }

    /**
     * Where a solve launched with {@code askedRemote} runs: its own choice,
     * the instance's when it makes none.
     */
    public boolean resolvePlace(Boolean askedRemote) {
        if (askedRemote == null) {
            return remoteByDefault();
        }
        if (askedRemote && !configured()) {
            throw new BusinessError.Invalid("Aucune machine de calcul n'est configurée sur cette instance.");
        }
        return askedRemote;
    }

    /**
     * Refuses to boot on a configuration that could only fail later, in the
     * middle of somebody's solve — and stops a machine left by another version.
     */
    void onStart(@Observes StartupEvent start) {
        check(config);
        if (!onDemand()) {
            return;
        }
        // Off the boot thread: a launcher that is down must not hold the
        // application's start for the minutes its calls take to give up.
        Thread.ofVirtual().name("compute-machine-startup").start(() -> {
            try {
                repository
                        .liveMachine()
                        .filter(machine -> !machine.version().equals(version))
                        // One still carrying a solve is left to finish it: the
                        // job found again at this start follows it there, and
                        // the end of that solve stops it.
                        .filter(machine -> !repository.anyPending())
                        .ifPresent(machine -> {
                            LOG.infof(
                                    "The compute machine runs version %s, this application %s: stopping it",
                                    machine.version(), version);
                            stopMachine();
                        });
            } catch (RuntimeException e) {
                LOG.error("The compute machine could not be checked at startup", e);
            }
        });
        idleWatch = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread watching = new Thread(runnable, "compute-machine-idle");
            watching.setDaemon(true);
            return watching;
        });
        idleWatch.scheduleWithFixedDelay(this::stopWhenIdle, 1, 1, TimeUnit.MINUTES);
    }

    static void check(ConfigSolverWorker config) {
        if (config.url().isPresent() && config.hookUrl().isPresent()) {
            throw new IllegalStateException(
                    "SOLVER_WORKER_URL and SOLVER_WORKER_HOOK_URL exclude each other: a worker is permanent or started on demand");
        }
        if (config.url().isPresent()) {
            if (config.secret().isEmpty()) {
                throw new IllegalStateException("SOLVER_WORKER_URL needs SOLVER_WORKER_SECRET");
            }
            URI url = URI.create(config.url().get());
            boolean loopback = "localhost".equals(url.getHost()) || "127.0.0.1".equals(url.getHost());
            if (!"https".equals(url.getScheme()) && !loopback) {
                throw new IllegalStateException(
                        "SOLVER_WORKER_URL must be https: plain HTTP is accepted for a worker on the loopback address only");
            }
        }
        if (config.hookUrl().isPresent() && config.hookSecret().isEmpty()) {
            throw new IllegalStateException("SOLVER_WORKER_HOOK_URL needs SOLVER_WORKER_HOOK_SECRET");
        }
        if (config.hookUrl().isPresent()) {
            // The hook secret travels as a bearer token on every call. A
            // launcher listens to the application alone — the host of its
            // container, a private network — and that is where plain HTTP is
            // accepted; across anything public it must be HTTPS.
            URI hook = URI.create(config.hookUrl().get());
            if (!"https".equals(hook.getScheme()) && !privateOrLoopback(hook.getHost())) {
                throw new IllegalStateException(
                        "SOLVER_WORKER_HOOK_URL must be https: plain HTTP is accepted for a launcher on the loopback address or a private network only");
            }
        }
    }

    /** Whether {@code host} resolves to this machine or to a private network; an unknown name answers no. */
    static boolean privateOrLoopback(String host) {
        if (host == null) {
            return false;
        }
        try {
            InetAddress address = InetAddress.getByName(host);
            return address.isLoopbackAddress() || address.isSiteLocalAddress() || address.isLinkLocalAddress();
        } catch (UnknownHostException _) {
            return false;
        }
    }

    @PreDestroy
    void shutdown() {
        if (idleWatch != null) {
            idleWatch.shutdownNow();
        }
    }

    // --- The solve --------------------------------------------------------------

    /**
     * Solves {@code problem} on the worker: prepared here, as any solve is,
     * sent, followed, and its plan laid back on {@code problem} — which is
     * returned, carrying it.
     */
    SolveRunner.Solved solve(
            PlanningService planning, PlanningEvenement problem, SolveBudget budget, SolveListener listener) {
        SolveListener.RemoteTicket ticket = listener.remote();
        planning.prepareToSolve(problem, listener);
        Optional<RemoteSolver.Resume> resume =
                repository.findPending(ticket.jobId()).map(this::resumeOf);
        ticket.stage()
                .accept(resume.isPresent() ? SolveListener.RemoteStage.SOLVING : SolveListener.RemoteStage.PREPARING);
        RemoteSolver solver = new RemoteSolver(
                // A solve found again is followed where it runs: starting a
                // fresh machine for it would only pay for an empty one.
                resume.isPresent() ? cancelled -> existingWorker() : this::acquire,
                exchange,
                planning.solutionManager(),
                planning.searchSettings(budget),
                version,
                resume.orElse(null),
                (solveId, realIds) -> {
                    repository.savePending(ticket.jobId(), solveId, write(realIds), Instant.now());
                    ticket.stage().accept(SolveListener.RemoteStage.SOLVING);
                },
                ticket.shuttingDown(),
                pollEvery);
        listener.accept(solver);
        solvingJob.set(ticket.jobId());
        boolean detached = false;
        try {
            return new SolveRunner.Solved(solver.solve(problem), solver.feasibilityFirst());
        } catch (RemoteSolver.Detached e) {
            detached = true;
            throw e;
        } catch (RemoteSolver.Failed e) {
            // Its log was read while it still answered; a machine that failed
            // a solve is not one to hand the next.
            if (onDemand()) {
                stopMachine();
            }
            throw e;
        } finally {
            solvingJob.set(null);
            if (!detached) {
                repository.deletePending(ticket.jobId());
                repository.touch(Instant.now());
                stopMachineOfAnotherVersion();
            }
        }
    }

    /** A machine started by a previous version cannot take this one's problems: stopped once it has nothing left to finish. */
    private void stopMachineOfAnotherVersion() {
        if (onDemand()
                && repository
                        .liveMachine()
                        .filter(machine -> !machine.version().equals(version))
                        .isPresent()) {
            stopMachine();
        }
    }

    /** Whether {@code jobId} left a solve on a worker that a restart may still find. */
    boolean hasPending(String jobId) {
        return repository.findPending(jobId).isPresent();
    }

    private RemoteSolver.Resume resumeOf(RemoteSolveRepository.PendingSolve pending) {
        try {
            return new RemoteSolver.Resume(
                    pending.solveId(), json.readValue(pending.realIds(), new TypeReference<>() {}));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("The remote solve's identifiers cannot be read", e);
        }
    }

    private String write(Map<String, String> realIds) {
        try {
            return json.writeValueAsString(realIds);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    // --- The machine ------------------------------------------------------------

    public ComputeMachine view() {
        if (!configured()) {
            return new ComputeMachine(false, false, false, null, null, null, null);
        }
        if (permanent()) {
            return new ComputeMachine(true, false, remoteByDefault(), MachineState.PERMANENT, null, null, null);
        }
        Optional<RemoteSolveRepository.Machine> machine = repository.liveMachine();
        MachineState state = machine.isEmpty()
                ? MachineState.ABSENT
                : machine.get().readyAt() == null
                        ? MachineState.PREPARING
                        : solvingJob.get() != null ? MachineState.SOLVING : MachineState.READY;
        Instant stopsAt =
                state == MachineState.READY ? machine.get().lastActivity().plus(config.idleTimeout()) : null;
        return new ComputeMachine(
                true,
                true,
                remoteByDefault(),
                state,
                stopsAt,
                repository.billedHoursOfMonth(Instant.now()),
                lastStopError);
    }

    /**
     * Starts the machine ahead of the first solve of a session, and returns
     * at once: the screen follows {@link #view()}.
     */
    public void prepare() {
        requireOnDemand();
        Thread.ofVirtual().name("compute-machine-prepare").start(() -> {
            try {
                acquire(() -> false);
            } catch (RuntimeException e) {
                LOG.warn("The compute machine could not be prepared", e);
            }
        });
    }

    /** Stops the machine now rather than at the end of its idle time. Refused while it solves. */
    public void stopNow() {
        requireOnDemand();
        if (solvingJob.get() != null) {
            throw new BusinessError.Conflict(
                    "La machine de calcul résout en ce moment : arrêtez d'abord la résolution.");
        }
        stopMachine();
    }

    private void requireOnDemand() {
        if (!onDemand()) {
            throw new BusinessError.Invalid("Cette instance ne démarre pas de machine de calcul à la demande.");
        }
    }

    private void stopWhenIdle() {
        try {
            if (solvingJob.get() != null) {
                return;
            }
            repository
                    .liveMachine()
                    // A machine still being prepared counts too: one whose solve
                    // was cancelled meanwhile has nobody left waiting for it.
                    .filter(machine ->
                            machine.lastActivity().plus(config.idleTimeout()).isBefore(Instant.now()))
                    .ifPresent(machine -> {
                        LOG.infof("The compute machine has been idle for %s: stopping it", config.idleTimeout());
                        stopMachine();
                    });
        } catch (RuntimeException e) {
            LOG.error("The idle compute machine could not be checked", e);
        }
    }

    /** The permanent worker, or the machine already up — never one started for the occasion. */
    private WorkerClient existingWorker() {
        if (permanent()) {
            return permanentWorker();
        }
        return repository
                .liveMachine()
                .filter(machine -> machine.readyAt() != null)
                .map(this::workerOf)
                .orElseThrow(
                        () -> new RemoteSolver.Failed("La machine de calcul qui portait ce calcul n'existe plus."));
    }

    private WorkerClient permanentWorker() {
        return new WorkerClient(
                new WorkerClient.Endpoint(
                        config.url().orElseThrow(),
                        config.secret().orElseThrow(),
                        config.tlsFingerprint().orElse(null)),
                json);
    }

    private WorkerClient workerOf(RemoteSolveRepository.Machine machine) {
        return new WorkerClient(
                new WorkerClient.Endpoint(machine.url(), secretOf(machine.nonce()), machine.fingerprint()), json);
    }

    /**
     * The worker to solve on, its machine started first when there is none.
     * {@code null} when {@code cancelled} ended the wait.
     */
    private WorkerClient acquire(BooleanSupplier cancelled) {
        if (permanent()) {
            return permanentWorker();
        }
        Instant deadline = Instant.now().plus(config.startTimeout());
        RemoteSolveRepository.Machine machine = startIfAbsent();
        while (machine.readyAt() == null) {
            if (cancelled.getAsBoolean()) {
                return null;
            }
            String state;
            JsonNode status = null;
            try {
                status = hook("GET", "status?instance=" + encode(config.instance()), null);
                state = status.path("state").asText();
            } catch (RemoteSolver.Failed e) {
                // One bad answer of the launcher, or of its provider, is not
                // the machine failing: asked again until the deadline.
                LOG.debugf(e, "The launcher did not say where the machine stands; asking again");
                state = "creating";
            }
            if ("ready".equals(state)) {
                repository.markReady(
                        machine.id(),
                        status.path("url").asText(),
                        status.path("certFingerprint").asText(null),
                        Instant.now());
                machine = repository.liveMachine().orElseThrow();
            } else if ("absent".equals(state)) {
                repository.closeMachine(machine.id(), Instant.now());
                throw new RemoteSolver.Failed(UNAVAILABLE + "le lanceur ne la connaît plus.");
            } else if (Instant.now().isAfter(deadline)) {
                stopMachine();
                throw new RemoteSolver.Failed(UNAVAILABLE + "elle n'était pas prête après "
                        + config.startTimeout().toMinutes() + " minutes.");
            } else {
                pause();
            }
        }
        return workerOf(machine);
    }

    /** The live machine, asked of the launcher first when there is none. One caller at a time: a solve and « Préparer » must not start two. */
    private synchronized RemoteSolveRepository.Machine startIfAbsent() {
        Optional<RemoteSolveRepository.Machine> live = repository.liveMachine();
        if (live.isPresent()) {
            return live.get();
        }
        byte[] bytes = new byte[16];
        random.nextBytes(bytes);
        String nonce = HexFormat.of().formatHex(bytes);
        repository.openMachine(version, nonce, Instant.now());
        RemoteSolveRepository.Machine machine = repository.liveMachine().orElseThrow();
        try {
            hook(
                    "POST",
                    "start",
                    Map.of("instance", config.instance(), "version", version, "secretWorker", secretOf(nonce)));
        } catch (RemoteSolver.Failed e) {
            // Nothing was started, as far as anyone can tell: no machine to count.
            repository.closeMachine(machine.id(), Instant.now());
            throw e;
        }
        LOG.infof("Compute machine asked of the launcher for version %s", version);
        return machine;
    }

    /**
     * Asks the launcher to end the machine, a few times, and closes its line
     * only once it said yes: a machine nobody stopped keeps costing, and the
     * line still open is what makes the idle watch ask again next minute.
     */
    private synchronized void stopMachine() {
        Optional<RemoteSolveRepository.Machine> live = repository.liveMachine();
        if (live.isEmpty()) {
            return;
        }
        RemoteSolver.Failed failure = null;
        for (int attempt = 0; attempt < STOP_ATTEMPTS; attempt++) {
            try {
                hook("POST", "stop", Map.of("instance", config.instance()));
                repository.closeMachine(live.get().id(), Instant.now());
                lastStopError = null;
                LOG.info("Compute machine stopped");
                return;
            } catch (RemoteSolver.Failed e) {
                failure = e;
            }
        }
        lastStopError = failure.getMessage();
        LOG.errorf(failure, "The compute machine could not be stopped and keeps running; it will be asked again");
    }

    /**
     * A machine's own secret: the hook's, keyed by the nonce of its line — so
     * a secret read on one machine opens no other, and none is stored.
     */
    private String secretOf(String nonce) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(
                    config.hookSecret().orElseThrow().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(nonce.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private JsonNode hook(String method, String path, Object body) {
        String base = config.hookUrl().orElseThrow();
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder(
                            URI.create((base.endsWith("/") ? base : base + "/") + path))
                    .timeout(Duration.ofSeconds(60))
                    .header("Authorization", "Bearer " + config.hookSecret().orElseThrow())
                    .header("Content-Type", "application/json");
            request.method(
                    method,
                    body == null
                            ? HttpRequest.BodyPublishers.noBody()
                            : HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(body)));
            HttpResponse<byte[]> response = http.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() >= 300) {
                throw new RemoteSolver.Failed(UNAVAILABLE + "le lanceur a répondu " + response.statusCode() + ".");
            }
            return response.body().length == 0 ? json.createObjectNode() : json.readTree(response.body());
        } catch (IOException e) {
            throw new RemoteSolver.Failed(UNAVAILABLE + "le lanceur ne répond pas.", new UncheckedIOException(e));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RemoteSolver.Failed(UNAVAILABLE + "attente du lanceur interrompue.", e);
        }
    }

    private void pause() {
        try {
            Thread.sleep(pollEvery.multipliedBy(5));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RemoteSolver.Failed(UNAVAILABLE + "attente interrompue.", e);
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
