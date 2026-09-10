package dev.sylvain.planning.mcp;

import java.time.Instant;
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
import dev.sylvain.planning.service.WrittenAnimateur;
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
@EditionCiblee
@Journalise
@ApplicationScoped
public class AnimateurMcpTools {

    @Inject
    ReferenceDataService referenceDataService;

    @Tool(description = "Liste les animateurs. Ne renvoie aucune donnée personnelle identifiante (pas de nom, "
            + "prénom, ni date de naissance) : uniquement l'id, le statut majeur/mineur, et les attributs de "
            + "planification (compétences, souhaits, jours indisponibles). Sans limite, renvoie tout l'effectif ; "
            + "total dit toujours combien il y en a.",
            annotations = @Tool.Annotations(readOnlyHint = true, destructiveHint = false,
                    idempotentHint = true, openWorldHint = false))
    AnimateursView lister_animateurs(
            @ToolArg(description = "Nombre maximum d'animateurs renvoyés (défaut : tous)", required = false) Integer limite,
            @ToolArg(description = EditionArg.DESCRIPTION, required = false) @EditionArg String edition) {
        LocalDate reference = dateReference();
        List<Animateur> animateurs = referenceDataService.listAnimateurs();
        return new AnimateursView(animateurs.size(), animateurs.stream()
                .limit(McpArgs.limite(limite, animateurs.size()))
                .map(animateur -> toView(animateur, reference))
                .toList());
    }

    @Tool(description = "Consulte un animateur par son id. Ne renvoie aucune donnée personnelle identifiante.",
            annotations = @Tool.Annotations(readOnlyHint = true, destructiveHint = false,
                    idempotentHint = true, openWorldHint = false))
    AnimateurView consulter_animateur(@ToolArg(description = "Id de l'animateur") String id,
            @ToolArg(description = EditionArg.DESCRIPTION, required = false) @EditionArg String edition) {
        return toView(find(id), dateReference());
    }

    @Tool(description = "Crée un animateur. Les données personnelles (nom, prénom, date de naissance) sont "
            + "facultatives et ne sont jamais relues par MCP ; la date de naissance reste toutefois la seule "
            + "source du statut mineur/majeur utilisé par les contraintes légales, un animateur créé sans elle "
            + "sera donc traité comme majeur.",
            annotations = @Tool.Annotations(readOnlyHint = false, destructiveHint = false,
                    idempotentHint = false, openWorldHint = false))
    WrittenAnimateurView creer_animateur(
            @ToolArg(description = "Id de l'animateur (unique)") String id,
            @ToolArg(description = "Date de naissance (AAAA-MM-JJ), nécessaire pour les contraintes légales sur les mineurs", required = false) String dateNaissance,
            @ToolArg(description = "Prénom (donnée personnelle, jamais renvoyée)", required = false) String prenom,
            @ToolArg(description = "Nom (donnée personnelle, jamais renvoyée)", required = false) String nom,
            @ToolArg(description = "Statut manager", required = false) Boolean manager,
            @ToolArg(description = "Compétences : id de typologie -> DEBUTANT|AUTONOME|REFERENT", required = false) Map<String, String> competences,
            @ToolArg(description = "Ids de typologies souhaitées", required = false) List<String> souhaits,
            @ToolArg(description = "Jours indisponibles (AAAA-MM-JJ)", required = false) List<String> joursIndisponibles,
            @ToolArg(description = EditionArg.DESCRIPTION, required = false) @EditionArg String edition) {
        Animateur animateur = new Animateur();
        animateur.setId(id);
        animateur.setPrenom(prenom);
        animateur.setNom(nom);
        animateur.setDateNaissance(McpArgs.date(dateNaissance, "dateNaissance"));
        animateur.setManager(Boolean.TRUE.equals(manager));
        animateur.setCompetences(competences == null ? new HashMap<>() : competences(competences));
        animateur.setSouhaits(souhaits == null ? new HashSet<>() : new HashSet<>(souhaits));
        animateur.setJoursIndisponibles(McpArgs.dates(joursIndisponibles, "joursIndisponibles"));
        return written(referenceDataService.writeAnimateur(animateur));
    }

    @Tool(description = "Modifie un animateur. Seuls les champs fournis sont modifiés : les champs omis — dont "
            + "les données personnelles que MCP ne peut pas lire — conservent leur valeur en base.",
            annotations = @Tool.Annotations(readOnlyHint = false, destructiveHint = false,
                    idempotentHint = true, openWorldHint = false))
    WrittenAnimateurView modifier_animateur(
            @ToolArg(description = "Id de l'animateur") String id,
            @ToolArg(description = "Statut manager", required = false) Boolean manager,
            @ToolArg(description = "Compétences : id de typologie -> DEBUTANT|AUTONOME|REFERENT (remplace la liste existante)", required = false) Map<String, String> competences,
            @ToolArg(description = "Ids de typologies souhaitées (remplace la liste existante)", required = false) List<String> souhaits,
            @ToolArg(description = "Jours indisponibles AAAA-MM-JJ (remplace la liste existante)", required = false) List<String> joursIndisponibles,
            @ToolArg(description = "WriteStamp modifieLe lu avant la modification (précondition : refusé si la fiche a changé depuis ; omis, pas de contrôle)", required = false) String modifieLe,
            @ToolArg(description = EditionArg.DESCRIPTION, required = false) @EditionArg String edition) {
        Animateur animateur = find(id);
        if (modifieLe != null) {
            animateur.setModifieLe(McpArgs.instant(modifieLe, "modifieLe"));
        }
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
        return written(referenceDataService.writeAnimateur(id, animateur));
    }

    @Tool(description = "Supprime un animateur. Ses postes dans le planning enregistré ne sont pas "
            + "supprimés : ils sont vidés et redeviennent des places non pourvues, à repourvoir "
            + "à la prochaine résolution. Refusé (409) tant qu'une résolution est en cours sur cette édition, "
            + "sinon elle réinsérerait l'animateur en enregistrant son résultat.",
            annotations = @Tool.Annotations(readOnlyHint = false, destructiveHint = true,
                    idempotentHint = false, openWorldHint = false))
    SuppressionResult supprimer_animateur(@ToolArg(description = "Id de l'animateur") String id,
            @ToolArg(description = EditionArg.DESCRIPTION, required = false) @EditionArg String edition) {
        referenceDataService.deleteAnimateur(id);
        return new SuppressionResult(id, true);
    }

    private Animateur find(String id) {
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

    /** Same derivation as {@code PlanningService.buildFromReferenceData}: the earliest date of the active groupe de créneaux. */
    private LocalDate dateReference() {
        return referenceDataService.listCreneaux().stream()
                .map(Creneau::getDate)
                .filter(Objects::nonNull)
                .min(LocalDate::compareTo)
                .orElse(LocalDate.now());
    }

    private WrittenAnimateurView written(WrittenAnimateur ecrit) {
        return new WrittenAnimateurView(toView(ecrit.animateur(), dateReference()), WarningCodes.of(ecrit.avertissements()));
    }

    static AnimateurView toView(Animateur animateur, LocalDate reference) {
        String statut = animateur.isMineurOn(reference) ? "mineur" : "majeur";
        return new AnimateurView(animateur.getId(), statut, animateur.isUnder16On(reference),
                animateur.isManager(),
                animateur.getCompetences(), animateur.getSouhaits(), animateur.getJoursIndisponibles(),
                animateur.getModifieLe());
    }

    /**
     * @param statut         "majeur" or "mineur", computed at the date of the first timeslot of the active group
     * @param moinsDe16Ans   the third regime of French labour law, derived from the same reference date
     */
    /** @param total animateurs in the edition, which may exceed the number returned */
    public record AnimateursView(int total, List<AnimateurView> animateurs) {
    }

    /**
     * A write, and what it is worth a second look at — the same shape as the
     * REST {@code WrittenAnimateur}, with the warnings as codes (see
     * {@link WarningCodes}).
     */
    public record WrittenAnimateurView(AnimateurView animateur, List<String> avertissements) {
    }

    public record AnimateurView(String id, String statut, boolean moinsDe16Ans, boolean manager,
            Map<String, NiveauCompetence> competences, Set<String> souhaits, Set<LocalDate> joursIndisponibles,
            Instant modifieLe) {
    }
}
