package dev.sylvain.planning.service.notification;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.ParametresNotifications;
import dev.sylvain.planning.service.espace.ApplicationLinks;
import dev.sylvain.planning.service.mail.MailDeliveryLog;
import dev.sylvain.planning.service.mail.MailKind;
import dev.sylvain.planning.service.publication.ConfirmationPlanningService;
import dev.sylvain.planning.service.publication.PlanPublieService;
import dev.sylvain.planning.service.referentiel.ReferenceDataService;
import dev.sylvain.planning.service.solve.PlanSnapshotService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jboss.logging.Logger;

/**
 * « Confirmez-vous votre planning ? » (issue #299): one reminder to the people
 * who never answered the plan that was published to them.
 *
 * <p>It rests entirely on the status of issue #293, and that is what makes it
 * finite. The people written to are those still at {@code NON_VU} <b>and</b>
 * holding a seat in the published plan; sending moves them to {@code RELANCE},
 * which takes them out of the selection for good. Escalating after several
 * reminders is deliberately another feature — this job cannot write to the same
 * person twice.</p>
 *
 * <p>Two independent guards, and each one alone would be enough on a good day:
 * the status, and a claim in {@link JournalNotificationsRepository} keyed on
 * the publication the reminder is about. The claim is what holds when the
 * status write fails, when two runs overlap, or when a republication resets
 * somebody in between — that reset means their planning changed, so the key
 * changes with it and a fresh reminder is legitimate.</p>
 *
 * <p>The status moves only <b>once the mail has left</b>, as it does for the
 * hand ({@code RelanceManuelleService}). A send that failed leaves the person
 * {@code NON_VU}, gives the shared key back so « Relancer maintenant » is
 * accepted, and leaves an alert — while a key of the night's own
 * ({@code RELANCE_NUIT_TENTEE}) stops the next hourly run from trying the
 * same publication again.</p>
 *
 * <p>The delay is counted from the <b>publication</b>, not from the person: it
 * answers « cela fait trois jours que le planning est parti et je n'ai pas de
 * réponse », which is the question an organiser actually asks.</p>
 */
@ApplicationScoped
public class RelanceConfirmationJob {

    private static final Logger LOG = Logger.getLogger(RelanceConfirmationJob.class);

    private final ConfirmationPlanningService confirmationService;

    private final PlanPublieService planPublieService;

    private final ReferenceDataService referenceDataService;

    private final ApplicationLinks liens;

    private final JournalNotificationsRepository journal;

    private final Event<Notification> notifications;

    private final MailDeliveryLog deliveries;

    @Inject
    public RelanceConfirmationJob(
            ConfirmationPlanningService confirmationService,
            PlanPublieService planPublieService,
            ReferenceDataService referenceDataService,
            ApplicationLinks liens,
            JournalNotificationsRepository journal,
            Event<Notification> notifications,
            MailDeliveryLog deliveries) {
        this.confirmationService = confirmationService;
        this.planPublieService = planPublieService;
        this.referenceDataService = referenceDataService;
        this.liens = liens;
        this.journal = journal;
        this.notifications = notifications;
        this.deliveries = deliveries;
    }

    /**
     * Reminds whoever has been silent for longer than the configured delay.
     *
     * @param maintenant the run's clock, shared with the other jobs
     * @return how many reminders actually left
     */
    public int run(ParametresNotifications parametres, Instant maintenant) {
        PlanSnapshotService.SnapshotMeta publication = planPublieService.lastPublication();
        if (publication == null || publication.publieLe() == null) {
            return 0;
        }
        Duration attente = Duration.between(publication.publieLe(), maintenant);
        if (attente.toHours() < parametres.delaiRelanceHeures()) {
            return 0;
        }

        List<String> silencieux = confirmationService.unconfirmed();
        if (silencieux.isEmpty()) {
            return 0;
        }

        Map<String, Animateur> fiches = new LinkedHashMap<>();
        for (Animateur animateur : referenceDataService.listAnimateurs()) {
            fiches.put(animateur.getId(), animateur);
        }

        Set<String> bloquees = deliveries.blockedAddresses();
        int envoyes = 0;
        for (String animateurId : silencieux) {
            Animateur fiche = fiches.get(animateurId);
            if (fiche == null) {
                continue;
            }
            envoyes += remind(fiche, bloquees.contains(animateurId), publication.publieLe(), maintenant) ? 1 : 0;
        }
        LOG.debugf("Confirmation reminders: %d sent", envoyes);
        return envoyes;
    }

    private boolean remind(Animateur fiche, boolean adresseBloquee, Instant publieLe, Instant maintenant) {
        // Keyed on the publication, so a later one — which only resets the
        // people whose planning really moved — may legitimately remind again.
        String cle = fiche.getId() + "|" + publieLe;
        if (fiche.getEmail() == null || fiche.getEmail().isBlank()) {
            if (journal.claim(
                    JournalNotificationsRepository.Type.RELANCE_INJOIGNABLE,
                    cle,
                    fiche.getId(),
                    "Relance de confirmation impossible : aucune adresse e-mail sur la fiche."
                            + " Cette personne n'a pas accusé réception de son planning.",
                    JournalNotificationsRepository.Severite.WARNING)) {
                deliveries.recordNoAddress(fiche.getId(), MailKind.RELANCE_NUIT);
            }
            return false;
        }
        // The relay refused this address for good on the last send, and the
        // address has not changed since: the same mail would earn the same
        // refusal. Nothing is claimed, so the reminder leaves on the first
        // night after somebody corrects the address; the alert is left once.
        if (adresseBloquee) {
            journal.claim(
                    JournalNotificationsRepository.Type.RELANCE_INJOIGNABLE,
                    cle,
                    fiche.getId(),
                    "Relance de confirmation non envoyée : le relais a refusé l'adresse de la fiche au dernier"
                            + " envoi. Corrigez l'adresse pour rétablir la relance.",
                    JournalNotificationsRepository.Severite.WARNING);
            return false;
        }
        // The night's own attempt, whatever its outcome: the one thing that
        // stops the next hourly run from trying this publication again.
        if (!journal.claim(JournalNotificationsRepository.Type.RELANCE_NUIT_TENTEE, cle, fiche.getId())) {
            return false;
        }
        // The key the hand claims too: whoever wins the insert writes.
        if (!journal.claim(JournalNotificationsRepository.Type.RELANCE_CONFIRMATION, cle, fiche.getId())) {
            return false;
        }
        // The dispatcher is synchronous and swallows its own failures, so
        // whether the mail left is read back from the delivery journal it
        // wrote, rather than from an exception that never comes through.
        boolean parti = deliveries.sentDuring(
                fiche.getId(),
                MailKind.RELANCE_NUIT,
                () -> notifications.fire(new Notification.RelanceConfirmation(
                        fiche.getId(),
                        fiche.getEmail(),
                        fiche.getPrenom(),
                        liens.espaceAnimateur(fiche.getAccessToken()).orElse(null))));
        if (!parti) {
            // Nothing reached them: the status stays NON_VU, and the shared
            // key goes back so the hand is not refused as « déjà relancée ».
            // RELANCE_NUIT_TENTEE stays, so the night does not insist.
            journal.release(JournalNotificationsRepository.Type.RELANCE_CONFIRMATION, cle);
            journal.claim(
                    JournalNotificationsRepository.Type.RELANCE_INJOIGNABLE,
                    cle,
                    fiche.getId(),
                    "Relance de nuit non partie : l'envoi du courriel a échoué."
                            + " « Relancer maintenant » reste possible.",
                    JournalNotificationsRepository.Severite.ALERTE);
            return false;
        }
        confirmationService.recordReminder(fiche.getId(), maintenant);
        return true;
    }
}
