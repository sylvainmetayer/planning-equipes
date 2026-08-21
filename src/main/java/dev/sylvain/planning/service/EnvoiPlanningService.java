package dev.sylvain.planning.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.PlanningFestival;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Envoyer aux animateurs leur planning individuel — leur PDF en pièce jointe,
 * le lien de leur espace dans le corps.
 *
 * <p>Tout est lu côté serveur depuis le planning <b>persisté</b> : ce qui part
 * par mail est exactement ce que montrent l'espace et les calendriers, et le
 * planning (potentiellement énorme) ne transite pas par le navigateur.</p>
 *
 * <p>Contrairement aux notifications d'échange, best-effort par nature, c'est
 * une action d'administration explicite : le compte rendu dit qui a été
 * touché, qui n'a pas d'adresse, et pour qui l'envoi a échoué.</p>
 */
@ApplicationScoped
public class EnvoiPlanningService {

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
            if (!aUneAdresse(animateur)) {
                sansEmail.add(animateur.nomAffiche());
                continue;
            }
            try {
                envoyer(planning, animateur);
                envoyes++;
            } catch (RuntimeException e) {
                Log.errorf(e, "Failed to mail the planning of animateur %s", animateur.getId());
                echecs.add(animateur.nomAffiche());
            }
        }
        return new CompteRenduEnvoi(envoyes, sansEmail, echecs);
    }

    /**
     * Sends one animateur their planning. Returns the same compte rendu shape
     * as {@link #envoyerATous}, so a send that failed comes back described
     * rather than as a stack trace: {@code echecs} then carries what to tell
     * the operator, and the resource decides which status carries it.
     *
     * @throws ErreurMetier.Introuvable when the id names nobody in the plan
     * @throws ErreurMetier.Invalide    when their fiche carries no address
     */
    public CompteRenduEnvoi envoyerAUnAnimateur(String animateurId) {
        PlanningFestival planning = persistenceService.loadPersistedPlanning();
        Animateur animateur = planning.getAnimateurs().stream()
                .filter(candidat -> candidat.getId().equals(animateurId))
                .findFirst()
                .orElseThrow(() -> new ErreurMetier.Introuvable("Animateur inconnu : " + animateurId));
        if (!aUneAdresse(animateur)) {
            throw new ErreurMetier.Invalide(animateur.nomAffiche() + " n'a pas d'adresse e-mail sur sa fiche");
        }
        try {
            envoyer(planning, animateur);
        } catch (RuntimeException e) {
            Log.errorf(e, "Failed to mail the planning of animateur %s", animateur.getId());
            return new CompteRenduEnvoi(0, List.of(), List.of("Échec de l'envoi à " + animateur.getEmail()));
        }
        return new CompteRenduEnvoi(1, List.of(), List.of());
    }

    private static boolean aUneAdresse(Animateur animateur) {
        return animateur.getEmail() != null && !animateur.getEmail().isBlank();
    }

    private void envoyer(PlanningFestival planning, Animateur animateur) {
        byte[] pdf = planningExportService.exportAnimateurPdf(planning, animateur.getId());
        mailService.envoyerPlanningIndividuel(
                animateur.getEmail(),
                animateur.getPrenom(),
                planningExportService.lienEspaceAnimateur(planning, animateur.getId()),
                pdf,
                PlanningExportService.nomFichierPlanning(animateur.nomAffiche(), "pdf"));
    }
}
