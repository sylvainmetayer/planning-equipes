package dev.sylvain.planning.service.publication;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.StatutConfirmation;
import dev.sylvain.planning.service.BusinessError;
import dev.sylvain.planning.service.espace.ApplicationLinks;
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
 * arbitrates, not a status read before acting. A republication that moves
 * somebody's schedule changes {@code publieLe}, and with it the key: a fresh
 * reminder is then legitimate, as it is for the night.</p>
 *
 * <p>The claim is taken and the status moved <b>before</b> the mail leaves,
 * in the order the nightly job uses. A send that then fails is counted in
 * {@code echecs} and named to the organiser, whose way back is the individual
 * resend of the planning — which carries the same espace link — rather than a
 * second reminder that the rule above would refuse.</p>
 */
@ApplicationScoped
public class RelanceManuelleService {

    @Inject
    PlanPublieService planPublieService;

    @Inject
    ConfirmationPlanningService confirmationService;

    @Inject
    ReferenceDataService referenceDataService;

    @Inject
    JournalNotificationsRepository journal;

    @Inject
    ApplicationLinks liens;

    @Inject
    MailService mailService;

    /**
     * Who was written to, and who was not and why — ids only in every list,
     * so the report can travel over MCP unchanged.
     *
     * @param envoyes                          the reminder left
     * @param dejaConfirmes                    already answered: nothing to chase
     * @param sansEmail                        no address on the fiche
     * @param dejaRelancesPourCettePublication already reminded about this very
     *                                         publication, by the night or by
     *                                         hand — the rule « personne ne
     *                                         reçoit deux fois le même message »
     * @param echecs                           the send itself failed; the claim
     *                                         stands, see the class javadoc
     * @param sansPoste                        no seat in the published plan:
     *                                         nothing was ever asked of them
     */
    @Schema(
            requiredProperties = {
                "envoyes",
                "dejaConfirmes",
                "sansEmail",
                "dejaRelancesPourCettePublication",
                "echecs",
                "sansPoste"
            })
    public record RapportRelance(
            List<String> envoyes,
            List<String> dejaConfirmes,
            List<String> sansEmail,
            List<String> dejaRelancesPourCettePublication,
            List<String> echecs,
            List<String> sansPoste) {}

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

        List<String> envoyes = new ArrayList<>();
        List<String> dejaConfirmes = new ArrayList<>();
        List<String> sansEmail = new ArrayList<>();
        List<String> dejaRelances = new ArrayList<>();
        List<String> echecs = new ArrayList<>();
        List<String> sansPoste = new ArrayList<>();
        Instant maintenant = Instant.now();
        for (String animateurId : retenus) {
            Animateur fiche = fiches.get(animateurId);
            ConfirmationPlanningService.ConfirmationView reponse = reponses.get(animateurId);
            if (!reponse.affecte()) {
                sansPoste.add(animateurId);
                continue;
            }
            if (StatutConfirmation.CONFIRME.name().equals(reponse.statut())) {
                dejaConfirmes.add(animateurId);
                continue;
            }
            if (fiche.getEmail() == null || fiche.getEmail().isBlank()) {
                sansEmail.add(animateurId);
                continue;
            }
            // The same key the nightly job claims: whoever wins the insert is
            // the one who writes, and the other hand is refused.
            String cle = animateurId + "|" + publieLe;
            if (!journal.claim(JournalNotificationsRepository.Type.RELANCE_CONFIRMATION, cle, animateurId)) {
                dejaRelances.add(animateurId);
                continue;
            }
            confirmationService.recordReminder(animateurId, maintenant);
            try {
                mailService.sendRelanceConfirmation(
                        fiche.getEmail(),
                        fiche.getPrenom(),
                        liens.espaceAnimateur(fiche.getAccessToken()).orElse(null));
                envoyes.add(animateurId);
            } catch (RuntimeException e) {
                Log.errorf(e, "Failed to mail the confirmation reminder to animateur %s", animateurId);
                echecs.add(animateurId);
            }
        }
        return new RapportRelance(
                List.copyOf(envoyes),
                List.copyOf(dejaConfirmes),
                List.copyOf(sansEmail),
                List.copyOf(dejaRelances),
                List.copyOf(echecs),
                List.copyOf(sansPoste));
    }
}
