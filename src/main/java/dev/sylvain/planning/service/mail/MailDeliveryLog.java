package dev.sylvain.planning.service.mail;

import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Map;

/**
 * Records what became of every mail to an animateur, <b>beside</b> the send
 * and without changing its failure policy.
 *
 * <p>The application has two opposite policies on a failed mail: the ones an
 * admin asks for propagate ({@code MailService}), the best-effort ones are
 * swallowed ({@code NotificationDispatcher}). This class takes no side.
 * {@link #send} rethrows exactly what the send threw, after writing it down,
 * and every method swallows its <b>own</b> failure: a journal that cannot be
 * written must never turn a mail that left into an error, nor a failed mail
 * into a different one.</p>
 */
@ApplicationScoped
public class MailDeliveryLog {

    private final MailDeliveryRepository repository;

    @Inject
    public MailDeliveryLog(MailDeliveryRepository repository) {
        this.repository = repository;
    }

    /**
     * Runs {@code envoi} and records its outcome; a failure is recorded with
     * its category and then rethrown unchanged.
     */
    public void send(String animateurId, MailKind kind, Runnable envoi) {
        try {
            envoi.run();
        } catch (RuntimeException e) {
            recordFailure(animateurId, kind, e);
            throw e;
        }
        recordSent(animateurId, kind);
    }

    /** The relay accepted the mail. */
    public void recordSent(String animateurId, MailKind kind) {
        record(animateurId, kind, MailOutcome.ENVOYE, null);
    }

    /** Nothing could be attempted: the fiche carries no address. */
    public void recordNoAddress(String animateurId, MailKind kind) {
        record(animateurId, kind, MailOutcome.SANS_EMAIL, null);
    }

    /** The send threw; the category is read from what it threw. */
    public void recordFailure(String animateurId, MailKind kind, Throwable failure) {
        record(animateurId, kind, MailOutcome.ECHEC, MailFailure.classify(failure));
    }

    /**
     * The last mail to each animateur of the edition since their fiche last
     * changed. An empty map when the journal cannot be read: the screens and
     * the reminders then behave as before it existed, rather than failing.
     */
    public Map<String, LastDelivery> latestByAnimateur() {
        try {
            return repository.latestByAnimateur();
        } catch (RuntimeException e) {
            Log.errorf(e, "Could not read the mail delivery journal");
            return Map.of();
        }
    }

    private void record(String animateurId, MailKind kind, MailOutcome outcome, MailFailure failure) {
        if (animateurId == null) {
            return;
        }
        try {
            repository.record(animateurId, kind, outcome, failure);
        } catch (RuntimeException e) {
            Log.errorf(e, "Could not record the outcome (%s) of a %s mail to animateur %s", outcome, kind, animateurId);
        }
    }
}
