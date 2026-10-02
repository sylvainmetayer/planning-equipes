package dev.sylvain.planning.service.notification;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.DeclarationDisponibilite;
import dev.sylvain.planning.service.espace.ApplicationLinks;
import dev.sylvain.planning.service.espace.DeclarationDisponibiliteRepository;
import dev.sylvain.planning.service.espace.DeclarationDisponibiliteRepository.FenetreCollecte;
import dev.sylvain.planning.service.mail.MailDelivery;
import dev.sylvain.planning.service.mail.MailDeliveryOutcome;
import dev.sylvain.planning.service.mail.MailDeliveryRepository;
import dev.sylvain.planning.service.mail.MailFailureCategory;
import dev.sylvain.planning.service.publication.MailService;
import dev.sylvain.planning.service.referentiel.ReferenceDataService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.jboss.logging.Logger;

/**
 * « Vos disponibilités sont attendues avant le … »: one reminder, three days
 * before the collection closes, to whoever was invited to declare and has
 * declared nothing.
 *
 * <p><b>Who is written to.</b> The invited are read from {@code envoi_mail}:
 * the invitation is an explicit send of {@code MailService}, recorded there
 * like every mail to an animateur, and it leaves no other trace. Only an
 * invitation that <b>left</b> counts — somebody whose invitation failed was
 * never told there was anything to declare, and a reminder would be the first
 * they hear of it. « Declared nothing » means no declaration at all, whatever
 * its status: an applied or refused one is an answer, and the person knows
 * the way to their espace.</p>
 *
 * <p><b>When.</b> On the first run at or after {@link #SENDING_TIME}, in the
 * notification zone, of a day where the end of the window is zero to three
 * days away. The job is the hourly one of the nightly sends, so with the
 * shipped cron that is the 9 h run; a cron that never fires at or after 9 h
 * sends no reminder at all. A day the job did not run is caught up the next
 * day, down to the last day of the window itself; past it, the collection is
 * closed and nothing leaves.</p>
 *
 * <p><b>Once.</b> Claimed in {@link JournalNotificationsRepository} under
 * {@code animateurId|fin} before the mail is attempted, the way the other
 * nightly sends are: the hourly runs of the three days write to nobody twice,
 * and moving the end of the window is a new deadline that may remind again.
 * Each day also claims its own attempt, {@code animateurId|fin|day}, the way
 * {@link RelanceConfirmationJob} claims the night's: a send that failed gives
 * the deadline's key back and keeps the day's, so a relay down at 9 h is not
 * written to every hour, and the next day tries again — an alert on the home
 * screen says so in between. An address the relay refused is not written to
 * again until the fiche is edited; a fiche without an address is not written
 * to at all. Both are said on the home screen, by id, never by name.</p>
 *
 * <p>Off unless the organisation switched it on with the window; and like
 * every nightly send, only the active edition gets here at all (ADR 0072).</p>
 */
@ApplicationScoped
public class RelanceCollecteJob {

    /** How many days before the end of the window the reminder may leave. */
    static final int DAYS_BEFORE_END = 3;

    /** Not before this hour of the notification zone: a mail at midnight is read by nobody. */
    static final LocalTime SENDING_TIME = LocalTime.of(9, 0);

    /** Appended to the deadline's key for the warning of a fiche without an address. */
    static final String NO_ADDRESS_SUFFIX = "|sans-adresse";

    /** Appended to the deadline's key for the warning of an address the relay refused. */
    static final String REFUSED_ADDRESS_SUFFIX = "|adresse-refusee";

    private static final Logger LOG = Logger.getLogger(RelanceCollecteJob.class);

    private final DeclarationDisponibiliteRepository declarations;

    private final ReferenceDataService referenceDataService;

    private final MailDeliveryRepository deliveries;

    private final ApplicationLinks liens;

    private final JournalNotificationsRepository journal;

    private final NotificationDispatcher dispatcher;

    @Inject
    public RelanceCollecteJob(
            DeclarationDisponibiliteRepository declarations,
            ReferenceDataService referenceDataService,
            MailDeliveryRepository deliveries,
            ApplicationLinks liens,
            JournalNotificationsRepository journal,
            NotificationDispatcher dispatcher) {
        this.declarations = declarations;
        this.referenceDataService = referenceDataService;
        this.deliveries = deliveries;
        this.liens = liens;
        this.journal = journal;
        this.dispatcher = dispatcher;
    }

    /**
     * Reminds the invited who have not answered, if the window ends soon.
     *
     * @param now the run's clock, in the notification zone
     * @return how many reminders actually left
     */
    public int run(ZonedDateTime now) {
        if (now.toLocalTime().isBefore(SENDING_TIME)) {
            return 0;
        }
        LocalDate today = now.toLocalDate();
        FenetreCollecte window = declarations.fenetre();
        if (!due(window, today)) {
            return 0;
        }
        Set<String> invited = deliveries.animateursReached(MailService.AVAILABILITY_INVITATION);
        if (invited.isEmpty()) {
            return 0;
        }
        Set<String> answered = declarations.list().stream()
                .map(DeclarationDisponibilite::getAnimateurId)
                .collect(Collectors.toSet());
        Map<String, MailDelivery> lastDeliveries = deliveries.latestByAnimateur();
        int sent = 0;
        for (Animateur animateur : referenceDataService.listAnimateurs()) {
            if (invited.contains(animateur.getId()) && !answered.contains(animateur.getId())) {
                sent += remind(animateur, lastDeliveries.get(animateur.getId()), window.fin(), today) ? 1 : 0;
            }
        }
        LOG.debugf("Collection reminders: %d sent before %s", sent, window.fin());
        return sent;
    }

    /**
     * Whether the window calls for a reminder today: switched on, open today,
     * and closing within {@link #DAYS_BEFORE_END} days. A window with no end
     * has no deadline to remind of.
     */
    static boolean due(FenetreCollecte window, LocalDate today) {
        if (!window.relanceAutomatique() || window.fin() == null || !window.openOn(today)) {
            return false;
        }
        long daysLeft = ChronoUnit.DAYS.between(today, window.fin());
        return daysLeft >= 0 && daysLeft <= DAYS_BEFORE_END;
    }

    /** @return true when a mail actually left */
    private boolean remind(Animateur animateur, MailDelivery lastDelivery, LocalDate fin, LocalDate today) {
        String id = animateur.getId();
        String key = id + "|" + fin;
        if (animateur.getEmail() == null || animateur.getEmail().isBlank()) {
            // Invited, so the fiche had an address; it lost it since. Said
            // once per deadline, and not claimed: should the fiche get one
            // back before the end, the next run writes.
            journal.claim(
                    JournalNotificationsRepository.Type.RELANCE_COLLECTE,
                    key + NO_ADDRESS_SUFFIX,
                    id,
                    "Relance de la collecte impossible : aucune adresse e-mail sur la fiche. Cette personne n'a"
                            + " pas déclaré ses disponibilités et la collecte se termine le "
                            + NotificationWriter.JOUR.format(fin) + ".",
                    JournalNotificationsRepository.Severite.WARNING);
            return false;
        }
        if (lastDelivery != null && lastDelivery.refusedAddressStands(animateur.getModifieLe())) {
            // Not attempted: the same mail would meet the same refusal. Said
            // once per deadline; the first edit of the fiche puts the person
            // back in the next run.
            journal.claim(
                    JournalNotificationsRepository.Type.RELANCE_COLLECTE,
                    key + REFUSED_ADDRESS_SUFFIX,
                    id,
                    "Relance de la collecte non tentée : l'adresse de la fiche a été refusée par le serveur de"
                            + " messagerie au dernier envoi. Corrigez-la : la relance repartira au passage suivant"
                            + " des envois automatiques, jusqu'au "
                            + NotificationWriter.JOUR.format(fin) + ".",
                    JournalNotificationsRepository.Severite.WARNING);
            return false;
        }
        // The day's one attempt, claimed first: it stops the hourly runs from
        // writing to a relay that is down, without holding the deadline's key
        // — tomorrow's first run tries again.
        String attempt = key + "|" + today;
        if (!journal.claim(JournalNotificationsRepository.Type.RELANCE_COLLECTE, attempt, id)) {
            return false;
        }
        if (!journal.claim(JournalNotificationsRepository.Type.RELANCE_COLLECTE, key, id)) {
            // Already reminded of this deadline.
            return false;
        }
        Optional<MailDeliveryOutcome> outcome = dispatcher.deliver(new Notification.RelanceCollecte(
                id,
                animateur.getEmail(),
                animateur.getPrenom(),
                fin,
                liens.espaceDisponibilites(animateur.getAccessToken()).orElse(null)));
        if (outcome.isEmpty()) {
            // Nothing was attempted: nothing to hold.
            journal.release(JournalNotificationsRepository.Type.RELANCE_COLLECTE, key);
            journal.release(JournalNotificationsRepository.Type.RELANCE_COLLECTE, attempt);
            return false;
        }
        if (!outcome.get().wasSent()) {
            recordFailure(id, key, attempt, fin, outcome.get().category());
            return false;
        }
        return true;
    }

    /**
     * What a failed send leaves behind. The deadline's key goes back — the
     * person was not reminded, and the next day's run must find it free —, and
     * an {@code ALERTE} is left on the home screen, once per deadline.
     *
     * <p>The day's attempt is kept, so the hourly runs do not insist, except
     * for a refused address: that one is held back by the refused-address
     * check until the fiche is edited, and the edit must find the job ready to
     * write again the same day.</p>
     */
    private void recordFailure(
            String animateurId, String key, String attempt, LocalDate fin, MailFailureCategory category) {
        journal.release(JournalNotificationsRepository.Type.RELANCE_COLLECTE, key);
        boolean refusedAddress = category == MailFailureCategory.ADRESSE_REFUSEE;
        if (refusedAddress) {
            journal.release(JournalNotificationsRepository.Type.RELANCE_COLLECTE, attempt);
        }
        String deadline = NotificationWriter.JOUR.format(fin);
        journal.claim(
                JournalNotificationsRepository.Type.RELANCE_COLLECTE,
                key + JournalNotificationsRepository.FAILURE_SUFFIX,
                animateurId,
                refusedAddress
                        ? "Relance de la collecte non partie : le serveur de messagerie a refusé l'adresse de la"
                                + " fiche. Corrigez-la : la relance repartira au passage suivant des envois"
                                + " automatiques, jusqu'au " + deadline + "."
                        : "Relance de la collecte non partie : l'envoi du courriel a échoué. Cette personne n'a pas"
                                + " déclaré ses disponibilités ; les envois automatiques réessaient le lendemain,"
                                + " jusqu'au " + deadline + ", dernier jour de la collecte.",
                JournalNotificationsRepository.Severite.ALERTE);
    }
}
