package dev.sylvain.planning.service.publication;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.service.BusinessError;
import dev.sylvain.planning.service.export.PlanningExportService;
import dev.sylvain.planning.service.mail.MailDeliveryLog;
import dev.sylvain.planning.service.mail.MailKind;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;

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

    /**
     * Names nobody, on purpose: it travels to whoever asked, MCP included, and
     * the fiche carrying the button already says whose address it is.
     */
    static final String ADRESSE_REFUSEE = "Le relais a refusé l'adresse e-mail de cette fiche au dernier envoi : "
            + "corrigez l'adresse avant de renvoyer.";

    private final PlanPublieService planPublieService;

    private final PlanningExportService planningExportService;

    private final MailService mailService;

    private final MailDeliveryLog deliveries;

    @Inject
    public PlanningDeliveryService(
            PlanPublieService planPublieService,
            PlanningExportService planningExportService,
            MailService mailService,
            MailDeliveryLog deliveries) {
        this.planPublieService = planPublieService;
        this.planningExportService = planningExportService;
        this.mailService = mailService;
        this.deliveries = deliveries;
    }

    /**
     * Outcome of a send: {@code sansEmail} and {@code echecs} carry display
     * names, ready to be shown to the admin as-is.
     */
    public record DeliveryReport(int envoyes, List<String> sansEmail, List<String> echecs) {}

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
     * @throws BusinessError.Conflict   when the relay refused their address for
     *         good on the last send and it has not changed since: resending
     *         would only earn the same refusal
     */
    public DeliveryReport sendToOneAnimateur(String animateurId) {
        if (planPublieService.jamaisPublie()) {
            throw new BusinessError.Invalid("Le planning n'a pas encore été publié : il n'y a rien à renvoyer.");
        }
        PlanningEvenement planning = planPublieService.planPublie();
        Animateur animateur = planning.getAnimateurs().stream()
                .filter(candidat -> candidat.getId().equals(animateurId))
                .findFirst()
                .orElseThrow(() -> new BusinessError.NotFound("Animateur inconnu : " + animateurId));
        if (!hasAddress(animateur)) {
            // By id, not by name: a refusal now travels to whoever asked, MCP
            // included, and no BusinessError may name a person (issue #529).
            // The screen loses nothing — it is the fiche of that very
            // animateur that carries the button.
            throw new BusinessError.Invalid("L'animateur " + animateurId + " n'a pas d'adresse e-mail sur sa fiche.");
        }
        if (deliveries.isAddressBlocked(animateurId)) {
            throw new BusinessError.Conflict(ADRESSE_REFUSEE);
        }
        try {
            send(planning, animateur);
        } catch (RuntimeException e) {
            Log.errorf(e, "Failed to mail the planning of animateur %s", animateur.getId());
            return new DeliveryReport(0, List.of(), List.of("Échec de l'envoi à " + animateur.getEmail()));
        }
        return new DeliveryReport(1, List.of(), List.of());
    }

    /**
     * The same send inside a batch that loaded the published plan once — the
     * « Renvoyer les envois en échec » of the Animateurs page. The caller has
     * checked the address, and that it is not blocked.
     *
     * @return {@code true} when it left; {@code false} when it failed, the
     *         failure being journalled like any other
     */
    boolean resend(PlanningEvenement planning, Animateur animateur) {
        try {
            send(planning, animateur);
            return true;
        } catch (RuntimeException e) {
            Log.errorf(e, "Failed to mail the planning of animateur %s again", animateur.getId());
            return false;
        }
    }

    static boolean hasAddress(Animateur animateur) {
        return animateur.getEmail() != null && !animateur.getEmail().isBlank();
    }

    private void send(PlanningEvenement planning, Animateur animateur) {
        byte[] pdf = planningExportService.exportAnimateurPdfPublie(planning, animateur.getId());
        String lienEspace = planningExportService.lienEspaceAnimateur(planning, animateur.getId());
        deliveries.send(
                animateur.getId(),
                MailKind.PLANNING_INDIVIDUEL,
                () -> mailService.sendIndividualPlanning(
                        animateur.getEmail(),
                        animateur.getPrenom(),
                        lienEspace,
                        pdf,
                        PlanningExportService.planningFileName(animateur.nomAffiche(), "pdf")));
    }
}
