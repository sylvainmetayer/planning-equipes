package dev.sylvain.planning.service.publication;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.StatutConfirmation;
import dev.sylvain.planning.service.BusinessError;
import dev.sylvain.planning.service.espace.ApplicationLinks;
import dev.sylvain.planning.service.mail.MailDeliveryLog;
import dev.sylvain.planning.service.mail.MailKind;
import dev.sylvain.planning.service.notification.JournalNotificationsRepository;
import dev.sylvain.planning.service.referentiel.ReferenceDataService;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * « Relancer maintenant » (issue #504): the organiser's own hand on the
 * reminder the night sends by itself.
 *
 * <p>The automatic reminder of issue #299 leaves once, at night, after the
 * configured delay. The day before the event that is one night too late, and
 * the organiser needs a gesture: pick the silent people on the Animateurs
 * page and write to them now. Same message as the night — one wording, held
 * by {@link RelanceConfirmationMail} — but sent through {@link MailService},
 * because a click is an explicit action whose failure must be reported by id
 * rather than swallowed.</p>
 *
 * <p><b>One reminder per person and per publication, whichever hand sends
 * it.</b> The night and this service claim the very same key in
 * {@link JournalNotificationsRepository} — {@code animateurId|publieLe} —
 * so a person reminded by hand is not reminded again by the following night,
 * and a person the night already wrote to is refused here. The database
 * arbitrates the race, and the status closes the door the key cannot: {@code
 * publieLe} is the date of the edition's <b>last</b> publication, so it moves
 * for everybody at every republication, including people nobody wrote to again
 * — only those the republication actually moved go back to {@code NON_VU}, and
 * a fresh key would otherwise buy a second reminder for a planning that was
 * never re-sent. Someone already at {@code RELANCE} is therefore refused here
 * too, which is exactly what the night does by writing only to the people it
 * has no row for.</p>
 *
 * <p>The claim is taken <b>before</b> the mail leaves — that is what makes two
 * concurrent hands impossible — but the status moves only <b>after</b> it has
 * left, and a failed send gives the key back. Otherwise a momentary SMTP outage
 * cost the thirty people selected on the eve of the event their reminder for
 * good: the hand refused them as already reminded, the night skipped them as
 * already recorded, and the summary counted them as reminded while nothing had
 * reached them. A failure now leaves an alert on the Notifications screen,
 * which outlives the nine seconds of a bubble.</p>
 */
@ApplicationScoped
public class RelanceManuelleService {

    private final PlanPublieService planPublieService;

    private final ConfirmationPlanningService confirmationService;

    private final ReferenceDataService referenceDataService;

    private final JournalNotificationsRepository journal;

    private final ApplicationLinks liens;

    private final MailService mailService;

    private final MailDeliveryLog deliveries;

    @Inject
    public RelanceManuelleService(
            PlanPublieService planPublieService,
            ConfirmationPlanningService confirmationService,
            ReferenceDataService referenceDataService,
            JournalNotificationsRepository journal,
            ApplicationLinks liens,
            MailService mailService,
            MailDeliveryLog deliveries) {
        this.planPublieService = planPublieService;
        this.confirmationService = confirmationService;
        this.referenceDataService = referenceDataService;
        this.journal = journal;
        this.liens = liens;
        this.mailService = mailService;
        this.deliveries = deliveries;
    }

    /**
     * Who was written to, and who was not and why — ids only in every list,
     * so the report can travel over MCP unchanged.
     *
     * @param envoyes                          the reminder left
     * @param dejaConfirmes                    already answered: nothing to chase
     * @param sansEmail                        no address on the fiche
     * @param dejaRelancesPourCettePublication already reminded about this
     *                                         planning, by the night or by hand
     *                                         — the rule « personne ne reçoit
     *                                         deux fois le même message »
     * @param echecs                           the send itself failed: the claim
     *                                         is given back and an alert is
     *                                         left, so a retry is possible
     * @param sansPoste                        no seat in the published plan:
     *                                         nothing was ever asked of them
     * @param adresseRefusee                   the relay refused this address
     *                                         for good on the last send, and
     *                                         the address has not changed
     *                                         since: writing again would earn
     *                                         the same refusal. Changing the
     *                                         address lifts it
     */
    @Schema(
            requiredProperties = {
                "envoyes",
                "dejaConfirmes",
                "sansEmail",
                "dejaRelancesPourCettePublication",
                "echecs",
                "sansPoste",
                "adresseRefusee"
            })
    public record RapportRelance(
            List<String> envoyes,
            List<String> dejaConfirmes,
            List<String> sansEmail,
            List<String> dejaRelancesPourCettePublication,
            List<String> echecs,
            List<String> sansPoste,
            List<String> adresseRefusee) {}

    /**
     * Reminds the given animateurs now.
     *
     * @throws BusinessError.Invalid when nothing was ever published — there is
     *         no planning to confirm —, when the list is empty, or when an id
     *         names nobody
     */
    public RapportRelance relancer(List<String> animateurIds) {
        if (planPublieService.jamaisPublie()) {
            throw new BusinessError.Invalid("Le planning n'a pas encore été publié : il n'y a personne à relancer.");
        }
        if (animateurIds == null || animateurIds.isEmpty()) {
            throw new BusinessError.Invalid("Aucun animateur à relancer.");
        }
        Instant publieLe = planPublieService.lastPublication().publieLe();
        Map<String, Animateur> fiches = new LinkedHashMap<>();
        for (Animateur animateur : referenceDataService.listAnimateurs()) {
            fiches.put(animateur.getId(), animateur);
        }
        Map<String, ConfirmationPlanningService.ConfirmationView> reponses = new LinkedHashMap<>();
        for (ConfirmationPlanningService.ConfirmationView vue : confirmationService.byAnimateur()) {
            reponses.put(vue.animateurId(), vue);
        }

        LinkedHashSet<String> retenus = new LinkedHashSet<>(animateurIds);
        // Refused as a whole before anything leaves: an unknown id found at the
        // third row must not leave the first two reminded and the rest not.
        for (String animateurId : retenus) {
            if (!fiches.containsKey(animateurId) || !reponses.containsKey(animateurId)) {
                throw new BusinessError.Invalid("Animateur inconnu : " + animateurId);
            }
        }

        Tri tri = new Tri();
        Set<String> bloquees = deliveries.blockedAddresses();
        Instant maintenant = Instant.now();
        for (String animateurId : retenus) {
            remindOne(
                    animateurId,
                    fiches.get(animateurId),
                    reponses.get(animateurId),
                    bloquees.contains(animateurId),
                    animateurId + "|" + publieLe,
                    maintenant,
                    tri);
        }
        return tri.rapport();
    }

    /** The seven lists the report is made of, filled one animateur at a time. */
    private record Tri(
            List<String> envoyes,
            List<String> dejaConfirmes,
            List<String> sansEmail,
            List<String> dejaRelances,
            List<String> echecs,
            List<String> sansPoste,
            List<String> adresseRefusee) {

        Tri() {
            this(
                    new ArrayList<>(),
                    new ArrayList<>(),
                    new ArrayList<>(),
                    new ArrayList<>(),
                    new ArrayList<>(),
                    new ArrayList<>(),
                    new ArrayList<>());
        }

        RapportRelance rapport() {
            return new RapportRelance(
                    List.copyOf(envoyes),
                    List.copyOf(dejaConfirmes),
                    List.copyOf(sansEmail),
                    List.copyOf(dejaRelances),
                    List.copyOf(echecs),
                    List.copyOf(sansPoste),
                    List.copyOf(adresseRefusee));
        }
    }

    /**
     * Reminds one animateur, or says in {@code tri} why not.
     *
     * @param adresseBloquee the relay refused this person's address for good
     *                on the last send ({@code MailDeliveryLog.blockedAddresses})
     * @param cle the key both the manual and the nightly reminder claim for
     *            this person and this publication
     */
    private void remindOne(
            String animateurId,
            Animateur fiche,
            ConfirmationPlanningService.ConfirmationView reponse,
            boolean adresseBloquee,
            String cle,
            Instant maintenant,
            Tri tri) {
        if (!reponse.affecte()) {
            tri.sansPoste().add(animateurId);
            return;
        }
        if (StatutConfirmation.CONFIRME.name().equals(reponse.statut())) {
            tri.dejaConfirmes().add(animateurId);
            return;
        }
        if (fiche.getEmail() == null || fiche.getEmail().isBlank()) {
            tri.sansEmail().add(animateurId);
            // Same trace as the nightly job leaves for the same case: a
            // notification bubble lasts nine seconds, the eve of the event
            // does not.
            journal.claim(
                    JournalNotificationsRepository.Type.RELANCE_INJOIGNABLE,
                    cle,
                    animateurId,
                    "Relance impossible : aucune adresse e-mail sur la fiche.",
                    JournalNotificationsRepository.Severite.WARNING);
            deliveries.recordNoAddress(animateurId, MailKind.RELANCE_MANUELLE);
            return;
        }
        // Insisting on an address the relay refused for good would only
        // earn the same refusal; changing the address is what lifts this.
        if (adresseBloquee) {
            tri.adresseRefusee().add(animateurId);
            return;
        }
        // Already reminded for this planning, whichever hand did it: the
        // status is what the night reads too, and a republication that
        // moves somebody is what puts them back to NON_VU.
        // The same key the nightly job claims: whoever wins the insert is
        // the one who writes, and the other hand is refused.
        if (StatutConfirmation.RELANCE.name().equals(reponse.statut())
                || !journal.claim(JournalNotificationsRepository.Type.RELANCE_CONFIRMATION, cle, animateurId)) {
            tri.dejaRelances().add(animateurId);
            return;
        }
        try {
            deliveries.send(
                    animateurId,
                    MailKind.RELANCE_MANUELLE,
                    () -> mailService.sendRelanceConfirmation(
                            fiche.getEmail(),
                            fiche.getPrenom(),
                            liens.espaceAnimateur(fiche.getAccessToken()).orElse(null)));
            // Recorded only once the mail has left: the status is what the
            // screen, the « silent since N days » filter and the summary all
            // read, and moving it for a send that failed would count a
            // reminder nobody received.
            confirmationService.recordReminder(animateurId, maintenant);
            tri.envoyes().add(animateurId);
        } catch (RuntimeException e) {
            Log.errorf(e, "Failed to mail the confirmation reminder to animateur %s", animateurId);
            // The reservation goes back, so a retry is possible at all —
            // holding it would refuse the hand and the night alike — and the
            // failure is left on the Notifications screen rather than in a
            // bubble that disappears.
            journal.release(JournalNotificationsRepository.Type.RELANCE_CONFIRMATION, cle);
            journal.claim(
                    JournalNotificationsRepository.Type.RELANCE_INJOIGNABLE,
                    cle,
                    animateurId,
                    "Relance non partie : l'envoi du courriel a échoué.",
                    JournalNotificationsRepository.Severite.ALERTE);
            tri.echecs().add(animateurId);
        }
    }
}
