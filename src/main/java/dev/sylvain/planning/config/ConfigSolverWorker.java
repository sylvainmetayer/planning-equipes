package dev.sylvain.planning.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import java.util.Optional;

/**
 * Where a solve may run other than here (ADR 0086) — instance settings, never
 * an edition's nor a screen's, like the backup's.
 *
 * <p>A permanent worker known by its {@link #url}; without one, every solve
 * runs here, as it always has.</p>
 */
@ConfigMapping(prefix = "planning.solver.worker")
public interface ConfigSolverWorker {

    /** {@code SOLVER_WORKER_URL}: a worker that is always on. */
    Optional<String> url();

    /** {@code SOLVER_WORKER_SECRET}: what that worker expects. */
    Optional<String> secret();

    /** {@code SOLVER_WORKER_TLS_FINGERPRINT}: the SHA-256 of that worker's certificate. */
    Optional<String> tlsFingerprint();

    /** {@code SOLVER_WORKER_DEFAULT}: {@code local} or {@code remote}, where a solve runs when its launch does not say. */
    @WithDefault("local")
    String defaultPlace();
}
