package dev.sylvain.planning.config;

import io.smallrye.config.ConfigMapping;
import java.time.Duration;

/**
 * The server-sent events stream that follows solver jobs.
 *
 * <p>The heartbeat is not a nicety: an idle solver sends nothing for hours, and
 * a reverse proxy — Pangolin, in this deployment — closes a connection it
 * believes dead. It also doubles as the client's liveness proof, which is what
 * lets the browser tell "nothing is happening" from "the stream died", and fall
 * back to polling in the second case.</p>
 */
@ConfigMapping(prefix = "planning.jobs.stream")
public interface ConfigJobStream {

    Duration heartbeat();

    /**
     * How often the running solve's score curve is flushed to the open streams
     * (issue #304). This is the second half of the rate limiting — the first
     * being the sampling {@code SolverScoreTrace} applies as the solver
     * announces its improvements. A tick with nothing new emits nothing, so an
     * idle solver costs no traffic here.
     */
    Duration score();
}
