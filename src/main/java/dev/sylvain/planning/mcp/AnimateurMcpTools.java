package dev.sylvain.planning.mcp;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
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
 * MCP tools exposing animateurs to an AI assistant, mirroring the animateur
 * part of {@code ReferenceDataResource} (list/create/update/delete).
 *
 * <p>Per the privacy requirement of issue #107 — the one restriction the
 * issue kept when its follow-up comment opened MCP to every endpoint —
 * nom/prénom/date de naissance never leave this boundary: only the id, the
 * "majeur"/"mineur" status derived from the birth date, and non-identifying
 * planning attributes (compétences, souhaits, indisponibilités) are returned.
 *
 * <p>That is also why {@link #modifier_animateur} is a <em>merge</em>, not the
 * whole-entity replacement {@code PUT /api/animateurs/{id}} performs: an
 * assistant that cannot read nom/prénom/dateNaissance could not send them
 * back either, so a replacement would silently wipe them on every edit.
 * Omitted arguments therefore keep their persisted value.
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
        return toView(trouver(id), dateReference());
    }

    @Tool(description = "Crée un animateur. Les données personnelles (nom, prénom, date de naissance) sont "
            + "facultatives et ne sont jamais relues par MCP ; la date de naissance reste toutefois la seule "
            + "source du statut mineur/majeur utilisé par les contraintes légales, un animateur créé sans elle "
            + "sera donc traité comme majeur.")
    AnimateurView creer_animateur(
            @ToolArg(description = "Id de l'animateur (unique)") String id,
            @ToolArg(description = "Date de naissance (AAAA-MM-JJ), nécessaire pour les contraintes légales sur les mineurs", required = false) String dateNaissance,
            @ToolArg(description = "Prénom (donnée personnelle, jamais renvoyée)", required = false) String prenom,
            @ToolArg(description = "Nom (donnée personnelle, jamais renvoyée)", required = false) String nom,
            @ToolArg(description = "Statut manager", required = false) Boolean manager,
            @ToolArg(description = "Compétences : id de typologie -> DEBUTANT|AUTONOME|REFERENT", required = false) Map<String, String> competences,
            @ToolArg(description = "Ids de typologies souhaitées", required = false) List<String> souhaits,
            @ToolArg(description = "Jours indisponibles (AAAA-MM-JJ)", required = false) List<String> joursIndisponibles) {
        Animateur animateur = new Animateur();
        animateur.setId(id);
        animateur.setPrenom(prenom);
        animateur.setNom(nom);
        animateur.setDateNaissance(McpArgs.date(dateNaissance, "dateNaissance"));
        animateur.setManager(Boolean.TRUE.equals(manager));
        animateur.setCompetences(competences == null ? new HashMap<>() : competences(competences));
        animateur.setSouhaits(souhaits == null ? new HashSet<>() : new HashSet<>(souhaits));
        animateur.setJoursIndisponibles(McpArgs.dates(joursIndisponibles, "joursIndisponibles"));
        return toView(referenceDataService.createAnimateur(animateur), dateReference());
    }

    @Tool(description = "Modifie un animateur. Seuls les champs fournis sont modifiés : les champs omis — dont "
            + "les données personnelles que MCP ne peut pas lire — conservent leur valeur en base.")
    AnimateurView modifier_animateur(
            @ToolArg(description = "Id de l'animateur") String id,
            @ToolArg(description = "Statut manager", required = false) Boolean manager,
            @ToolArg(description = "Compétences : id de typologie -> DEBUTANT|AUTONOME|REFERENT (remplace la liste existante)", required = false) Map<String, String> competences,
            @ToolArg(description = "Ids de typologies souhaitées (remplace la liste existante)", required = false) List<String> souhaits,
            @ToolArg(description = "Jours indisponibles AAAA-MM-JJ (remplace la liste existante)", required = false) List<String> joursIndisponibles) {
        Animateur animateur = trouver(id);
        if (manager != null) {
            animateur.setManager(manager);
        }
        if (competences != null) {
            animateur.setCompetences(competences(competences));
        }
        if (souhaits != null) {
            animateur.setSouhaits(new HashSet<>(souhaits));
        }
        if (joursIndisponibles != null) {
            animateur.setJoursIndisponibles(McpArgs.dates(joursIndisponibles, "joursIndisponibles"));
        }
        return toView(referenceDataService.updateAnimateur(id, animateur), dateReference());
    }

    @Tool(description = "Supprime un animateur et ses affectations.")
    SuppressionResult supprimer_animateur(@ToolArg(description = "Id de l'animateur") String id) {
        referenceDataService.deleteAnimateur(id);
        return new SuppressionResult(id, true);
    }

    private Animateur trouver(String id) {
        return referenceDataService.listAnimateurs().stream()
                .filter(animateur -> animateur.getId().equals(id))
                .findFirst()
                .orElseThrow(() -> new NoSuchElementException("Animateur introuvable : " + id));
    }

    private static Map<String, NiveauCompetence> competences(Map<String, String> competences) {
        Map<String, NiveauCompetence> parsed = new HashMap<>();
        competences.forEach((typologieId, niveau) -> parsed.put(typologieId,
                McpArgs.enumeration(NiveauCompetence.class, niveau, "niveau de compétence")));
        return parsed;
    }

    /** Same derivation as {@code PlanningService.construireDepuisReferenceData}: the earliest date of the active groupe de créneaux. */
    private LocalDate dateReference() {
        return referenceDataService.listCreneaux().stream()
                .map(Creneau::getDate)
                .filter(Objects::nonNull)
                .min(LocalDate::compareTo)
                .orElse(LocalDate.now());
    }

    static AnimateurView toView(Animateur animateur, LocalDate reference) {
        String statut = animateur.estMineurLe(reference) ? "mineur" : "majeur";
        return new AnimateurView(animateur.getId(), statut, animateur.estMoinsDe16AnsLe(reference),
                animateur.isManager(),
                animateur.getCompetences(), animateur.getSouhaits(), animateur.getJoursIndisponibles());
    }

    /**
     * @param statut         "majeur" ou "mineur", calculé à la date du premier créneau du groupe de créneaux actif
     * @param moinsDe16Ans   troisième régime du droit du travail, dérivé de la même date de référence
     */
    public record AnimateurView(String id, String statut, boolean moinsDe16Ans, boolean manager,
            Map<String, NiveauCompetence> competences, Set<String> souhaits, Set<LocalDate> joursIndisponibles) {
    }
}
