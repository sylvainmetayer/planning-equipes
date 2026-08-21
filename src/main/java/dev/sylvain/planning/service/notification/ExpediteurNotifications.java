package dev.sylvain.planning.service.notification;

import io.quarkus.logging.Log;
import io.quarkus.mailer.Mail;
import io.quarkus.mailer.Mailer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

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
 * <p>Explicit admin sends do <b>not</b> go through here: sending an animateur
 * their planning, or an espace access code, must fail loudly so the caller can
 * report who could not be reached. Those stay direct calls on
 * {@code MailService}.</p>
 */
@ApplicationScoped
public class ExpediteurNotifications {

    @Inject
    Mailer mailer;

    @Inject
    RedacteurNotifications redacteur;

    /**
     * The single {@code catch} of the whole notification path, and it wraps
     * writing as well as sending: whatever goes wrong between "a demande was
     * submitted" and "someone was told" must not reach the caller, who already
     * committed the operation being announced.
     */
    void surNotification(@Observes Notification notification) {
        try {
            redacteur.rediger(notification)
                    .ifPresent(courrier -> mailer.send(Mail.withText(
                            courrier.destinataire(), courrier.sujet(), courrier.corps())));
        } catch (RuntimeException e) {
            Log.errorf(e, "Notification %s could not be delivered; the operation it describes stands",
                    notification.getClass().getSimpleName());
        }
    }
}
