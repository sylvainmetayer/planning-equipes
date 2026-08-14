package dev.sylvain.planning.mcp;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.NiveauCompetence;
import dev.sylvain.planning.service.ReferenceDataService;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * MCP tools exposing animateurs to an AI assistant. Per the privacy
 * requirement of issue #107, nom/prénom/date de naissance never leave this
 * boundary: only the id, the "majeur"/"mineur" status derived from the birth
 * date, and non-identifying planning attributes (compétences, souhaits,
 * indisponibilités) are returned.
 */
@ApplicationScoped
public class AnimateurMcpTools {

    @Inject
    ReferenceDataService referenceDataService;

    @Tool(description = "Liste les animateurs. Ne renvoie aucune donnée personnelle identifiante (pas de nom, "
            + "prénom, ni date de naissance) : uniquement l'id, le statut majeur/mineur, et les attributs de "
            + "planification (compétences, souhaits, jours indisponibles).")
    List<AnimateurView> lister_animateurs() {
        LocalDate reference = dateReference();
        return referenceDataService.listAnimateurs().stream()
                .map(animateur -> toView(animateur, reference))
                .toList();
    }

    @Tool(description = "Consulte un animateur par son id. Ne renvoie aucune donnée personnelle identifiante.")
    AnimateurView consulter_animateur(@ToolArg(description = "Id de l'animateur") String id) {
        LocalDate reference = dateReference();
        return referenceDataService.listAnimateurs().stream()
                .filter(animateur -> animateur.getId().equals(id))
                .findFirst()
                .map(animateur -> toView(animateur, reference))
                .orElseThrow(() -> new NoSuchElementException("Animateur introuvable : " + id));
    }

    /** Same derivation as {@code PlanningService.construireDepuisReferenceData}: the earliest date of the active groupe de créneaux. */
    private LocalDate dateReference() {
        return referenceDataService.listCreneauxGroupeActif().stream()
                .map(Creneau::getDate)
                .filter(Objects::nonNull)
                .min(LocalDate::compareTo)
                .orElse(LocalDate.now());
    }

    static AnimateurView toView(Animateur animateur, LocalDate reference) {
        String statut = animateur.estMineurLe(reference) ? "mineur" : "majeur";
        return new AnimateurView(animateur.getId(), statut, animateur.isManager(),
                animateur.getCompetences(), animateur.getSouhaits(), animateur.getJoursIndisponibles());
    }

    /**
     * @param statut "majeur" ou "mineur", calculé à la date du premier créneau du groupe de créneaux actif
     */
    public record AnimateurView(String id, String statut, boolean manager,
            Map<String, NiveauCompetence> competences, Set<String> souhaits, Set<LocalDate> joursIndisponibles) {
    }
}
