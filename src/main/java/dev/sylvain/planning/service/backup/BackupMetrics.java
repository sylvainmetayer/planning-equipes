package dev.sylvain.planning.service.backup;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The nightly backup's operating metrics: how long each attempt took and how
 * it ended, when the last successful one ran and how large it was (see
 * {@code docs/observabilite.md} § Métriques).
 *
 * <p>The two gauges only exist once there is something true to say. With no
 * {@code BACKUP_DIR} nothing is ever recorded, and a deployment that never
 * produced a dump shows no timestamp rather than a zero — "the last success
 * was in 1970" would page someone for a feature that is simply off.</p>
 */
@ApplicationScoped
public class BackupMetrics {

    static final String DURATION = "planning.backup.duration";
    static final String LAST_SUCCESS = "planning.backup.last.success.timestamp";
    static final String SIZE = "planning.backup.size";

    @Inject
    MeterRegistry registry;

    private final AtomicLong lastSuccessEpochSeconds = new AtomicLong();
    private final AtomicLong sizeBytes = new AtomicLong();
    private final AtomicBoolean gaugesBound = new AtomicBoolean();

    /** One attempt, successful or not. */
    void attempted(boolean succeeded, Duration duration) {
        Timer.builder(DURATION)
                .description("Duration of backup attempts")
                .tag("outcome", succeeded ? "success" : "failure")
                .register(registry)
                .record(duration);
    }

    /**
     * A dump that exists: written just now, or found at startup — the
     * counters restart at zero with the process, the date of the last good
     * backup must not.
     */
    void succeeded(Instant at, long bytes) {
        lastSuccessEpochSeconds.set(at.getEpochSecond());
        sizeBytes.set(bytes);
        if (gaugesBound.compareAndSet(false, true)) {
            Gauge.builder(LAST_SUCCESS, lastSuccessEpochSeconds, AtomicLong::get)
                    .description("When the last successful backup ran, in seconds since the epoch")
                    .baseUnit("seconds")
                    .register(registry);
            Gauge.builder(SIZE, sizeBytes, AtomicLong::get)
                    .description("Size of the last successful backup")
                    .baseUnit("bytes")
                    .register(registry);
        }
    }
}
