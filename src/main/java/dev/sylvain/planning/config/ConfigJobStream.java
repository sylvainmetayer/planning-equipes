package dev.sylvain.planning.config;

import java.time.Duration;

import io.smallrye.config.ConfigMapping;

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
}
