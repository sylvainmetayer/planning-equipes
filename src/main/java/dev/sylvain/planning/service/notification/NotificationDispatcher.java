package dev.sylvain.planning.service.notification;

import dev.sylvain.planning.service.EditionContext;
import dev.sylvain.planning.service.mail.MailDeliveryOutcome;
import dev.sylvain.planning.service.mail.MailMetrics;
import dev.sylvain.planning.service.mail.MailTemplates;
import io.quarkus.logging.Log;
import io.quarkus.mailer.Mailer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.util.Optional;

/**
 * Delivers every {@link Notification} fired in the application, and owns the
 * one policy that goes with them: <b>best-effort</b>. A notification decorates
 * a business operation that has already happened — a demande submitted, a
 * decision taken, a solve finished — so a broken SMTP server must never roll
 * it back. The failure is logged, loudly, and stops there.
 *
 * <p>That policy used to be re-implemented by hand at each call site (a
 * {@code try/catch (RuntimeException)} around every send, in five services);
 * it is now stated once, and business code cannot forget it because it no
 * longer sends anything — it fires a fact.</p>
 *
 * <p>Deliberately a <b>synchronous</b> {@code @Observes} rather than
 * {@code @ObservesAsync}: the notification must be attempted within the
 * operation that caused it, so "the solve is done" and "the admin was told"
 * stay causally ordered and observable from the same call — the end-to-end
 * tests assert on the mailbox right after the HTTP call returns. Isolation is
 * not lost by being synchronous, it is simply written here instead of being
 * delegated to the container.</p>
 *
 * <p><b>Only the active edition speaks</b> (ADR 0072). A notification born in
 * an edition that is not the active one — a swap decided while preparing next
 * year, a solve finished on a draft — is dropped here, before anything is
 * written: the people it would reach are those of the edition under way.
 * The instance's own messages (the nightly backup) belong to no edition and
 * always go; {@link #editionScoped} says which is which, exhaustively.</p>
 *
 * <p>Explicit admin sends do <b>not</b> go through here: sending an animateur
 * their planning, or an espace access code, must fail loudly so the caller can
 * report who could not be reached. Those stay direct calls on
 * {@code MailService}.</p>
 */
@ApplicationScoped
public class NotificationDispatcher {

    private final Mailer mailer;

    private final NotificationWriter redacteur;

    private final MailTemplates templates;

    private final MailMetrics metrics;

    private final EditionContext editionContext;

    @Inject
    public NotificationDispatcher(
            Mailer mailer,
            NotificationWriter redacteur,
            MailTemplates templates,
            MailMetrics metrics,
            EditionContext editionContext) {
        this.editionContext = editionContext;
        this.mailer = mailer;
        this.redacteur = redacteur;
        this.templates = templates;
        this.metrics = metrics;
    }

    /**
     * The observer every fired {@link Notification} reaches. Its outcome is
     * dropped on purpose: whoever fired a fact does not wait for a mail.
     */
    void surNotification(@Observes Notification notification) {
        deliver(notification);
    }

    /**
     * Writes and sends one notification now, and says what became of it —
     * for the two nightly jobs, which must not count as reminded somebody
     * whose mail never left, and must leave an alert instead.
     *
     * <p>Still best-effort: nothing is thrown, whatever happens. The outcome
     * is <b>returned</b> rather than propagated, so a caller learns of a
     * failure without the policy of {@code docs/architecture.md} changing —
     * the operation it reports on stands either way. The outcome is also
     * recorded against the animateur by {@link MailMetrics} when the
     * notification names one ({@link Notification.ToAnimateur}).</p>
     *
     * <p>The single {@code catch} of the whole notification path, and it wraps
     * writing as well as sending: whatever goes wrong between "a demande was
     * submitted" and "someone was told" must not reach the caller, who already
     * committed the operation being announced.</p>
     *
     * @return empty when there was nobody to write to — no address on the
     *         fiche, no admin address configured, nothing to say
     */
    public Optional<MailDeliveryOutcome> deliver(Notification notification) {
        String animateurId =
                notification instanceof Notification.ToAnimateur toAnimateur ? toAnimateur.animateurId() : null;
        try {
            if (editionScoped(notification) && !fromActiveEdition()) {
                Log.infof(
                        "Notification %s dropped: its edition is not the active one",
                        notification.getClass().getSimpleName());
                return Optional.empty();
            }
            Optional<MailDraft> draft = redacteur.rediger(notification);
            if (draft.isEmpty()) {
                return Optional.empty();
            }
            MailDraft courrier = draft.get();
            metrics.send(
                    mailer,
                    courrier.template(),
                    templates.toMail(courrier.destinataire(), courrier.sujet(), courrier.corps(), courrier.html()),
                    animateurId);
            return Optional.of(MailDeliveryOutcome.SENT);
        } catch (RuntimeException e) {
            Log.errorf(
                    e,
                    "Notification %s could not be delivered; the operation it describes stands",
                    notification.getClass().getSimpleName());
            return Optional.of(MailDeliveryOutcome.failed(e));
        }
    }

    /**
     * Whether the notification belongs to the edition it was fired in — every
     * one but the instance's own messages. A switch without {@code default},
     * so a new case does not compile until somebody decides.
     */
    static boolean editionScoped(Notification notification) {
        return switch (notification) {
            case Notification.BackupFailed _, Notification.BackupRecovered _ -> false;
            case Notification.TargetSolicited _,
                    Notification.DemandeDeclinee _,
                    Notification.DemandesSoumises _,
                    Notification.DeclarationSoumise _,
                    Notification.EmpechementSignale _,
                    Notification.AbsenceReportFiled _,
                    Notification.AbsenceReportAccepted _,
                    Notification.CarpoolValidated _,
                    Notification.CarpoolSetAside _,
                    Notification.CarpoolCancelled _,
                    Notification.ResolutionTerminee _,
                    Notification.RappelVeille _,
                    Notification.RelanceConfirmation _,
                    Notification.PendingEchanges _,
                    // The weather is read for the active edition only, and the
                    // alert speaks of that edition's dates and places.
                    Notification.WeatherAlert _,
                    Notification.WeatherUnreachable _ -> true;
        };
    }

    private boolean fromActiveEdition() {
        return editionContext.isActive(editionContext.editionIdCourant());
    }
}
