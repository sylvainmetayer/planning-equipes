package dev.sylvain.planning.service.notification;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.ParametresNotifications;
import dev.sylvain.planning.service.espace.ApplicationLinks;
import dev.sylvain.planning.service.mail.MailDelivery;
import dev.sylvain.planning.service.mail.MailDeliveryOutcome;
import dev.sylvain.planning.service.mail.MailDeliveryRepository;
import dev.sylvain.planning.service.mail.MailFailureCategory;
import dev.sylvain.planning.service.publication.ConfirmationPlanningService;
import dev.sylvain.planning.service.publication.PlanPublieService;
import dev.sylvain.planning.service.referentiel.ReferenceDataService;
import dev.sylvain.planning.service.solve.PlanSnapshotService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
 * <p>The delay is counted from the <b>publication</b>, not from the person: it
 * answers « cela fait trois jours que le planning est parti et je n'ai pas de
 * réponse », which is the question an organiser actually asks.</p>
 *
 * <p><b>A reminder that did not leave is not a reminder.</b> The status moves
 * to {@code RELANCE} only once the relay has taken the mail; a failed send
 * leaves the person at {@code NON_VU} — shown as « échec d'envoi » on the
 * Animateurs page — and an {@code ALERTE} on the home screen, exactly as the
 * manual reminder does. The key shared with the hand goes back, so that
 * « Relancer maintenant » finds it free; the night keeps a key of its own, one
 * attempt per person and per publication, because a relay that is down at
 * 19 h is likely down at 20 h and writing to it every hour of the night would
 * change nothing. The organiser, warned, retries by hand.</p>
 *
 * <p>Nor does it write to an address the relay has already refused for good
 * ({@code ADRESSE_REFUSEE}) while the fiche has not been edited since: the
 * same mail would meet the same refusal. The person is left out, an alert says
 * why, and the first edit of the fiche puts them back in the next run.</p>
 */
@ApplicationScoped
public class RelanceConfirmationJob {

    private static final Logger LOG = Logger.getLogger(RelanceConfirmationJob.class);

    private final ConfirmationPlanningService confirmationService;

    private final PlanPublieService planPublieService;

    private final ReferenceDataService referenceDataService;

    private final ApplicationLinks liens;

    private final JournalNotificationsRepository journal;

    private final NotificationDispatcher dispatcher;

    private final MailDeliveryRepository deliveries;

    @Inject
    public RelanceConfirmationJob(
            ConfirmationPlanningService confirmationService,
            PlanPublieService planPublieService,
            ReferenceDataService referenceDataService,
            ApplicationLinks liens,
            JournalNotificationsRepository journal,
            NotificationDispatcher dispatcher,
            MailDeliveryRepository deliveries) {
        this.confirmationService = confirmationService;
        this.planPublieService = planPublieService;
        this.referenceDataService = referenceDataService;
        this.liens = liens;
        this.journal = journal;
        this.dispatcher = dispatcher;
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

        Map<String, MailDelivery> derniersEnvois = deliveries.latestByAnimateur();
        int envoyes = 0;
        for (String animateurId : silencieux) {
            Animateur fiche = fiches.get(animateurId);
            if (fiche == null) {
                continue;
            }
            envoyes += remind(fiche, derniersEnvois.get(animateurId), publication.publieLe(), maintenant) ? 1 : 0;
        }
        LOG.debugf("Confirmation reminders: %d sent", envoyes);
        return envoyes;
    }

    private boolean remind(Animateur fiche, MailDelivery dernierEnvoi, Instant publieLe, Instant maintenant) {
        // Keyed on the publication, so a later one — which only resets the
        // people whose planning really moved — may legitimately remind again.
        String cle = fiche.getId() + "|" + publieLe;
        if (fiche.getEmail() == null || fiche.getEmail().isBlank()) {
            journal.claim(
                    JournalNotificationsRepository.Type.RELANCE_INJOIGNABLE,
                    cle,
                    fiche.getId(),
                    "Relance de confirmation impossible : aucune adresse e-mail sur la fiche."
                            + " Cette personne n'a pas accusé réception de son planning.",
                    JournalNotificationsRepository.Severite.WARNING);
            return false;
        }
        if (dernierEnvoi != null && dernierEnvoi.refusedAddressStands(fiche.getModifieLe())) {
            // Not claimed: the first edit of the fiche must put the person
            // back in the next run — so the night's own attempt, should an
            // earlier failure hold it, goes back too. The alert has a key of
            // its own, so that it is raised once per publication and never
            // takes the place of the failure alert of a later attempt.
            journal.release(
                    JournalNotificationsRepository.Type.RELANCE_CONFIRMATION,
                    cle + JournalNotificationsRepository.NIGHT_ATTEMPT_SUFFIX);
            journal.claim(
                    JournalNotificationsRepository.Type.RELANCE_INJOIGNABLE,
                    cle + "|adresse-refusee",
                    fiche.getId(),
                    "Relance non tentée : l'adresse de la fiche a été refusée par le serveur de messagerie au"
                            + " dernier envoi. Corrigez-la : la relance repartira au passage suivant des envois"
                            + " automatiques.",
                    JournalNotificationsRepository.Severite.WARNING);
            return false;
        }
        // The night's one attempt for this person and this publication,
        // claimed first: it is what stops the night from writing every hour
        // to a relay that is down, without holding the shared key below.
        String tentative = cle + JournalNotificationsRepository.NIGHT_ATTEMPT_SUFFIX;
        if (!journal.claim(JournalNotificationsRepository.Type.RELANCE_CONFIRMATION, tentative, fiche.getId())) {
            return false;
        }
        // The key the hand claims too: whoever wins the insert writes.
        if (!journal.claim(JournalNotificationsRepository.Type.RELANCE_CONFIRMATION, cle, fiche.getId())) {
            // The organiser's hand holds it — sent, or sending right now. The
            // night attempted nothing, so its own key goes back.
            journal.release(JournalNotificationsRepository.Type.RELANCE_CONFIRMATION, tentative);
            return false;
        }
        Optional<MailDeliveryOutcome> issue = dispatcher.deliver(new Notification.RelanceConfirmation(
                fiche.getId(),
                fiche.getEmail(),
                fiche.getPrenom(),
                liens.espaceAnimateur(fiche.getAccessToken()).orElse(null)));
        if (issue.isEmpty()) {
            // Nothing was attempted: nothing to hold.
            journal.release(JournalNotificationsRepository.Type.RELANCE_CONFIRMATION, cle);
            journal.release(JournalNotificationsRepository.Type.RELANCE_CONFIRMATION, tentative);
            return false;
        }
        if (issue.get().wasSent()) {
            // Moved only once the relay took the mail: the status is what the
            // screen, the « silent since N days » filter and the summary all
            // read, and a reminder nobody received must not count as one.
            // Should this write fail, the claim still stops a second mail.
            confirmationService.recordReminder(fiche.getId(), maintenant);
            return true;
        }
        recordFailure(fiche.getId(), cle, issue.get().category());
        return false;
    }

    /**
     * What a failed send leaves behind. The shared key goes back — the person
     * was not reminded, and « Relancer maintenant » must find it free —, and
     * an {@code ALERTE} is left where the organiser looks, under a key of its
     * own so that an earlier address-less warning cannot swallow it.
     *
     * <p>The night's own attempt is kept, so it does not insist hour after
     * hour, except for a refused address: that one is held back by the
     * refused-address check until the fiche is edited, and the edit must find
     * the night ready to write again.</p>
     */
    private void recordFailure(String animateurId, String cle, MailFailureCategory categorie) {
        journal.release(JournalNotificationsRepository.Type.RELANCE_CONFIRMATION, cle);
        boolean adresseRefusee = categorie == MailFailureCategory.ADRESSE_REFUSEE;
        if (adresseRefusee) {
            journal.release(
                    JournalNotificationsRepository.Type.RELANCE_CONFIRMATION,
                    cle + JournalNotificationsRepository.NIGHT_ATTEMPT_SUFFIX);
        }
        journal.claim(
                JournalNotificationsRepository.Type.RELANCE_INJOIGNABLE,
                cle + JournalNotificationsRepository.FAILURE_SUFFIX,
                animateurId,
                adresseRefusee
                        ? "Relance non partie : le serveur de messagerie a refusé l'adresse de la fiche."
                                + " Corrigez-la : la relance repartira au passage suivant des envois automatiques."
                        : "Relance non partie : l'envoi du courriel a échoué. Les envois automatiques ne"
                                + " réessaient pas : relancez à la main depuis la page Animateurs.",
                JournalNotificationsRepository.Severite.ALERTE);
    }
}
