package dev.sylvain.planning.api;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.PlanningFestival;
import dev.sylvain.planning.service.MailService;
import dev.sylvain.planning.service.PlanningExportService;
import dev.sylvain.planning.service.PlanningPersistenceService;
import io.quarkus.logging.Log;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * Sends the individual plannings by e-mail (follow-up of issue #165): the
 * animateur's own PDF attached, their espace link in the body. Everything is
 * read server-side from the <b>persisted</b> planning — what gets mailed is
 * exactly what the espace and the calendars show, and the (potentially huge)
 * planning never travels through the browser for this.
 *
 * <p>Unlike the échange notifications (best-effort), sending plannings is an
 * explicit admin action: the response says exactly who was reached, who has no
 * address, and for whom the send failed.</p>
 */
@Path("/planning/envoi")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class EnvoiPlanningResource {

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
    public record CompteRenduEnvoi(int envoyes, List<String> sansEmail, List<String> echecs) {
    }

    /** Sends their planning to every animateur holding at least one poste. */
    @POST
    @Path("/tous")
    public CompteRenduEnvoi envoyerATous() {
        PlanningFestival planning = persistenceService.loadPersistedPlanning();
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
            if (animateur.getEmail() == null || animateur.getEmail().isBlank()) {
                sansEmail.add(nomComplet(animateur));
                continue;
            }
            try {
                envoyer(planning, animateur);
                envoyes++;
            } catch (RuntimeException e) {
                Log.errorf(e, "Failed to mail the planning of animateur %s", animateur.getId());
                echecs.add(nomComplet(animateur));
            }
        }
        return new CompteRenduEnvoi(envoyes, sansEmail, echecs);
    }

    /** Sends one animateur their planning; 400 without an address, 404 unknown. */
    @POST
    @Path("/animateur/{animateurId}")
    public Response envoyerAUnAnimateur(@PathParam("animateurId") String animateurId) {
        PlanningFestival planning = persistenceService.loadPersistedPlanning();
        Animateur animateur = planning.getAnimateurs().stream()
                .filter(candidat -> candidat.getId().equals(animateurId))
                .findFirst()
                .orElse(null);
        if (animateur == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(new ErreurValidation("Animateur inconnu : " + animateurId))
                    .build();
        }
        if (animateur.getEmail() == null || animateur.getEmail().isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(new ErreurValidation(
                            nomComplet(animateur) + " n'a pas d'adresse e-mail sur sa fiche"))
                    .build();
        }
        try {
            envoyer(planning, animateur);
        } catch (RuntimeException e) {
            Log.errorf(e, "Failed to mail the planning of animateur %s", animateur.getId());
            return Response.serverError()
                    .entity(new ErreurValidation(
                            "Échec de l'envoi à " + animateur.getEmail()))
                    .build();
        }
        return Response.ok(new CompteRenduEnvoi(1, List.of(), List.of())).build();
    }

    private void envoyer(PlanningFestival planning, Animateur animateur) {
        byte[] pdf = planningExportService.exportAnimateurPdf(planning, animateur.getId());
        mailService.envoyerPlanningIndividuel(
                animateur.getEmail(),
                animateur.getPrenom(),
                planningExportService.lienEspaceAnimateur(planning, animateur.getId()),
                pdf,
                nomFichier(animateur));
    }

    private static String nomComplet(Animateur animateur) {
        String nom = ((animateur.getPrenom() == null ? "" : animateur.getPrenom()) + " "
                + (animateur.getNom() == null ? "" : animateur.getNom())).trim();
        return nom.isEmpty() ? animateur.getId() : nom;
    }

    /** Same readable convention as the download: {@code planning-Prenom-Nom.pdf}. */
    private static String nomFichier(Animateur animateur) {
        String safe = nomComplet(animateur).replaceAll("[^\\p{L}\\p{N}]+", "-")
                .replaceAll("^-+|-+$", "");
        return "planning-" + (safe.isEmpty() ? "animateur" : safe) + ".pdf";
    }
}
