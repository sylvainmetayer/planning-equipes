package dev.sylvain.planning.service.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import dev.sylvain.planning.domain.DemandeEchange;
import dev.sylvain.planning.service.EditionContext;
import dev.sylvain.planning.service.ProductName;
import dev.sylvain.planning.service.espace.ApplicationLinks;
import dev.sylvain.planning.service.mail.MailDeliveryOutcome;
import dev.sylvain.planning.service.mail.MailFailureCategory;
import dev.sylvain.planning.service.mail.MailMetrics;
import dev.sylvain.planning.service.mail.MailTemplates;
import dev.sylvain.planning.service.publication.AdminAddress;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.quarkus.mailer.Mail;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The delivery policy of the notifications, now written in a single place. It
 * used to be written by hand on every call — a
 * {@code try/catch (RuntimeException)} around every send, in five services —
 * and nothing stopped anyone from forgetting one.
 */
class NotificationDispatcherTest {

    /** Every notification of these tests is born in the active edition, unless a test says otherwise. */
    private static final EditionContext ACTIVE_EDITION = editionContext(true);

    private static EditionContext editionContext(boolean active) {
        return new EditionContext(null, null) {
            @Override
            public String editionIdCourant() {
                return "E1";
            }

            @Override
            public boolean isActive(String editionId) {
                return active;
            }
        };
    }

    private final List<Mail> envoyes = new ArrayList<>();
    private final NotificationWriter redacteur = new NotificationWriter(
            new AdminAddress(Optional.of("admin@example.org")),
            new ApplicationLinks(Optional.of("https://planning.example.org")),
            ProductName.neutral(),
            MailTemplates.standalone(ProductName.neutral()));
    private NotificationDispatcher expediteur;
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    @BeforeEach
    void buildDispatcher() {
        expediteur = new NotificationDispatcher(
                mails -> envoyes.addAll(List.of(mails)),
                redacteur,
                MailTemplates.standalone(ProductName.neutral()),
                new MailMetrics(registry),
                ACTIVE_EDITION);
    }

    private static Notification oneSubmission() {
        DemandeEchange demande = new DemandeEchange();
        demande.setId("D1");
        demande.setPrevalidationOk(true);
        return new Notification.DemandesSoumises("Alice Dupont", List.of(demande));
    }

    @Test
    void uneNotificationRedigeeEstEnvoyeeAuDestinataireEcrit() {
        expediteur.surNotification(oneSubmission());

        assertThat(envoyes).singleElement().satisfies(mail -> {
            assertThat(mail.getTo()).containsExactly("admin@example.org");
            assertThat(mail.getSubject()).contains("nouvelle demande d'échange");
        });
    }

    /** Nobody to notify: nothing leaves, and above all nothing fails. */
    @Test
    void uneNotificationSansDestinataireNEnvoieRien() {
        expediteur.surNotification(new Notification.DemandesSoumises("Alice Dupont", List.of()));

        assertThat(envoyes).isEmpty();
    }

    /**
     * The central invariant: a broken SMTP must never fail the business
     * operation the notification describes — that operation already happened.
     */
    @Test
    void aFailedSendIsSwallowedWithoutBreakingTheOperation() {
        expediteur = new NotificationDispatcher(
                mails -> {
                    throw new IllegalStateException("SMTP down");
                },
                redacteur,
                MailTemplates.standalone(ProductName.neutral()),
                new MailMetrics(registry),
                ACTIVE_EDITION);

        assertThatCode(() -> expediteur.surNotification(oneSubmission())).doesNotThrowAnyException();
    }

    /** Swallowed is not uncounted: the failure shows on the metrics, under the notification's template. */
    @Test
    void aSwallowedFailureIsStillCounted() {
        expediteur = new NotificationDispatcher(
                mails -> {
                    throw new IllegalStateException("SMTP down");
                },
                redacteur,
                MailTemplates.standalone(ProductName.neutral()),
                new MailMetrics(registry),
                ACTIVE_EDITION);

        expediteur.surNotification(oneSubmission());

        assertThat(registry.get("planning.mail.failures")
                        .tag("template", "demandes-soumises")
                        .counter()
                        .count())
                .isEqualTo(1.0);
    }

    /** The {@code catch} covers the writing as much as the sending. */
    @Test
    void aFailedWritingIsSwallowedToo() {
        NotificationWriter failing = new NotificationWriter(null, null, null, null) {
            @Override
            public Optional<MailDraft> rediger(Notification notification) {
                throw new IllegalStateException("rédaction impossible");
            }
        };
        expediteur = new NotificationDispatcher(
                mails -> envoyes.addAll(List.of(mails)),
                failing,
                MailTemplates.standalone(ProductName.neutral()),
                new MailMetrics(registry),
                ACTIVE_EDITION);

        assertThatCode(() -> expediteur.surNotification(oneSubmission())).doesNotThrowAnyException();
        assertThat(envoyes).isEmpty();
    }

    /**
     * A covoiturage decided while the SMTP relay is down: the decision stands,
     * the admin's call returns, and the lost mail is counted under its template.
     */
    @Test
    void aCarpoolDecisionSurvivesAFailedMail() {
        expediteur = new NotificationDispatcher(
                mails -> {
                    throw new IllegalStateException("SMTP down");
                },
                redacteur,
                MailTemplates.standalone(ProductName.neutral()),
                new MailMetrics(registry),
                ACTIVE_EDITION);

        assertThatCode(() -> {
                    expediteur.surNotification(
                            new Notification.CarpoolValidated("A1", "bob@example.org", "Bob", List.of("Alice"), null));
                    expediteur.surNotification(
                            new Notification.CarpoolSetAside("A1", "alice@example.org", "Alice", "complet", null));
                    expediteur.surNotification(new Notification.CarpoolCancelled(
                            "A1", "alice@example.org", "Alice", List.of("Bob"), "panne", true, null));
                })
                .doesNotThrowAnyException();
        assertThat(registry.get("planning.mail.failures")
                        .tag("template", "covoiturage-valide")
                        .counter()
                        .count())
                .isEqualTo(1.0);
        assertThat(registry.get("planning.mail.failures")
                        .tag("template", "covoiturage-annule")
                        .counter()
                        .count())
                .isEqualTo(1.0);
    }

    /**
     * Only the active edition speaks (ADR 0072): a swap request decided in an
     * edition being prepared reaches nobody, while the instance's own backup
     * alert still goes.
     */
    @Test
    void anInactiveEditionNotifiesNobodyButTheInstanceStillDoes() {
        expediteur = new NotificationDispatcher(
                mails -> envoyes.addAll(List.of(mails)),
                redacteur,
                MailTemplates.standalone(ProductName.neutral()),
                new MailMetrics(registry),
                editionContext(false));

        expediteur.surNotification(oneSubmission());
        assertThat(envoyes).isEmpty();

        expediteur.surNotification(new Notification.BackupFailed(
                java.time.ZonedDateTime.parse("2026-07-12T04:00:00+02:00[Europe/Paris]"), "disque plein", null, 1));
        assertThat(envoyes).hasSize(1);
    }

    /* ---------------- The outcome, returned to the nightly jobs --------------- */

    /** One recorded send, as the capturing log below saw it. */
    private record Recorded(String animateurId, String template, MailDeliveryOutcome outcome) {}

    private NotificationDispatcher recordingDispatcher(io.quarkus.mailer.Mailer mailer, List<Recorded> log) {
        return new NotificationDispatcher(
                mailer,
                redacteur,
                MailTemplates.standalone(ProductName.neutral()),
                new MailMetrics(
                        registry,
                        (animateurId, template, outcome) -> log.add(new Recorded(animateurId, template, outcome))),
                ACTIVE_EDITION);
    }

    /**
     * The nightly jobs learn what became of their mail without the policy
     * changing: a failure still throws nothing, it comes back as an outcome.
     */
    @Test
    void deliverReturnsTheOutcomeAndThrowsNothing() {
        List<Recorded> log = new ArrayList<>();
        NotificationDispatcher reussi = recordingDispatcher(mails -> envoyes.addAll(List.of(mails)), log);
        NotificationDispatcher enPanne = recordingDispatcher(
                mails -> {
                    throw new IllegalStateException("550 5.1.1 recipient refused");
                },
                log);
        Notification relance = new Notification.RelanceConfirmation("A7", "alice@example.org", "Alice", null);

        assertThat(reussi.deliver(relance)).contains(MailDeliveryOutcome.SENT);
        assertThat(enPanne.deliver(relance))
                .contains(
                        new MailDeliveryOutcome(MailDeliveryOutcome.Status.ECHEC, MailFailureCategory.ADRESSE_REFUSEE));
        assertThat(reussi.deliver(new Notification.RelanceConfirmation("A8", " ", "Bruno", null)))
                .as("nobody to write to is not a failure")
                .isEmpty();
    }

    /**
     * A notification to an animateur is recorded against their id — and only
     * their id: the log is not even handed the address. One to the admin
     * records nothing.
     */
    @Test
    void aSendToAnAnimateurIsRecordedByIdAndOneToTheAdminIsNot() {
        List<Recorded> log = new ArrayList<>();
        NotificationDispatcher dispatcher = recordingDispatcher(mails -> envoyes.addAll(List.of(mails)), log);

        dispatcher.surNotification(new Notification.CarpoolSetAside("A9", "alice@example.org", "Alice", null, null));
        dispatcher.surNotification(oneSubmission());

        assertThat(log).containsExactly(new Recorded("A9", "mail/covoiturage-ecarte", MailDeliveryOutcome.SENT));
    }

    /** A database refusing the trace changes nothing to the send it describes. */
    @Test
    void aTraceThatCannotBeWrittenLeavesTheOutcomeAlone() {
        NotificationDispatcher dispatcher = new NotificationDispatcher(
                mails -> envoyes.addAll(List.of(mails)),
                redacteur,
                MailTemplates.standalone(ProductName.neutral()),
                new MailMetrics(registry, (animateurId, template, outcome) -> {
                    throw new IllegalStateException("database down");
                }),
                ACTIVE_EDITION);

        assertThat(dispatcher.deliver(new Notification.RelanceConfirmation("A7", "alice@example.org", "Alice", null)))
                .contains(MailDeliveryOutcome.SENT);
        assertThat(envoyes).hasSize(1);
    }
}
