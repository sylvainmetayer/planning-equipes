package dev.sylvain.planning.service.webhook;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.sylvain.planning.config.ConfigWebhooks;
import dev.sylvain.planning.domain.Edition;
import dev.sylvain.planning.service.BusinessError;
import dev.sylvain.planning.service.EditionContext;
import dev.sylvain.planning.service.edition.EditionRepository;
import dev.sylvain.planning.service.espace.ApplicationLinks;
import dev.sylvain.planning.service.notification.Notification;
import dev.sylvain.planning.service.notification.OutboundEditionPolicy;
import dev.sylvain.planning.service.publication.PlanningPublished;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jboss.logging.Logger;

/**
 * Turns what the application announces into queued webhook deliveries.
 *
 * <p>It observes the facts already fired for the mails — the sealed
 * {@link Notification} — through an exhaustive {@code switch}, so a new case
 * does not compile until somebody decides whether it leaves the application
 * and as what; and it observes {@link PlanningPublished}, which has no mail of
 * its own.</p>
 *
 * <p>A plain synchronous {@code @Observes}, deliberately not
 * {@code during = AFTER_SUCCESS}: the application opens no JTA transaction, and
 * without one a transactional observer is called at once anyway — the
 * annotation would promise a commit that does not exist. Every fact is fired
 * after its own write is committed, so the observer only <b>inserts</b> rows
 * (one per subscribed webhook), and the sending happens on another thread.
 * Nothing here propagates: an announcement never costs the operation it
 * announces.</p>
 *
 * <p>An event about an edition leaves only if {@link OutboundEditionPolicy}
 * lets that edition emit — only the active one does (ADR 0072); an event of
 * the instance — the nightly backup — always does.</p>
 */
@ApplicationScoped
public class WebhookEmitter {

    private static final Logger LOG = Logger.getLogger(WebhookEmitter.class);

    /** One event about to be queued: what it is, its counts, the screen to open. */
    record Occurrence(WebhookEvent event, Map<String, Object> data, String link) {}

    private final ConfigWebhooks config;

    private final OutboundEditionPolicy policy;

    private final WebhookRepository repository;

    private final WebhookDeliverer deliverer;

    private final EditionContext editionContext;

    private final EditionRepository editionRepository;

    private final ApplicationLinks links;

    private final ObjectMapper mapper;

    @Inject
    public WebhookEmitter(
            ConfigWebhooks config,
            OutboundEditionPolicy policy,
            WebhookRepository repository,
            WebhookDeliverer deliverer,
            EditionContext editionContext,
            EditionRepository editionRepository,
            ApplicationLinks links,
            ObjectMapper mapper) {
        this.config = config;
        this.policy = policy;
        this.repository = repository;
        this.deliverer = deliverer;
        this.editionContext = editionContext;
        this.editionRepository = editionRepository;
        this.links = links;
        this.mapper = mapper;
    }

    void onNotification(@Observes Notification notification) {
        try {
            occurrence(notification, links).ifPresent(this::emit);
        } catch (RuntimeException e) {
            LOG.errorf(
                    e,
                    "Webhook event for %s could not be queued; the operation it describes stands",
                    notification.getClass().getSimpleName());
        }
    }

    void onPlanningPublished(@Observes PlanningPublished published) {
        try {
            emit(occurrence(published, links));
        } catch (RuntimeException e) {
            LOG.error("Webhook event for a publication could not be queued; the publication stands", e);
        }
    }

    /**
     * Which notification leaves the application, and as what. Exhaustive and
     * without a {@code default}: a new {@link Notification} is a compile error
     * here until it is mapped — to an event, or explicitly to none.
     *
     * <p>Everything addressed to one person — a reminder, a covoiturage
     * decision, a colleague's answer — stays a mail: a webhook speaks to the
     * organisation, about counts.</p>
     */
    static Optional<Occurrence> occurrence(Notification notification, ApplicationLinks links) {
        return switch (notification) {
            case Notification.DemandesSoumises n ->
                Optional.of(new Occurrence(
                        WebhookEvent.SWAP_SUBMITTED,
                        data("nombre", n.demandes().size()),
                        links.echangesScreen().orElse(null)));
            case Notification.PendingEchanges n ->
                Optional.of(new Occurrence(
                        WebhookEvent.SWAPS_PENDING,
                        data("nombre", n.nombre(), "ancienneteMaxJours", n.joursMax()),
                        links.echangesScreen().orElse(null)));
            case Notification.DeclarationSoumise n ->
                Optional.of(new Occurrence(
                        WebhookEvent.AVAILABILITY_DECLARED,
                        data("joursIndisponibles", n.joursIndisponibles(), "souhaits", n.souhaits()),
                        links.disponibilitesScreen().orElse(null)));
            case Notification.ResolutionTerminee n ->
                Optional.of(new Occurrence(
                        WebhookEvent.SOLVE_FINISHED,
                        data("score", n.score(), "faisable", n.faisable()),
                        links.problemesScreen().orElse(null)));
            case Notification.BackupFailed n ->
                Optional.of(new Occurrence(
                        WebhookEvent.BACKUP_FAILED,
                        data(
                                "tentativeLe",
                                n.attemptedAt().toInstant().toString(),
                                "raison",
                                backupFailureKind(n.reason()),
                                "echecsConsecutifs",
                                n.consecutiveFailures()),
                        links.parametresGlobauxScreen().orElse(null)));
            case Notification.TargetSolicited _,
                    Notification.DemandeDeclinee _,
                    Notification.EmpechementSignale _,
                    Notification.AbsenceReportFiled _,
                    Notification.AbsenceReportAccepted _,
                    Notification.CarpoolValidated _,
                    Notification.CarpoolSetAside _,
                    Notification.CarpoolCancelled _,
                    Notification.RappelVeille _,
                    Notification.RelanceConfirmation _,
                    Notification.BackupRecovered _ -> Optional.empty();
        };
    }

    static Occurrence occurrence(PlanningPublished published, ApplicationLinks links) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("instantaneId", published.snapshotId());
        data.put("destinataires", published.recipients());
        data.put("envoyes", published.sent());
        data.put("sansEmail", published.withoutEmail());
        data.put("echecs", published.failed());
        data.put("differes", published.deferred());
        data.put("planningsChanges", published.changed());
        return new Occurrence(
                WebhookEvent.PLANNING_PUBLISHED, data, links.publicationScreen().orElse(null));
    }

    /** Queues {@code occurrence} for every active webhook subscribed to it, and starts the first attempts. */
    void emit(Occurrence occurrence) {
        if (!config.enabled()) {
            return;
        }
        WebhookMessage.EditionRef edition = null;
        if (!occurrence.event().instanceLevel()) {
            edition = currentEdition().orElse(null);
            if (edition == null || !policy.mayEmit(edition.id())) {
                return;
            }
        }
        var subscribers = repository.subscribers(occurrence.event());
        if (subscribers.isEmpty()) {
            return;
        }
        String payload = WebhookFormats.envelopeJson(
                mapper,
                new WebhookMessage(
                        occurrence.event().code(), Instant.now(), edition, occurrence.data(), occurrence.link()));
        for (Webhook webhook : subscribers) {
            UUID id = UUID.randomUUID();
            repository.enqueue(id, webhook.id(), occurrence.event().code(), payload);
            deliverer.deliverSoon(id);
        }
    }

    /**
     * The edition the fact was fired in: the request's, or the one a job
     * entered through {@code executeIn}. Empty off both, or when the request
     * named no edition it may use — a fact about an edition fired from nowhere
     * is not sent rather than sent about another one.
     */
    private Optional<WebhookMessage.EditionRef> currentEdition() {
        String id;
        try {
            id = editionContext.editionIdCourant();
        } catch (IllegalStateException | BusinessError.EditionRefused e) {
            return Optional.empty();
        }
        String name = editionRepository.listEditions().stream()
                .filter(edition -> edition.getId().equals(id))
                .map(Edition::getNom)
                .findFirst()
                .orElse(id);
        return Optional.of(new WebhookMessage.EditionRef(id, name));
    }

    /**
     * What a failed backup says outside: a fixed sentence per kind of failure,
     * never the reason itself. That one is an exception message, and it quotes
     * {@code pg_dump}'s own output — the database host, its user, its name, a
     * path on the server —, which has no business in a third-party chat. The
     * detail stays in the application, on the Paramètres screen the link opens.
     */
    static String backupFailureKind(String reason) {
        String text = reason == null ? "" : reason;
        if (text.contains("did not finish within")) {
            return "pg_dump n'a pas terminé dans le délai imparti";
        }
        if (text.startsWith("Could not run")) {
            return "pg_dump n'a pas pu être lancé";
        }
        if (text.contains(" failed (exit ")) {
            return "pg_dump a échoué";
        }
        return "la sauvegarde n'a pas abouti";
    }

    private static Map<String, Object> data(Object... keysAndValues) {
        Map<String, Object> data = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            data.put((String) keysAndValues[i], keysAndValues[i + 1]);
        }
        return data;
    }
}
