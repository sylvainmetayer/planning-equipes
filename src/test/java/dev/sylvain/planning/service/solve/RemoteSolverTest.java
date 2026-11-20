package dev.sylvain.planning.service.solve;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.timefold.solver.core.api.score.HardMediumSoftScore;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.ParametresQualite;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.service.EmptyReferenceData;
import dev.sylvain.planning.service.scenario.ScenarioYamlReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The application and a worker talking over real HTTP (ADR 0086): the problem
 * leaves, is solved by {@link SolveWorker} behind {@link SolveWorkerServer},
 * and its plan comes back on the application's own problem.
 *
 * <p>That the search itself is the same on both sides is
 * {@link SolveExchangeTest}'s proof, at a fixed number of calculations; here
 * the budget is a duration, as in production, and what is asserted is the
 * journey — never that two clocks stopped on the same plan.</p>
 */
class RemoteSolverTest {

    private static final String SCENARIO = "gamme-06-3j-4stands-8animateurs-contraintes-ad-hoc.yaml";
    private static final String SECRET = "s3cret-of-the-test";
    private static final String VERSION = "1.2.3";

    private final SolveWorker worker = new SolveWorker();
    private SolveWorkerServer server;
    private SolverConfiguration configuration;
    private PlanningEvenement prepared;
    private final AtomicBoolean shuttingDown = new AtomicBoolean();
    private final AtomicReference<RemoteSolver.Resume> submitted = new AtomicReference<>();

    @BeforeEach
    void startAWorkerAndPrepareAProblem() throws IOException {
        server = new SolveWorkerServer(
                worker, SECRET, VERSION, new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), null);
        EmptyReferenceData referenceData = new EmptyReferenceData();
        configuration = new SolverConfiguration(
                30L,
                0L,
                ParametresQualite.EMPLACEMENTS_DISTINCTS_PAR_JOUR_MAX_PAR_DEFAUT,
                referenceData,
                ConfigProvider.getConfig());
        prepared = ScenarioYamlReader.fromDto(
                        ScenarioYamlReader.readScenario(ScenarioYamlReader.scenarioPath(SCENARIO)),
                        referenceData::getParametresLegaux)
                .planning();
        new SolveRunner(configuration, referenceData, null).prepareProblem(prepared);
    }

    @AfterEach
    void stopTheWorker() {
        server.close();
    }

    private WorkerClient client(String secret) {
        return new WorkerClient(
                new WorkerClient.Endpoint("http://127.0.0.1:" + server.port(), secret, null), new ObjectMapper());
    }

    private RemoteSolver solver(long seconds, String secret, String version, RemoteSolver.Resume resume) {
        return new RemoteSolver(
                cancelled -> client(secret),
                new SolveExchange(new ObjectMapper()),
                configuration.solutionManager(),
                new SolverConfiguration.SearchSettings(seconds, 0, 0, SolverEngine.TIMEFOLD),
                version,
                resume,
                (solveId, realIds) -> submitted.set(new RemoteSolver.Resume(solveId, realIds)),
                shuttingDown::get,
                Duration.ofMillis(50));
    }

    @Test
    void aPlanSolvedOnTheWorkerLandsOnTheApplicationsOwnProblem() {
        RemoteSolver solver = solver(2, SECRET, VERSION, null);
        List<Long> times = new CopyOnWriteArrayList<>();
        solver.addEventListener(event -> times.add(event.getTimeMillisSpent()));

        PlanningEvenement solved = solver.solve(prepared);

        assertThat(solved).isSameAs(prepared);
        assertThat(solved.getScore().isFeasible()).isTrue();
        // Real people hold the seats again: the pseudonyms stayed on the wire.
        assertThat(solved.getPostes().stream()
                        .map(PosteAffectation::getAnimateur)
                        .filter(Objects::nonNull)
                        .collect(Collectors.toSet()))
                .isNotEmpty()
                .isSubsetOf(solved.getAnimateurs());
        assertThat(times).isNotEmpty().isSorted();
        assertThat(submitted.get().realIds().values())
                .containsExactlyInAnyOrderElementsOf(
                        prepared.getAnimateurs().stream().map(Animateur::getId).toList());
        assertThat(worker.isSolving()).isFalse();
    }

    @Test
    void aWrongSecretOrAnotherVersionFailsBeforeAnythingIsSolved() {
        assertThatThrownBy(() -> solver(2, "not-the-secret", VERSION, null).solve(prepared))
                .isInstanceOf(RemoteSolver.Failed.class)
                .hasMessageContaining("indisponible");
        assertThatThrownBy(() -> solver(2, SECRET, "9.9.9", null).solve(prepared))
                .isInstanceOf(RemoteSolver.Failed.class)
                .hasMessageContaining("version " + VERSION);
        assertThat(worker.isSolving()).isFalse();
        assertThat(prepared.getPostes()).allMatch(poste -> poste.getAnimateur() == null);
    }

    @Test
    void aStopEndsTheWorkersSolveAndItsBestPlanLands() {
        RemoteSolver solver = solver(120, SECRET, VERSION, null);
        solver.addEventListener(event -> solver.terminateEarly());
        long start = System.nanoTime();

        PlanningEvenement solved = solver.solve(prepared);

        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(60));
        assertThat(solved.getScore()).isNotNull();
        assertThat(worker.isSolving()).isFalse();
    }

    @Test
    void anApplicationGoingDownLeavesTheWorkerSolvingAndFollowsItAgainAfterwards() {
        RemoteSolver first = solver(3, SECRET, VERSION, null);
        first.addEventListener(event -> {
            shuttingDown.set(true);
            first.terminateEarly();
        });

        assertThatThrownBy(() -> first.solve(prepared)).isInstanceOf(RemoteSolver.Detached.class);
        assertThat(prepared.getPostes()).allMatch(poste -> poste.getAnimateur() == null);

        // The next start: same problem rebuilt, the solve found again by its id.
        shuttingDown.set(false);
        RemoteSolver.Resume resume = submitted.get();
        submitted.set(null);
        PlanningEvenement solved = solver(3, SECRET, VERSION, resume).solve(prepared);

        assertThat(solved.getScore().isFeasible()).isTrue();
        assertThat(submitted.get()).as("a resumed solve is not sent again").isNull();
    }

    @Test
    void aWorkerSolvesOneProblemAtATimeAndForgetsAnIdItNeverGave() {
        SolveExchange exchange = new SolveExchange(new ObjectMapper());
        SolverConfiguration.SearchSettings settings =
                new SolverConfiguration.SearchSettings(30, 0, 0, SolverEngine.TIMEFOLD);
        WorkerClient client = client(SECRET);
        String id = client.submit(exchange.outbound(prepared).payload(), settings, VERSION);

        assertThatThrownBy(() -> client.submit(exchange.outbound(prepared).payload(), settings, VERSION))
                .isInstanceOf(WorkerClient.Refused.class)
                .satisfies(refusal ->
                        assertThat(((WorkerClient.Refused) refusal).status()).isEqualTo(409));
        assertThatThrownBy(() -> client.status("another-id", 0))
                .isInstanceOf(WorkerClient.Refused.class)
                .satisfies(refusal ->
                        assertThat(((WorkerClient.Refused) refusal).status()).isEqualTo(404));
        assertThatThrownBy(() -> client.submit(new byte[] {1, 2, 3}, settings, VERSION))
                .isInstanceOf(WorkerClient.Refused.class);

        client.stop(id);
        assertThat(client.logs()).isNotNull();
    }

    @Test
    void aPlanWhoseScoreIsNotTheOneClaimedIsRefusedAndTheProblemLeftAsItWas() {
        HardMediumSoftScore before = configuration.solutionManager().update(prepared);
        Map<String, String> noOne = Map.of();
        RemoteSolver solver = solver(2, SECRET, VERSION, new RemoteSolver.Resume(startASolveNobodyFollows(), noOne));

        // Followed with a mapping that knows none of its pseudonyms: every holder is a stranger.
        assertThatThrownBy(() -> solver.solve(prepared))
                .isInstanceOf(RemoteSolver.Failed.class)
                .hasMessageContaining("Plan refusé");
        assertThat(configuration.solutionManager().update(prepared)).isEqualTo(before);
    }

    @Test
    void overTlsOnlyTheWorkerWhoseCertificateIsPinnedIsSpokenTo(@TempDir Path directory) throws Exception {
        Path keystore = directory.resolve("worker.p12");
        Process keytool = new ProcessBuilder(
                        Path.of(System.getProperty("java.home"), "bin", "keytool")
                                .toString(),
                        "-genkeypair",
                        "-alias",
                        "worker",
                        "-keyalg",
                        "EC",
                        "-dname",
                        "CN=solve-worker",
                        "-storetype",
                        "PKCS12",
                        "-keystore",
                        keystore.toString(),
                        "-storepass",
                        "changeit")
                .inheritIO()
                .start();
        assertThat(keytool.waitFor()).isZero();
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(keystore)) {
            store.load(in, "changeit".toCharArray());
        }
        String fingerprint = HexFormat.ofDelimiter(":")
                .withUpperCase()
                .formatHex(MessageDigest.getInstance("SHA-256")
                        .digest(store.getCertificate("worker").getEncoded()));

        try (SolveWorkerServer secured = new SolveWorkerServer(
                new SolveWorker(),
                SECRET,
                VERSION,
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 0),
                SolveWorkerServer.tls(keystore, "changeit"))) {
            String url = "https://127.0.0.1:" + secured.port();

            // As openssl prints it — colons, upper case — and the certificate
            // names no address: the pin is what vouches for it.
            assertThat(new WorkerClient(new WorkerClient.Endpoint(url, SECRET, fingerprint), new ObjectMapper())
                            .version())
                    .isEqualTo(VERSION);
            WorkerClient stranger =
                    new WorkerClient(new WorkerClient.Endpoint(url, SECRET, "00".repeat(32)), new ObjectMapper());
            assertThatThrownBy(stranger::version).isInstanceOf(UncheckedIOException.class);
            WorkerClient unpinned = new WorkerClient(new WorkerClient.Endpoint(url, SECRET, null), new ObjectMapper());
            assertThatThrownBy(unpinned::version)
                    .as("a self-signed certificate nobody pinned is nobody's")
                    .isInstanceOf(UncheckedIOException.class);
        }

        // The same worker as its program starts it, from the environment alone.
        try (SolveWorkerServer started = SolveWorkerServer.start(Map.of(
                "SOLVER_WORKER_SECRET",
                SECRET,
                "SOLVER_WORKER_PORT",
                "0",
                "SOLVER_WORKER_TLS_KEYSTORE",
                keystore.toString(),
                "SOLVER_WORKER_TLS_KEYSTORE_PASSWORD",
                "changeit"))) {
            WorkerClient pinned = new WorkerClient(
                    new WorkerClient.Endpoint("https://127.0.0.1:" + started.port(), SECRET, fingerprint),
                    new ObjectMapper());
            assertThat(pinned.version()).isEqualTo("dev");
        }
    }

    @Test
    void aWorkerWithoutTlsListensOnTheLoopbackAddressOnlyAndNeedsASecret() {
        SolveWorker idle = new SolveWorker();
        InetSocketAddress everywhere = new InetSocketAddress(0);
        InetSocketAddress loopback = new InetSocketAddress(InetAddress.getLoopbackAddress(), 0);

        assertThatThrownBy(() -> new SolveWorkerServer(idle, SECRET, VERSION, everywhere, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("loopback");
        assertThatThrownBy(() -> new SolveWorkerServer(idle, " ", VERSION, loopback, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SOLVER_WORKER_SECRET");
    }

    @Test
    void aWorkerStartedFromItsEnvironmentWithoutAKeystoreStaysOnTheLoopbackAddress() throws Exception {
        try (SolveWorkerServer started =
                SolveWorkerServer.start(Map.of("SOLVER_WORKER_SECRET", SECRET, "SOLVER_WORKER_PORT", "0"))) {
            WorkerClient plain = new WorkerClient(
                    new WorkerClient.Endpoint("http://127.0.0.1:" + started.port(), SECRET, null), new ObjectMapper());

            assertThat(plain.version()).isEqualTo("dev");
        }
        assertThatThrownBy(() -> SolveWorkerServer.start(Map.of("SOLVER_WORKER_PORT", "0")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SOLVER_WORKER_SECRET");
    }

    @Test
    void aSolveTheWorkerNoLongerKnowsFailsAtOnceWithTheEndOfItsLog() {
        RemoteSolver solver =
                solver(2, SECRET, VERSION, new RemoteSolver.Resume("an-id-of-before-its-restart", Map.of()));

        assertThatThrownBy(() -> solver.solve(prepared))
                .isInstanceOf(RemoteSolver.Failed.class)
                .hasMessageContaining("a perdu ce calcul");
        assertThat(prepared.getPostes()).allMatch(poste -> poste.getAnimateur() == null);
    }

    @Test
    void theQueueSeesASolverLikeAnyOtherWhoseBestScoresCarryNoSolution() {
        RemoteSolver solver = solver(1, SECRET, VERSION, null);
        List<Boolean> solvingWhenToldOfABest = new CopyOnWriteArrayList<>();
        List<Object> solutions = new CopyOnWriteArrayList<>();
        solver.addEventListener(event -> {
            solvingWhenToldOfABest.add(solver.isSolving());
            assertThat((Object) event.getNewBestScore()).isNotNull();
            assertThat(event.isNewBestSolutionInitialized()).isTrue();
            assertThat(event.isEveryProblemChangeProcessed()).isTrue();
            assertThat(event.getProducerId()).isNull();
            if (event.getNewBestSolution() != null) {
                solutions.add(event.getNewBestSolution());
            }
        });
        var silenced = new java.util.concurrent.atomic.AtomicInteger();
        ai.timefold.solver.core.api.solver.event.SolverEventListener<PlanningEvenement> removed =
                event -> silenced.incrementAndGet();
        solver.addEventListener(removed);
        solver.removeEventListener(removed);

        assertThat(solver.isSolving()).isFalse();
        assertThat(solver.isTerminateEarly()).isFalse();
        solver.solve(prepared);

        assertThat(solvingWhenToldOfABest).isNotEmpty().containsOnly(true);
        assertThat(solutions).isEmpty();
        assertThat(silenced).hasValue(0);
        assertThat(solver.isSolving()).isFalse();
        assertThat(solver.isEveryProblemChangeProcessed()).isTrue();
        assertThatThrownBy(() -> solver.addProblemChange(null)).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> solver.addProblemChanges(List.of())).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void aSolveStoppedBeforeTheWorkerHasTheProblemSendsNothing() {
        RemoteSolver solver = solver(30, SECRET, VERSION, null);
        solver.terminateEarly();

        assertThat(solver.isTerminateEarly()).isTrue();
        assertThatThrownBy(() -> solver.solve(prepared)).isInstanceOf(RemoteSolver.Cancelled.class);
        assertThat(submitted.get()).isNull();
        assertThat(worker.isSolving()).isFalse();
    }

    @Test
    void aFollowingThreadInterruptedOutsideAShutdownFailsTheJob() throws InterruptedException {
        RemoteSolver solver = solver(30, SECRET, VERSION, null);
        AtomicReference<Throwable> outcome = new AtomicReference<>();
        Thread following = new Thread(() -> {
            try {
                solver.solve(prepared);
            } catch (RuntimeException e) {
                outcome.set(e);
            }
        });
        solver.addEventListener(event -> following.interrupt());

        following.start();
        following.join(Duration.ofSeconds(30));

        assertThat(outcome.get()).isInstanceOf(RemoteSolver.Failed.class).hasMessageContaining("interrompu");
        client(SECRET).stop(submitted.get().solveId());
    }

    private String startASolveNobodyFollows() {
        return client(SECRET)
                .submit(
                        new SolveExchange(new ObjectMapper()).outbound(prepared).payload(),
                        new SolverConfiguration.SearchSettings(1, 0, 0, SolverEngine.TIMEFOLD),
                        VERSION);
    }
}
