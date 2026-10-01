package dev.sylvain.planning.service.weather;

import dev.sylvain.planning.config.ConfigMeteo;
import dev.sylvain.planning.service.EditionContext;
import dev.sylvain.planning.service.notification.OutboundEditionPolicy;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.Optional;
import org.jboss.logging.Logger;

/**
 * The application's fifth {@code @Scheduled} (ADR 0074): once a morning
 * ({@code METEO_CRON}, 6:00 in {@code NOTIFICATIONS_TIMEZONE}), the weather
 * alert of the edition that may emit outward.
 *
 * <p>One edition, not a loop: what may leave the application is decided by
 * {@link OutboundEditionPolicy} and nowhere else, and an instance-wide hour
 * replaces a per-edition one — which would have forced the job to wake hourly
 * like the nightly sends. Same conventions as the other four: {@code SKIP} on
 * overlap, the edition entered through {@link EditionContext#executeIn}, a
 * failure logged and never propagated, switched off under {@code %test}.</p>
 */
@ApplicationScoped
public class WeatherAlertJob {

    private static final Logger LOG = Logger.getLogger(WeatherAlertJob.class);

    private final ConfigMeteo config;

    private final OutboundEditionPolicy policy;

    private final EditionContext editionContext;

    private final WeatherAlertService service;

    @Inject
    public WeatherAlertJob(
            ConfigMeteo config,
            OutboundEditionPolicy policy,
            EditionContext editionContext,
            WeatherAlertService service) {
        this.config = config;
        this.policy = policy;
        this.editionContext = editionContext;
        this.service = service;
    }

    @Scheduled(
            identity = "alerte-meteo",
            cron = "{planning.meteo.cron}",
            timeZone = "{planning.notifications.zone}",
            concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void scheduledRun() {
        run();
    }

    /**
     * One run. Exposed for the tests, which drive it rather than waiting for a
     * cron.
     *
     * @return how the edition's run ended, empty when nothing ran — the
     *         feature cut, no edition allowed to emit, or a failure
     */
    public Optional<WeatherAlertService.RunResult> run() {
        if (!config.enabled()) {
            return Optional.empty();
        }
        Optional<String> edition;
        try {
            edition = policy.emittingEdition();
        } catch (RuntimeException e) {
            LOG.error("The weather alert could not tell which edition may emit; it will try tomorrow", e);
            return Optional.empty();
        }
        if (edition.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(
                    editionContext.executeIn(edition.get(), () -> service.runForCurrentEdition(Instant.now())));
        } catch (RuntimeException e) {
            // Named by id, as the nightly sends do.
            LOG.errorf(e, "The weather alert of edition %s failed; it will try tomorrow", edition.get());
            return Optional.empty();
        }
    }
}
