package dev.sylvain.planning.service.notification;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.ParametresNotifications;
import dev.sylvain.planning.service.espace.ApplicationLinks;
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

    @Inject
    public RelanceConfirmationJob(
            ConfirmationPlanningService confirmationService,
            PlanPublieService planPublieService,
            ReferenceDataService referenceDataService,
            ApplicationLinks liens,
            JournalNotificationsRepository journal,
            Event<Notification> notifications) {
        this.confirmationService = confirmationService;
        this.planPublieService = planPublieService;
        this.referenceDataService = referenceDataService;
        this.liens = liens;
        this.journal = journal;
        this.notifications = notifications;
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

        int envoyes = 0;
        for (String animateurId : silencieux) {
            Animateur fiche = fiches.get(animateurId);
            if (fiche == null) {
                continue;
            }
            envoyes += remind(fiche, publication.publieLe(), maintenant) ? 1 : 0;
        }
        LOG.debugf("Confirmation reminders: %d sent", envoyes);
        return envoyes;
    }

    private boolean remind(Animateur fiche, Instant publieLe, Instant maintenant) {
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
        if (!journal.claim(JournalNotificationsRepository.Type.RELANCE_CONFIRMATION, cle, fiche.getId())) {
            return false;
        }
        // The status is moved BEFORE the mail is fired, and that order is the
        // safe one: the notification is best-effort and swallows its own
        // failures, so a status written afterwards could be skipped by an
        // exception the dispatcher never lets through — and the next run would
        // write to the same person again.
        confirmationService.recordReminder(fiche.getId(), maintenant);
        notifications.fire(new Notification.RelanceConfirmation(
                fiche.getEmail(),
                fiche.getPrenom(),
                liens.espaceAnimateur(fiche.getAccessToken()).orElse(null)));
        return true;
    }
}
