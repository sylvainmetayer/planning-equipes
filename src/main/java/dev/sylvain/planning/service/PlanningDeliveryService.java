package dev.sylvain.planning.service;

import java.util.List;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.PlanningEvenement;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Sends <b>one</b> animateur their individual planning — their PDF as an
 * attachment, the link to their espace in the body.
 *
 * <p>Everything is read server-side from the persisted planning: the planning
 * (potentially huge) never travels through the browser.</p>
 *
 * <p>Unlike the swap notifications, best-effort by nature, this is an explicit
 * administration action: the report says who was reached, who has no address,
 * and whose delivery failed.</p>
 *
 * <p>Sending to everybody used to live here too. Since issue #245 it is
 * {@link PlanPublicationService} instead, and it is no longer « to everybody »:
 * publishing writes to the people whose own schedule moved. What is left here
 * is the individual resend — the way back when one publication mail bounced,
 * and the only send that does not change what is published.</p>
 */
@ApplicationScoped
public class PlanningDeliveryService {

    @Inject
    PlanPublieService planPublieService;

    @Inject
    PlanningExportService planningExportService;

    @Inject
    MailService mailService;

    /**
     * Outcome of a send: {@code sansEmail} and {@code echecs} carry display
     * names, ready to be shown to the admin as-is.
     */
    public record DeliveryReport(int envoyes, List<String> sansEmail, List<String> echecs) {
    }

    /**
     * Sends one animateur their planning. A send that failed comes back
     * described rather than as a stack trace: {@code echecs} then carries what
     * to tell the operator, and the resource decides which status carries it.
     *
     * <p>Resends the <b>published</b> plan, not the working one: the PDF must
     * say the same thing as their espace and as the mail they already got.
     * Resending a plan nobody announced would create a second version in
     * circulation, which is the whole problem issue #245 removes.</p>
     *
     * @throws BusinessError.NotFound when the id names nobody in the plan
     * @throws BusinessError.Invalid    when their fiche carries no address, or
     *         when nothing has been published yet
     */
    public DeliveryReport sendToOneAnimateur(String animateurId) {
        if (planPublieService.jamaisPublie()) {
            throw new BusinessError.Invalid(
                    "Le planning n'a pas encore été publié : il n'y a rien à renvoyer.");
        }
        PlanningEvenement planning = planPublieService.planPublie();
        Animateur animateur = planning.getAnimateurs().stream()
                .filter(candidat -> candidat.getId().equals(animateurId))
                .findFirst()
                .orElseThrow(() -> new BusinessError.NotFound("Animateur inconnu : " + animateurId));
        if (!hasAddress(animateur)) {
            throw new BusinessError.Invalid(animateur.nomAffiche() + " n'a pas d'adresse e-mail sur sa fiche");
        }
        try {
            send(planning, animateur);
        } catch (RuntimeException e) {
            Log.errorf(e, "Failed to mail the planning of animateur %s", animateur.getId());
            return new DeliveryReport(0, List.of(), List.of("Échec de l'envoi à " + animateur.getEmail()));
        }
        return new DeliveryReport(1, List.of(), List.of());
    }

    private static boolean hasAddress(Animateur animateur) {
        return animateur.getEmail() != null && !animateur.getEmail().isBlank();
    }

    private void send(PlanningEvenement planning, Animateur animateur) {
        byte[] pdf = planningExportService.exportAnimateurPdfPublie(planning, animateur.getId());
        mailService.sendIndividualPlanning(
                animateur.getEmail(),
                animateur.getPrenom(),
                planningExportService.lienEspaceAnimateur(planning, animateur.getId()),
                pdf,
                PlanningExportService.planningFileName(animateur.nomAffiche(), "pdf"));
    }
}
