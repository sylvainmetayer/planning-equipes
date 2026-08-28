package dev.sylvain.planning.service.notification;

import java.time.Duration;
import java.time.Instant;

import org.jboss.logging.Logger;

import dev.sylvain.planning.domain.DemandeEchange;
import dev.sylvain.planning.domain.ParametresNotifications;
import dev.sylvain.planning.domain.StatutDemandeEchange;
import dev.sylvain.planning.service.DemandeEchangeService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;

/**
 * Swap requests nobody has decided on (issue #300).
 *
 * <p>{@code DemandeEchange} already carried everything needed — {@code creeLe},
 * {@code cibleDecideLe}, {@code decideLe}, {@code statut} — and nothing read
 * them to raise an alert. What was missing was not data but a rule about
 * <b>silence</b>: an alert that repeats every night is one an organiser learns
 * to delete unread, which is worse than no alert at all.</p>
 *
 * <p>So each demande is alerted on <b>exactly once</b>, and the journal key is
 * its id — not its id plus a date, not a threshold tier. A demande that ages
 * further does not come back; it is already on the Notifications screen, with
 * the date it got there, and the Échanges screen is where it gets closed.</p>
 *
 * <p>The age is counted from the moment the demande landed on the <b>admin's
 * desk</b>, which is not always its creation: a demande waiting for the
 * targeted colleague's agreement is not waiting on the organisation, and
 * alerting about it would send somebody to the Échanges screen to look at a row
 * they cannot act on.</p>
 */
@ApplicationScoped
public class AlerteEchangeJob {

    private static final Logger LOG = Logger.getLogger(AlerteEchangeJob.class);

    @Inject
    DemandeEchangeService demandeEchangeService;

    @Inject
    JournalNotificationsRepository journal;

    @Inject
    Event<Notification> notifications;

    /**
     * Alerts about the requests that have just crossed the configured age.
     *
     * @return how many demandes were newly alerted about — the mail itself is
     *         one, batching them, so this is not a count of messages
     */
    public int run(ParametresNotifications parametres, Instant maintenant) {
        Duration seuil = Duration.ofDays(parametres.ancienneteEchangeJours());
        int nouvelles = 0;
        long joursMax = 0;
        for (DemandeEchange demande : demandeEchangeService.pendingDemandes()) {
            Instant depuis = waitingSince(demande);
            if (depuis == null) {
                continue;
            }
            Duration attente = Duration.between(depuis, maintenant);
            if (attente.compareTo(seuil) < 0) {
                continue;
            }
            long jours = attente.toDays();
            // The id alone: a second run finds the row and stays quiet, however
            // much older the demande has become in the meantime.
            if (!journal.claim(JournalNotificationsRepository.Type.ALERTE_ECHANGE, demande.getId(), null,
                    "Une demande d'échange attend une décision depuis " + jours
                            + (jours > 1 ? " jours." : " jour.")
                            + " À trancher depuis l'écran Échanges.",
                    JournalNotificationsRepository.Severite.WARNING)) {
                continue;
            }
            nouvelles++;
            joursMax = Math.max(joursMax, jours);
        }
        if (nouvelles > 0) {
            // One mail for the batch, not one per demande: an organiser opening
            // their mailbox to eleven identical messages reads none of them.
            notifications.fire(new Notification.PendingEchanges(nouvelles, joursMax));
            LOG.debugf("Stale swap requests: %d newly alerted", nouvelles);
        }
        return nouvelles;
    }

    /**
     * When this demande started waiting on the <b>organisation</b>, or
     * {@code null} while it is not waiting on them at all.
     *
     * <p>{@code EN_ATTENTE_CIBLE} is deliberately excluded: the colleague has
     * not answered yet, the admin has nothing to decide, and there is no button
     * for them on that row. Chasing the colleague is a different feature, and
     * one nobody asked for.</p>
     */
    private static Instant waitingSince(DemandeEchange demande) {
        if (demande.getStatut() != StatutDemandeEchange.PROPOSEE) {
            return null;
        }
        // The colleague's agreement is what put it on the desk; before issue
        // #165's two-step flow existed the demande went straight there, hence
        // the fallback on its creation.
        return demande.getCibleDecideLe() != null ? demande.getCibleDecideLe() : demande.getCreeLe();
    }
}
