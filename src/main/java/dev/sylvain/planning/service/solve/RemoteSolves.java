package dev.sylvain.planning.service.solve;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.sylvain.planning.config.ConfigSolverWorker;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.service.BusinessError;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Solves run on the permanent worker instead of here (ADR 0086, in what ADR 0100
 * leaves of it): an address and a secret, nothing to start. The machine
 * on demand a launcher used to create behind an HTTP hook is gone.
 *
 * <p>A remote solve that fails <b>fails the job</b>, with its reason; nothing
 * falls back on solving here, which would silently double the wait the
 * operator was told to expect. The persisted plan is never touched by a
 * failure.</p>
 */
@ApplicationScoped
public class RemoteSolves {

    /**
     * What the Solveur screen needs to offer the choice of where a solve runs.
     *
     * @param configured      whether a solve can run elsewhere at all
     * @param remoteByDefault where a solve runs when its launch does not say
     */
    public record ComputeMachine(boolean configured, boolean remoteByDefault) {}

    private final ConfigSolverWorker config;
    private final RemoteSolveRepository repository;
    private final ObjectMapper json;
    private final SolveExchange exchange;
    private final String version;

    /** How often the worker is asked where its solve stands. */
    private Duration pollEvery = Duration.ofSeconds(1);

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
        return config.url().isPresent();
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

    /** Refuses to boot on a configuration that could only fail later, in the middle of somebody's solve. */
    void onStart(@Observes StartupEvent start) {
        check(config);
    }

    static void check(ConfigSolverWorker config) {
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
                cancelled -> permanentWorker(),
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
        boolean detached = false;
        try {
            return new SolveRunner.Solved(solver.solve(problem), solver.feasibilityFirst());
        } catch (RemoteSolver.Detached e) {
            detached = true;
            throw e;
        } finally {
            if (!detached) {
                repository.deletePending(ticket.jobId());
            }
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

    public ComputeMachine view() {
        return new ComputeMachine(configured(), remoteByDefault());
    }

    private WorkerClient permanentWorker() {
        return new WorkerClient(
                new WorkerClient.Endpoint(
                        config.url().orElseThrow(),
                        config.secret().orElseThrow(),
                        config.tlsFingerprint().orElse(null)),
                json);
    }
}
