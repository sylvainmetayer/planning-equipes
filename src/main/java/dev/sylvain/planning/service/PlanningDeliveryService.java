package dev.sylvain.planning.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.PlanningEvenement;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Sends the animateurs their individual planning — their PDF as an attachment,
 * the link to their espace in the body.
 *
 * <p>Everything is read server-side from the <b>persisted</b> planning: what
 * leaves by mail is exactly what the espace and the calendars show, and the
 * planning (potentially huge) never travels through the browser.</p>
 *
 * <p>Unlike the swap notifications, best-effort by nature, this is an explicit
 * administration action: the report says who was reached, who has no address,
 * and whose delivery failed.</p>
 */
@ApplicationScoped
public class PlanningDeliveryService {

    @Inject
    PlanningPersistenceService persistenceService;

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

    /** Sends their planning to every animateur holding at least one poste. */
    public DeliveryReport sendToAll() {
        PlanningEvenement planning = persistenceService.loadPersistedPlanning();
        Set<String> animateursAvecPoste = planning.getPostes().stream()
                .filter(poste -> poste.getAnimateur() != null)
                .map(poste -> poste.getAnimateur().getId())
                .collect(Collectors.toSet());

        int envoyes = 0;
        List<String> sansEmail = new ArrayList<>();
        List<String> echecs = new ArrayList<>();
        for (Animateur animateur : planning.getAnimateurs()) {
            if (!animateursAvecPoste.contains(animateur.getId())) {
                continue;
            }
            if (!hasAddress(animateur)) {
                sansEmail.add(animateur.nomAffiche());
                continue;
            }
            try {
                send(planning, animateur);
                envoyes++;
            } catch (RuntimeException e) {
                Log.errorf(e, "Failed to mail the planning of animateur %s", animateur.getId());
                echecs.add(animateur.nomAffiche());
            }
        }
        return new DeliveryReport(envoyes, sansEmail, echecs);
    }

    /**
     * Sends one animateur their planning. Returns the same compte rendu shape
     * as {@link #sendToAll}, so a send that failed comes back described
     * rather than as a stack trace: {@code echecs} then carries what to tell
     * the operator, and the resource decides which status carries it.
     *
     * @throws BusinessError.NotFound when the id names nobody in the plan
     * @throws BusinessError.Invalid    when their fiche carries no address
     */
    public DeliveryReport sendToOneAnimateur(String animateurId) {
        PlanningEvenement planning = persistenceService.loadPersistedPlanning();
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
        byte[] pdf = planningExportService.exportAnimateurPdf(planning, animateur.getId());
        mailService.sendIndividualPlanning(
                animateur.getEmail(),
                animateur.getPrenom(),
                planningExportService.lienEspaceAnimateur(planning, animateur.getId()),
                pdf,
                PlanningExportService.planningFileName(animateur.nomAffiche(), "pdf"));
    }
}
