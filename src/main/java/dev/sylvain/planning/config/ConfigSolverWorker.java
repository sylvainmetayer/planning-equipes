package dev.sylvain.planning.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import java.time.Duration;
import java.util.Optional;

/**
 * Where a solve may run other than here (ADR 0086) — instance settings, never
 * an edition's nor a screen's, like the backup's.
 *
 * <p>Two ways to have a worker, and they exclude each other: a permanent one
 * known by its {@link #url}, or a machine a launcher creates on demand behind
 * {@link #hookUrl}. With neither, every solve runs here, as it always has.</p>
 */
@ConfigMapping(prefix = "planning.solver.worker")
public interface ConfigSolverWorker {

    /** {@code SOLVER_WORKER_URL}: a worker that is always on. */
    Optional<String> url();

    /** {@code SOLVER_WORKER_SECRET}: what that worker expects; with a hook, one is derived per machine instead. */
    Optional<String> secret();

    /** {@code SOLVER_WORKER_TLS_FINGERPRINT}: the SHA-256 of that worker's certificate; with a hook, the launcher gives it. */
    Optional<String> tlsFingerprint();

    /** {@code SOLVER_WORKER_HOOK_URL}: the launcher that starts and stops a machine. */
    Optional<String> hookUrl();

    /** {@code SOLVER_WORKER_HOOK_SECRET}: presented to the launcher, and the root of each machine's own secret. */
    Optional<String> hookSecret();

    /** {@code SOLVER_WORKER_INSTANCE}: how this application names itself to a launcher that serves several. */
    @WithDefault("planning")
    String instance();

    /** {@code SOLVER_WORKER_DEFAULT}: {@code local} or {@code remote}, where a solve runs when its launch does not say. */
    @WithDefault("local")
    String defaultPlace();

    /** {@code SOLVER_WORKER_START_TIMEOUT}: how long a machine may take to be ready. */
    @WithDefault("PT5M")
    Duration startTimeout();

    /** {@code SOLVER_WORKER_IDLE_TIMEOUT}: how long a machine started by the hook is kept after its last solve. */
    @WithDefault("PT45M")
    Duration idleTimeout();
}
