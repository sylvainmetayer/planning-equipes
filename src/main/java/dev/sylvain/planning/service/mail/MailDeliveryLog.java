package dev.sylvain.planning.service.mail;

import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

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
 *
 * <p>It is also the one place that says which addresses no mail may leave
 * for ({@link #blockedAddresses}): every send to an animateur reads it rather
 * than re-deriving the rule, and a send it skips writes <b>nothing</b> here —
 * nothing was attempted, and the last line stays the refusal.</p>
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
     * The last mail to each animateur of the edition since their address last
     * changed. An empty map when the journal cannot be read: the screens and
     * the sends then behave as before it existed, rather than failing.
     */
    public Map<String, LastDelivery> latestByAnimateur() {
        try {
            return repository.latestByAnimateur();
        } catch (RuntimeException e) {
            Log.errorf(e, "Could not read the mail delivery journal");
            return Map.of();
        }
    }

    /**
     * The animateurs of the edition no mail may leave for: the relay refused
     * their address for good on the last send ({@link LastDelivery#blocksAddress}),
     * and the address has not changed since. Empty when the journal cannot be
     * read — a blocked address then earns one more refusal, which is better
     * than no mail leaving for anybody.
     */
    public Set<String> blockedAddresses() {
        return latestByAnimateur().entrySet().stream()
                .filter(entree -> entree.getValue().blocksAddress())
                .map(Map.Entry::getKey)
                .collect(Collectors.toUnmodifiableSet());
    }

    /** Whether no mail may leave for this animateur: see {@link #blockedAddresses}. */
    public boolean isAddressBlocked(String animateurId) {
        return animateurId != null && blockedAddresses().contains(animateurId);
    }

    /**
     * Runs {@code envoi} — a notification fired to the best-effort dispatcher,
     * which swallows its own failures — and tells whether a {@code kind} mail
     * to this animateur actually left during it, as the journal recorded it.
     *
     * <p>For a caller that must move a state only once the mail has left, and
     * cannot see the send's exception. {@code false} when the journal cannot
     * be read: the state then stays where it was, the safe side for a
     * reminder that can still be sent by hand.</p>
     */
    public boolean sentDuring(String animateurId, MailKind kind, Runnable envoi) {
        long avant;
        try {
            avant = repository.lastId(animateurId);
        } catch (RuntimeException e) {
            Log.errorf(e, "Could not read the mail delivery journal of animateur %s", animateurId);
            envoi.run();
            return false;
        }
        envoi.run();
        try {
            return repository.sentAfter(animateurId, kind, avant);
        } catch (RuntimeException e) {
            Log.errorf(e, "Could not read whether the %s mail to animateur %s left", kind, animateurId);
            return false;
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
