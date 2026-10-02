package dev.sylvain.planning.service.webhook;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.sylvain.planning.config.ConfigWebhooks;
import dev.sylvain.planning.service.BusinessError;
import dev.sylvain.planning.service.notification.OutboundEditionPolicy;
import io.quarkus.scheduler.Scheduled;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.RequestOptions;
import io.vertx.core.net.SocketAddress;
import io.vertx.ext.web.client.HttpRequest;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;
import io.vertx.ext.web.codec.BodyCodec;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.net.ConnectException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.net.ssl.SSLException;
import org.jboss.logging.Logger;

/**
 * Sends the queued deliveries over HTTP, and retries them on a schedule.
 *
 * <p>Two ways in, one way out. A delivery is first attempted <b>at once</b>,
 * on a small pool of its own — never on the thread of the operation that
 * caused it, so a receiver that hangs costs nobody their publication. What
 * fails worth retrying is picked up by the sweep — the application's fourth
 * {@code @Scheduled}, every minute ({@code WEBHOOKS_CRON}, ADR 0074) — which
 * also resumes after a restart whatever was still pending. The lease in {@code webhook_livraison} is what
 * keeps the two from sending the same row twice: each row is leased right
 * before its own attempt — never a batch at once, whose last leases would
 * lapse while the first rows are sent — and the attempt is recorded only
 * under the lease it was given.</p>
 *
 * <p>Every attempt asks again whether the edition the payload speaks of may
 * still emit ({@link OutboundEditionPolicy}): a retry, or a « Renvoyer », about
 * an edition deactivated since is given up rather than sent.</p>
 *
 * <p>The schedule: a first attempt, then retries after 1 min, 5 min, 30 min,
 * 2 h and 12 h — six attempts in all. A network failure, a 408, a 429 (whose
 * {@code Retry-After} is honoured) and a 5xx are retried; any other answer is
 * final. A name that does not resolve is retried too — a DNS failure is the
 * likeliest to pass —, while an address the guard refuses is final. A
 * redirect is never followed: the address was checked, the one a redirect
 * names was not.</p>
 *
 * <p>The receiver's body is drained and thrown away, never buffered, never
 * stored: a webhook must not become a way to <em>read</em> an internal
 * service. The journal keeps a status code, a duration and a fixed sentence —
 * never an exception message, which can quote the request line, and with it a
 * Slack address that is a secret.</p>
 */
@ApplicationScoped
public class WebhookDeliverer {

    private static final Logger LOG = Logger.getLogger(WebhookDeliverer.class);

    /** The delay before each retry, in order: five retries after the first attempt. */
    static final List<Duration> RETRY_DELAYS = List.of(
            Duration.ofMinutes(1),
            Duration.ofMinutes(5),
            Duration.ofMinutes(30),
            Duration.ofHours(2),
            Duration.ofHours(12));

    /** The first attempt and the five retries. */
    public static final int MAX_ATTEMPTS = RETRY_DELAYS.size() + 1;

    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);

    /**
     * The whole exchange, from the request sent to the response drained: past
     * it the connection is closed. The idle timeout of the request alone would
     * let a receiver trickling a byte every few seconds hold it for ever.
     */
    static final Duration TOTAL_TIMEOUT = Duration.ofSeconds(10);

    /** Longer than any attempt can take: a lease this old was left by a restart. */
    static final Duration LEASE = Duration.ofMinutes(2);

    /** How many due deliveries one sweep takes on. */
    private static final int SWEEP_BATCH = 50;

    /** The longest {@code Retry-After} honoured: a receiver asking for a week gets the last delay of the schedule. */
    private static final Duration RETRY_AFTER_CAP = Duration.ofHours(12);

    private static final TypeReference<LinkedHashMap<String, Object>> DATA = new TypeReference<>() {};

    private final WebhookRepository repository;

    private final SecretCipher cipher;

    private final OutboundGuard guard;

    private final ObjectMapper mapper;

    private final ConfigWebhooks config;

    private final OutboundEditionPolicy policy;

    private final Vertx vertx;

    private final ExecutorService immediate = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "webhook-delivery");
        thread.setDaemon(true);
        return thread;
    });

    @Inject
    public WebhookDeliverer(
            WebhookRepository repository,
            SecretCipher cipher,
            OutboundGuard guard,
            ObjectMapper mapper,
            ConfigWebhooks config,
            OutboundEditionPolicy policy,
            Vertx vertx) {
        this.repository = repository;
        this.cipher = cipher;
        this.guard = guard;
        this.mapper = mapper;
        this.config = config;
        this.policy = policy;
        this.vertx = vertx;
    }

    /** How one attempt ended. {@code status} is the delivery's state once the attempt is recorded. */
    public record Attempt(DeliveryStatus status, Integer httpStatus, long durationMs, String error) {}

    /** What the wire said, before the schedule decides what it means. */
    record Exchange(
            Integer httpStatus,
            long durationMs,
            String error,
            boolean delivered,
            boolean retryable,
            Duration retryAfter) {

        static Exchange refused(String error) {
            return new Exchange(null, 0, error, false, false, null);
        }
    }

    @Scheduled(
            identity = "webhooks-livraisons",
            cron = "{planning.webhooks.cron}",
            concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void scheduledRun() {
        try {
            deliverDue();
        } catch (RuntimeException e) {
            LOG.error("The webhook sweep failed; the next one will try again", e);
        }
    }

    /**
     * Attempts every delivery whose time has come. Exposed for the tests,
     * which drive it rather than waiting for a cron.
     *
     * @return how many deliveries were attempted
     */
    public int deliverDue() {
        if (!config.enabled()) {
            return 0;
        }
        int attempted = 0;
        for (UUID id : repository.dueIds(SWEEP_BATCH, LEASE)) {
            try {
                // Leased one at a time, right before its attempt: an immediate
                // attempt or a « Renvoyer » that took it since is left alone.
                Optional<Instant> lease = repository.claim(id, LEASE);
                if (lease.isEmpty()) {
                    continue;
                }
                attemptClaimed(id, lease.get());
                attempted++;
            } catch (RuntimeException e) {
                LOG.errorf(e, "Webhook delivery %s could not be attempted; the sweep carries on", id);
            }
        }
        return attempted;
    }

    /** The first attempt, off the caller's thread. Never throws: a queued row is the sweep's otherwise. */
    public void deliverSoon(UUID id) {
        if (!config.enabled()) {
            return;
        }
        try {
            immediate.execute(() -> {
                try {
                    repository.claim(id, LEASE).ifPresent(lease -> attemptClaimed(id, lease));
                } catch (RuntimeException e) {
                    LOG.errorf(e, "Webhook delivery %s failed its first attempt; the sweep will retry", id);
                }
            });
        } catch (RuntimeException e) {
            LOG.warnf(e, "Webhook delivery %s left to the sweep", id);
        }
    }

    /**
     * One attempt, on the caller's thread — the test button, which waits for
     * the answer to show it. The row was inserted already leased, so no sweep
     * can take it in between.
     *
     * @return the outcome, or empty when the webhook was deleted meanwhile
     */
    public Optional<Attempt> deliverNow(UUID id, Instant lease) {
        return Optional.ofNullable(attemptClaimed(id, lease));
    }

    /** How many attempts a delivery of {@code event} gets: one for the test, never retried. */
    public static int maxAttempts(String event) {
        return WebhookEvent.TEST.code().equals(event) ? 1 : MAX_ATTEMPTS;
    }

    /**
     * One attempt of a delivery whose lease this thread holds, recorded under
     * that lease. Never leaves the row unrecorded: an unexpected failure is an
     * attempt that failed, retried on the schedule rather than re-leased every
     * two minutes for the thirty days of the journal.
     */
    Attempt attemptClaimed(UUID id, Instant lease) {
        WebhookDelivery delivery = repository.findDelivery(id).orElse(null);
        Webhook webhook =
                delivery == null ? null : repository.find(delivery.webhookId()).orElse(null);
        if (delivery == null || webhook == null) {
            // Deleted meanwhile: its deliveries went with it, nothing to send.
            return null;
        }
        boolean test = WebhookEvent.TEST.code().equals(delivery.event());
        int attempts = delivery.attempts() + 1;
        Exchange exchange;
        try {
            exchange = exchange(webhook, delivery, test);
        } catch (RuntimeException e) {
            LOG.errorf(e, "Webhook delivery %s failed unexpectedly", id);
            exchange = new Exchange(null, 0, "Erreur interne pendant l'envoi.", false, true, null);
        }
        DeliveryStatus status;
        Instant next = null;
        if (exchange.delivered()) {
            status = DeliveryStatus.DELIVERED;
        } else if (!exchange.retryable()) {
            status = DeliveryStatus.ABANDONED;
        } else if (attempts < maxAttempts(delivery.event())) {
            status = DeliveryStatus.PENDING;
            next = Instant.now().plus(nextDelay(attempts, exchange.retryAfter()));
        } else {
            status = DeliveryStatus.FAILED;
        }
        if (!repository.recordAttempt(
                id,
                lease,
                new WebhookRepository.AttemptOutcome(
                        attempts, status, exchange.httpStatus(), exchange.durationMs(), exchange.error(), next))) {
            LOG.warnf("Webhook delivery %s lost its lease during the attempt; its outcome is not recorded", id);
        }
        return new Attempt(status, exchange.httpStatus(), exchange.durationMs(), exchange.error());
    }

    /** Whether to send at all, then the send. */
    private Exchange exchange(Webhook webhook, WebhookDelivery delivery, boolean test) {
        if (!webhook.active() && !test) {
            return Exchange.refused("Webhook désactivé.");
        }
        WebhookMessage message;
        try {
            message = message(delivery);
        } catch (RuntimeException e) {
            LOG.errorf(e, "Webhook delivery %s carries an unreadable payload", delivery.id());
            return Exchange.refused("Contenu de la livraison illisible.");
        }
        // Asked again at each attempt: the edition that was active when the
        // event was queued may have handed over since, and a retry must not
        // speak for it (ADR 0072). An instance event names no edition.
        if (message.edition() != null && !policy.mayEmit(message.edition().id())) {
            return Exchange.refused("Édition inactive : elle n'émet plus vers l'extérieur.");
        }
        return send(webhook, delivery, message);
    }

    /** The scheduled delay after attempt {@code attempts}, or the receiver's own, whichever is later. */
    static Duration nextDelay(int attempts, Duration retryAfter) {
        Duration scheduled = RETRY_DELAYS.get(Math.min(attempts, RETRY_DELAYS.size()) - 1);
        if (retryAfter == null) {
            return scheduled;
        }
        Duration capped = retryAfter.compareTo(RETRY_AFTER_CAP) > 0 ? RETRY_AFTER_CAP : retryAfter;
        return capped.compareTo(scheduled) > 0 ? capped : scheduled;
    }

    private Exchange send(Webhook webhook, WebhookDelivery delivery, WebhookMessage message) {
        String address;
        String signingKey;
        try {
            address = address(webhook);
            signingKey = webhook.format() == WebhookFormat.GENERIC ? cipher.decrypt(webhook.encryptedSecret()) : null;
        } catch (IllegalStateException e) {
            LOG.errorf(e, "The secret of webhook %s cannot be read", webhook.id());
            return Exchange.refused("Secret illisible : la clé WEBHOOKS_SECRET_KEY a-t-elle changé ?");
        }
        OutboundGuard.Target target;
        try {
            target = guard.checkForSend(address);
        } catch (OutboundGuard.UnresolvedHost _) {
            return new Exchange(null, 0, "Nom introuvable (DNS) : nouvel essai prévu.", false, true, null);
        } catch (BusinessError.Invalid e) {
            return Exchange.refused(e.getMessage());
        }
        WebhookFormats.Rendered rendered =
                WebhookFormats.render(mapper, webhook.format(), delivery.id().toString(), message, webhook.chatId());
        // A client of its own per attempt, closed afterwards: closing it is
        // what cuts a request still running when the deadline passes, which
        // neither the future nor the request offers.
        WebClient client = newClient();
        long start = System.nanoTime();
        try {
            HttpRequest<Void> request = client.request(
                            HttpMethod.POST,
                            SocketAddress.inetSocketAddress(
                                    target.port(), target.address().getHostAddress()),
                            new RequestOptions()
                                    .setHost(target.host())
                                    .setPort(target.port())
                                    .setURI(target.requestUri())
                                    .setSsl(target.https()))
                    .ssl(target.https())
                    .timeout(TOTAL_TIMEOUT.toMillis())
                    .putHeader("Content-Type", rendered.contentType())
                    .as(BodyCodec.none());
            if (signingKey != null) {
                String timestamp = String.valueOf(Instant.now().getEpochSecond());
                request.putHeader("X-Planning-Evenement", message.event())
                        .putHeader("X-Planning-Livraison", delivery.id().toString())
                        .putHeader("X-Planning-Horodatage", timestamp)
                        .putHeader(
                                "X-Planning-Signature",
                                WebhookFormats.signature(signingKey, timestamp, rendered.body()));
            }
            HttpResponse<Void> response = request.sendBuffer(Buffer.buffer(rendered.body()))
                    .toCompletionStage()
                    .toCompletableFuture()
                    .get(TOTAL_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            return classify(response.statusCode(), response.getHeader("Retry-After"), elapsed(start));
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            return new Exchange(null, elapsed(start), "Envoi interrompu.", false, true, null);
        } catch (ExecutionException | TimeoutException e) {
            return new Exchange(null, elapsed(start), networkError(e), false, true, null);
        } finally {
            client.close();
        }
    }

    /** The address a delivery goes to: in clear, decrypted, or built around the Telegram token. */
    private String address(Webhook webhook) {
        return switch (webhook.format()) {
            case GENERIC -> webhook.url();
            case SLACK, DISCORD, MATRIX -> cipher.decrypt(webhook.encryptedSecret());
            case TELEGRAM ->
                "https://api.telegram.org/bot" + cipher.decrypt(webhook.encryptedSecret()) + "/sendMessage";
        };
    }

    private WebhookMessage message(WebhookDelivery delivery) {
        try {
            JsonNode node = mapper.readTree(delivery.payload());
            JsonNode edition = node.get("edition");
            Map<String, Object> data = mapper.convertValue(node.get("donnees"), DATA);
            JsonNode link = node.get("lien");
            return new WebhookMessage(
                    node.get("evenement").asText(),
                    Instant.parse(node.get("survenuLe").asText()),
                    edition == null || edition.isNull()
                            ? null
                            : new WebhookMessage.EditionRef(
                                    edition.get("id").asText(),
                                    edition.get("nom").asText()),
                    data,
                    link == null || link.isNull() ? null : link.asText());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unreadable webhook payload", e);
        }
    }

    /** What an answer means for the schedule. */
    static Exchange classify(int status, String retryAfter, long durationMs) {
        if (status >= 200 && status < 300) {
            return new Exchange(status, durationMs, null, true, false, null);
        }
        if (status == 408 || status == 429 || status >= 500) {
            return new Exchange(
                    status,
                    durationMs,
                    "Le récepteur a répondu " + status + ".",
                    false,
                    true,
                    status == 429 ? retryAfter(retryAfter) : null);
        }
        if (status >= 300 && status < 400) {
            return new Exchange(status, durationMs, "Redirection non suivie (" + status + ").", false, false, null);
        }
        return new Exchange(status, durationMs, "Refusé par le récepteur (" + status + ").", false, false, null);
    }

    /** {@code Retry-After} in seconds or as an HTTP date; {@code null} when absent or unreadable. */
    static Duration retryAfter(String header) {
        if (header == null || header.isBlank()) {
            return null;
        }
        String value = header.trim();
        try {
            long seconds = Long.parseLong(value);
            return seconds < 0 ? null : Duration.ofSeconds(seconds);
        } catch (NumberFormatException _) {
            try {
                Duration until = Duration.between(
                        Instant.now(),
                        ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME)
                                .toInstant());
                return until.isNegative() ? Duration.ZERO : until;
            } catch (DateTimeParseException _) {
                return null;
            }
        }
    }

    /**
     * A fixed sentence per kind of failure. The exception's own message is
     * never kept: Vert.x writes the request line into its timeout message, and
     * for three formats the path is a secret.
     */
    static String networkError(Throwable failure) {
        Throwable cause =
                failure instanceof ExecutionException && failure.getCause() != null ? failure.getCause() : failure;
        for (Throwable t = cause; t != null; t = t.getCause()) {
            if (t instanceof TimeoutException || t.getClass().getSimpleName().contains("Timeout")) {
                return "Délai dépassé (" + TOTAL_TIMEOUT.toSeconds() + " s).";
            }
            if (t instanceof SSLException) {
                return "Échec TLS (certificat refusé ou négociation impossible).";
            }
            if (t instanceof ConnectException) {
                return "Connexion refusée par le récepteur.";
            }
        }
        return "Erreur réseau (" + cause.getClass().getSimpleName() + ").";
    }

    private static long elapsed(long start) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
    }

    private WebClient newClient() {
        return WebClient.create(
                vertx,
                new WebClientOptions()
                        .setFollowRedirects(false)
                        .setConnectTimeout((int) CONNECT_TIMEOUT.toMillis())
                        .setKeepAlive(false)
                        .setVerifyHost(true)
                        .setTrustAll(false)
                        .setUserAgent("planning-equipes-webhooks"));
    }

    @PreDestroy
    void close() {
        immediate.shutdownNow();
    }
}
