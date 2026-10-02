package dev.sylvain.planning.service.webhook;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.sylvain.planning.config.ConfigWebhooks;
import dev.sylvain.planning.service.BusinessError;
import dev.sylvain.planning.service.journal.CurrentAction;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.jboss.logging.Logger;

/**
 * The webhooks of the instance: created, edited, tested, and their journal of
 * deliveries read and resent (ADR 0074).
 *
 * <p>A secret is shown <b>once</b> — the HMAC key generated at creation or
 * regeneration — and never again: every read masks it, and the address of a
 * Slack, Discord or Matrix webhook, which is a secret too, is shown as its
 * host alone. The journal of actions records which fields an edit changed,
 * never their values (see {@code CatalogueActions}).</p>
 *
 * <p>No {@code @RefusedWhileSolving}: a solve's landing rewrites the plan and
 * the referential, never these tables.</p>
 */
@ApplicationScoped
public class WebhookService {

    private static final Logger LOG = Logger.getLogger(WebhookService.class);

    /** How long a delivery stays in the journal: the nightly sweep drops what is older. */
    static final Duration RETENTION = Duration.ofDays(30);

    /** How many lines the journal of one webhook shows. */
    private static final int JOURNAL_LIMIT = 100;

    private static final int NAME_MAX = 120;

    private static final String NOT_FOUND_SUFFIX = " » introuvable.";

    /** {@code 123456:ABC-def_…}: digits, a colon, the URL-safe rest — and nothing that could reshape the URL. */
    private static final Pattern TELEGRAM_TOKEN = Pattern.compile("\\d{1,20}:[A-Za-z0-9_-]{20,100}");

    /** A numeric chat id (negative for groups) or a public {@code @channel}. */
    private static final Pattern TELEGRAM_CHAT = Pattern.compile("-?\\d{1,20}|@\\w{5,64}");

    private final WebhookRepository repository;

    private final SecretCipher cipher;

    private final OutboundGuard guard;

    private final WebhookDeliverer deliverer;

    private final ConfigWebhooks config;

    private final CurrentAction currentAction;

    private final ObjectMapper mapper;

    @Inject
    public WebhookService(
            WebhookRepository repository,
            SecretCipher cipher,
            OutboundGuard guard,
            WebhookDeliverer deliverer,
            ConfigWebhooks config,
            CurrentAction currentAction,
            ObjectMapper mapper) {
        this.repository = repository;
        this.cipher = cipher;
        this.guard = guard;
        this.deliverer = deliverer;
        this.config = config;
        this.currentAction = currentAction;
        this.mapper = mapper;
    }

    /**
     * Refuses to boot on a configuration that would lie: a malformed list of
     * allowed networks or key (both parsed when their beans are built), or
     * webhooks whose secrets the key cannot open — missing, or another one
     * than the key they were sealed with: they would look configured while
     * every delivery failed. One secret is tried; a key opens all or none.
     */
    void checkAtStartup(@Observes StartupEvent startup) {
        if (guard.opensInternalNetworks()) {
            LOG.info("Webhooks: WEBHOOKS_RESEAUX_AUTORISES opens internal networks to outgoing calls");
        }
        checkKeyOpensSecrets();
    }

    void checkKeyOpensSecrets() {
        if (!cipher.available()) {
            if (repository.any()) {
                throw new IllegalStateException(
                        "Des webhooks sont configurés mais WEBHOOKS_SECRET_KEY n'est pas définie : "
                                + "leurs secrets sont chiffrés avec cette clé, et sans elle aucune livraison ne pourrait "
                                + "partir. Redéfinissez la clé d'origine (voir docs/exploitation.md).");
            }
            return;
        }
        repository.anySecret().ifPresent(secret -> {
            try {
                cipher.decrypt(secret);
            } catch (IllegalStateException e) {
                throw new IllegalStateException(
                        "WEBHOOKS_SECRET_KEY n'ouvre pas les secrets des webhooks configurés : ce n'est pas la clé "
                                + "avec laquelle ils ont été chiffrés, et aucune livraison ne pourrait partir. "
                                + "Redéfinissez la clé d'origine (voir docs/exploitation.md).",
                        e);
            }
        });
    }

    /* ---------------------------------- Views --------------------------------- */

    /**
     * One webhook as the screen shows it.
     *
     * @param destination where it posts, masked when the address is a secret:
     *                    the host alone for Slack, Discord and Matrix, the
     *                    Bot API without its token for Telegram
     * @param events      the codes subscribed to, {@code planning.publie}…
     * @param lastDelivery the latest delivery, {@code null} before the first
     */
    @Schema(requiredProperties = {"id", "name", "format", "destination", "events", "active"})
    public record WebhookView(
            String id,
            String name,
            WebhookFormat format,
            String destination,
            String chatId,
            List<String> events,
            boolean active,
            Instant createdAt,
            Instant modifiedAt,
            WebhookDeliveryView lastDelivery) {}

    /** One line of the journal of deliveries. {@code error} is a fixed sentence, never the receiver's body. */
    @Schema(requiredProperties = {"id", "event", "attempts", "maxAttempts", "status"})
    public record WebhookDeliveryView(
            String id,
            String event,
            Instant createdAt,
            int attempts,
            int maxAttempts,
            DeliveryStatus status,
            Integer httpStatus,
            Long durationMs,
            String error,
            Instant nextAttemptAt,
            Instant lastAttemptAt) {}

    /**
     * What the form sends. The secret material depends on the format: the
     * address for every format but Telegram, whose bot token travels in
     * {@code token} with {@code chatId}. Blank on an edit means « unchanged »,
     * since a read never gives the secret back.
     */
    public record WebhookRequest(
            String name,
            WebhookFormat format,
            String url,
            String token,
            String chatId,
            List<String> events,
            Boolean active) {}

    /**
     * A webhook just written, and its HMAC secret when one was generated by
     * this very call — shown once, {@code null} otherwise.
     */
    @Schema(requiredProperties = {"webhook"})
    public record WebhookSaved(WebhookView webhook, String secret) {}

    /** A regenerated HMAC secret, shown once. */
    @Schema(requiredProperties = {"secret"})
    public record WebhookSecret(String secret) {}

    /** What « Envoyer un test » shows: the outcome of one synchronous attempt. */
    @Schema(requiredProperties = {"status", "durationMs"})
    public record WebhookTestResult(DeliveryStatus status, Integer httpStatus, long durationMs, String error) {}

    /* ---------------------------------- Reads --------------------------------- */

    public List<WebhookView> list() {
        Map<String, WebhookDelivery> latest = repository.latestDeliveries();
        return repository.list().stream()
                .map(webhook -> view(webhook, latest.get(webhook.id())))
                .toList();
    }

    public List<WebhookDeliveryView> deliveries(String id) {
        require(id);
        return repository.deliveries(id, JOURNAL_LIMIT).stream()
                .map(WebhookService::view)
                .toList();
    }

    /* --------------------------------- Writes --------------------------------- */

    public WebhookSaved create(WebhookRequest request) {
        WebhookFormat format = requireFormat(request);
        String name = requireName(request.name());
        List<WebhookEvent> events = events(request.events());
        String secret = null;
        String url = null;
        String encrypted;
        String chatId = null;
        switch (format) {
            case GENERIC -> {
                url = checkedAddress(request.url());
                secret = SecretCipher.newSecret();
                encrypted = cipher.encrypt(secret);
            }
            case SLACK, DISCORD, MATRIX -> encrypted = cipher.encrypt(checkedAddress(request.url()));
            case TELEGRAM -> {
                encrypted = cipher.encrypt(requireToken(request.token()));
                chatId = requireChat(request.chatId());
            }
            default -> throw new IllegalStateException("Unknown webhook format " + format);
        }
        Webhook webhook = new Webhook(
                UUID.randomUUID().toString(),
                name,
                format,
                url,
                encrypted,
                chatId,
                events,
                request.active() == null || request.active(),
                null,
                null);
        repository.insert(webhook);
        return new WebhookSaved(view(require(webhook.id()), null), secret);
    }

    /**
     * Edits a webhook. Leaving the address or the token blank keeps it —
     * unless the format changes, which needs the new format's own material;
     * turning a webhook into a generic one generates its HMAC secret, given
     * back once like at creation.
     */
    public WebhookSaved update(String id, WebhookRequest request) {
        Webhook before = require(id);
        WebhookFormat format = requireFormat(request);
        String name = requireName(request.name());
        List<WebhookEvent> events = events(request.events());
        boolean formatChanged = format != before.format();
        String secret = null;
        String url = before.url();
        String encrypted = before.encryptedSecret();
        String chatId;
        boolean destinationChanged = false;
        switch (format) {
            case GENERIC -> {
                if (formatChanged || !blank(request.url())) {
                    url = checkedAddress(request.url());
                    destinationChanged = !url.equals(before.url());
                }
                if (formatChanged) {
                    secret = SecretCipher.newSecret();
                    encrypted = cipher.encrypt(secret);
                }
                chatId = null;
            }
            case SLACK, DISCORD, MATRIX -> {
                if (formatChanged || !blank(request.url())) {
                    encrypted = cipher.encrypt(checkedAddress(request.url()));
                    destinationChanged = true;
                }
                url = null;
                chatId = null;
            }
            case TELEGRAM -> {
                if (formatChanged || !blank(request.token())) {
                    encrypted = cipher.encrypt(requireToken(request.token()));
                    destinationChanged = true;
                }
                url = null;
                chatId = requireChat(request.chatId());
            }
            default -> throw new IllegalStateException("Unknown webhook format " + format);
        }
        boolean active = request.active() == null ? before.active() : request.active();
        Webhook after = new Webhook(
                id, name, format, url, encrypted, chatId, events, active, before.createdAt(), before.modifiedAt());
        repository.update(after);
        currentAction.champsModifies(changedFields(before, after, destinationChanged));
        return new WebhookSaved(view(require(id), repository.latestDeliveries().get(id)), secret);
    }

    public void delete(String id) {
        if (!repository.delete(id)) {
            throw webhookNotFound(id);
        }
    }

    /** A new HMAC secret for a generic webhook; deliveries still waiting are signed with it. */
    public WebhookSecret regenerateSecret(String id) {
        Webhook webhook = require(id);
        if (webhook.format() != WebhookFormat.GENERIC) {
            throw new BusinessError.Invalid(
                    "Seul un webhook générique a un secret de signature ; pour les autres formats, l'adresse ou "
                            + "le jeton est le secret : modifiez-le dans le formulaire.");
        }
        String secret = SecretCipher.newSecret();
        repository.update(new Webhook(
                webhook.id(),
                webhook.name(),
                webhook.format(),
                webhook.url(),
                cipher.encrypt(secret),
                webhook.chatId(),
                webhook.events(),
                webhook.active(),
                webhook.createdAt(),
                webhook.modifiedAt()));
        currentAction.champsModifies(List.of("secret"));
        return new WebhookSecret(secret);
    }

    /**
     * Sends the {@code test} event to one webhook and waits for the answer —
     * active or not, subscribed or not: it is the check an admin runs before
     * switching it on. Recorded in its journal like any delivery, never
     * retried — a « Renvoyer » of it is one attempt again.
     */
    public WebhookTestResult test(String id) {
        requireOutboundEnabled();
        Webhook webhook = require(id);
        UUID delivery = UUID.randomUUID();
        WebhookMessage message = new WebhookMessage(WebhookEvent.TEST.code(), Instant.now(), null, Map.of(), null);
        Instant lease = repository.enqueueLeased(
                delivery, webhook.id(), WebhookEvent.TEST.code(), WebhookFormats.envelopeJson(mapper, message));
        WebhookDeliverer.Attempt attempt = deliverer.deliverNow(delivery, lease).orElseThrow(() -> webhookNotFound(id));
        return new WebhookTestResult(attempt.status(), attempt.httpStatus(), attempt.durationMs(), attempt.error());
    }

    /** « Renvoyer »: the delivery starts its schedule again, under the same id. */
    public WebhookDeliveryView resend(String deliveryId) {
        requireOutboundEnabled();
        UUID id = deliveryUuid(deliveryId);
        repository.findDelivery(id).orElseThrow(() -> deliveryNotFound(deliveryId));
        if (!repository.resetForResend(id, WebhookDeliverer.LEASE)) {
            throw new BusinessError.Conflict("Cette livraison est en cours d'envoi : réessayez dans un instant.");
        }
        deliverer.deliverSoon(id);
        return view(repository.findDelivery(id).orElseThrow());
    }

    /** Drops the deliveries past {@link #RETENTION}; run by the nightly sweep. */
    public int purgeDeliveries() {
        return repository.purgeBefore(Instant.now().minus(RETENTION));
    }

    /* -------------------------------- Helpers --------------------------------- */

    private Webhook require(String id) {
        return repository.find(id).orElseThrow(() -> webhookNotFound(id));
    }

    private void requireOutboundEnabled() {
        if (!config.enabled()) {
            throw new BusinessError.Conflict(
                    "Les appels sortants sont coupés sur cette instance (WEBHOOKS_ENABLED=false).");
        }
    }

    private static UUID deliveryUuid(String id) {
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException _) {
            throw deliveryNotFound(id);
        }
    }

    private static BusinessError.NotFound webhookNotFound(String id) {
        return new BusinessError.NotFound("Webhook « " + id + NOT_FOUND_SUFFIX);
    }

    private static BusinessError.NotFound deliveryNotFound(String id) {
        return new BusinessError.NotFound("Livraison « " + id + NOT_FOUND_SUFFIX);
    }

    private static WebhookFormat requireFormat(WebhookRequest request) {
        if (request == null || request.format() == null) {
            throw new BusinessError.Invalid("Le format du webhook est obligatoire.");
        }
        return request.format();
    }

    private static String requireName(String name) {
        if (blank(name)) {
            throw new BusinessError.Invalid("Le nom du webhook est obligatoire.");
        }
        String trimmed = name.strip();
        if (trimmed.length() > NAME_MAX) {
            throw new BusinessError.Invalid("Le nom du webhook dépasse " + NAME_MAX + " caractères.");
        }
        return trimmed;
    }

    /** Checked when it is saved, and again at every send: what resolves today may not tomorrow. */
    private String checkedAddress(String url) {
        if (blank(url)) {
            throw new BusinessError.Invalid("L'adresse du webhook est obligatoire.");
        }
        guard.check(url);
        return url.strip();
    }

    private static String requireToken(String token) {
        if (blank(token) || !TELEGRAM_TOKEN.matcher(token.strip()).matches()) {
            throw new BusinessError.Invalid(
                    "Le jeton du bot Telegram est obligatoire, sous la forme donnée par BotFather (123456:ABC…).");
        }
        return token.strip();
    }

    private static String requireChat(String chatId) {
        if (blank(chatId) || !TELEGRAM_CHAT.matcher(chatId.strip()).matches()) {
            throw new BusinessError.Invalid(
                    "L'identifiant de la conversation Telegram est obligatoire : un nombre (négatif pour un groupe) "
                            + "ou @nom d'un canal public.");
        }
        return chatId.strip();
    }

    private static List<WebhookEvent> events(List<String> codes) {
        Set<WebhookEvent> events = new LinkedHashSet<>();
        for (String code : codes == null ? List.<String>of() : codes) {
            WebhookEvent event = WebhookEvent.ofCode(code)
                    .filter(WebhookEvent.subscribable()::contains)
                    .orElseThrow(() -> new BusinessError.Invalid("Événement de webhook inconnu : « " + code + " »."));
            events.add(event);
        }
        // In the order of the vocabulary, whatever order the form sent.
        return WebhookEvent.subscribable().stream().filter(events::contains).toList();
    }

    private static List<String> changedFields(Webhook before, Webhook after, boolean destinationChanged) {
        List<String> fields = new ArrayList<>();
        if (!before.name().equals(after.name())) {
            fields.add("name");
        }
        if (before.format() != after.format()) {
            fields.add("format");
        }
        if (destinationChanged) {
            fields.add("destination");
        }
        if (!Objects.equals(before.chatId(), after.chatId())) {
            fields.add("chatId");
        }
        if (!before.events().equals(after.events())) {
            fields.add("events");
        }
        if (before.active() != after.active()) {
            fields.add("active");
        }
        return fields;
    }

    private WebhookView view(Webhook webhook, WebhookDelivery latest) {
        return new WebhookView(
                webhook.id(),
                webhook.name(),
                webhook.format(),
                destination(webhook),
                webhook.chatId(),
                webhook.events().stream().map(WebhookEvent::code).toList(),
                webhook.active(),
                webhook.createdAt(),
                webhook.modifiedAt(),
                latest == null ? null : view(latest));
    }

    private static WebhookDeliveryView view(WebhookDelivery delivery) {
        return new WebhookDeliveryView(
                delivery.id().toString(),
                delivery.event(),
                delivery.createdAt(),
                delivery.attempts(),
                WebhookDeliverer.maxAttempts(delivery.event()),
                delivery.status(),
                delivery.httpStatus(),
                delivery.durationMs(),
                delivery.error(),
                delivery.nextAttemptAt(),
                delivery.lastAttemptAt());
    }

    /** Where it posts, as much as may be shown: never a path that is itself a secret. */
    private String destination(Webhook webhook) {
        return switch (webhook.format()) {
            case GENERIC -> webhook.url();
            case TELEGRAM -> "https://api.telegram.org/bot…/sendMessage";
            case SLACK, DISCORD, MATRIX -> {
                try {
                    URI uri = URI.create(cipher.decrypt(webhook.encryptedSecret()));
                    yield uri.getScheme() + "://" + uri.getRawAuthority() + "/…";
                } catch (RuntimeException _) {
                    yield "(adresse illisible : clé WEBHOOKS_SECRET_KEY changée ?)";
                }
            }
        };
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
