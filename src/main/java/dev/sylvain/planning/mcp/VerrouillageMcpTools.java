package dev.sylvain.planning.mcp;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import dev.sylvain.planning.domain.TypeVerrouillage;
import dev.sylvain.planning.domain.VerrouillagePlanning;
import dev.sylvain.planning.service.BusinessError;
import dev.sylvain.planning.service.referentiel.ReferenceDataService;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * MCP tools mirroring {@code VerrouillageResource}: what the operator has
 * decided the solver may no longer move.
 *
 * <p>Without them an assistant could launch a solve but not protect what the
 * previous one got right, so every run started from nothing — the "je fige ce
 * qui est bon, je relance le reste" loop was the one thing MCP could not
 * do.</p>
 *
 * <p>A lock names an animateur by id only, like everything else here, so it
 * carries no personal data of its own.</p>
 */
@EditionCiblee
@Journalise
@ApplicationScoped
public class VerrouillageMcpTools {

    @Inject
    ReferenceDataService referenceDataService;

    @Tool(description = "Liste les verrouillages du planning : ce que le solveur n'a plus le droit de déplacer. Un "
            + "verrouillage conserve ce que la dernière résolution a produit ; pour imposer ou interdire une "
            + "affectation avant le calcul, c'est une contrainte ad hoc (creer_contrainte_ad_hoc). "
            + "Les animateurs y sont désignés par id seul.",
            annotations = @Tool.Annotations(readOnlyHint = true, destructiveHint = false,
                    idempotentHint = true, openWorldHint = false))
    List<VerrouillageView> lister_verrouillages(
            @ToolArg(description = EditionArg.DESCRIPTION, required = false) @EditionArg String edition) {
        return referenceDataService.listVerrouillages().stream()
                .map(VerrouillageMcpTools::toView)
                .toList();
    }

    /**
     * One tool for the five {@link TypeVerrouillage} values rather than five
     * tools: the target columns are mutually exclusive, so five near-identical
     * tools would only move the "which argument goes with which type" question
     * from the description to the tool list. The description carries the
     * mapping, and {@code VerrouillageService} refuses a mismatch with a
     * readable message.
     */
    @Tool(description = "Fige une partie du planning pour les prochaines résolutions. Le type détermine la cible "
            + "attendue : ANIMATEUR (animateurId), STAND (standId), CRENEAU (creneauId), JOUR (jour), "
            + "ANIMATEUR_CRENEAU (animateurId + creneauId). Verrouiller une cible déjà verrouillée ne crée pas "
            + "de doublon.",
            annotations = @Tool.Annotations(readOnlyHint = false, destructiveHint = false,
                    idempotentHint = true, openWorldHint = false))
    VerrouillageView verrouiller(
            @ToolArg(description = "ANIMATEUR | STAND | CRENEAU | JOUR | ANIMATEUR_CRENEAU") String type,
            @ToolArg(description = "Id de l'animateur (types ANIMATEUR et ANIMATEUR_CRENEAU)", required = false) String animateurId,
            @ToolArg(description = "Id du stand (type STAND)", required = false) String standId,
            @ToolArg(description = "Id du créneau (types CRENEAU et ANIMATEUR_CRENEAU)", required = false) Long creneauId,
            @ToolArg(description = "Jour à figer (AAAA-MM-JJ, type JOUR)", required = false) String jour,
            @ToolArg(description = "Raison du verrouillage, libre", required = false) String raison,
            @ToolArg(description = EditionArg.DESCRIPTION, required = false) @EditionArg String edition) {
        VerrouillagePlanning verrouillage = new VerrouillagePlanning();
        verrouillage.setType(McpArgs.enumeration(TypeVerrouillage.class, type, "type"));
        verrouillage.setAnimateurId(animateurId);
        verrouillage.setStandId(standId);
        verrouillage.setCreneauId(creneauId);
        verrouillage.setJour(McpArgs.date(jour, "jour"));
        verrouillage.setRaison(raison);
        return toView(referenceDataService.createVerrouillage(verrouillage));
    }

    /**
     * Unlike {@code DELETE /api/verrouillages/{id}}, which answers 204 whatever
     * happened, an unknown id is reported. The REST caller is a screen that
     * just listed the locks and knows the row was there; an assistant works
     * from ids it may have invented, and "supprimé" on a lock that never
     * existed would let it believe the planning is free to move.
     */
    @Tool(description = "Retire un verrouillage : la partie du planning qu'il figeait redevient déplaçable.",
            annotations = @Tool.Annotations(readOnlyHint = false, destructiveHint = true,
                    idempotentHint = false, openWorldHint = false))
    SuppressionResult deverrouiller(@ToolArg(description = "Id du verrouillage") String id,
            @ToolArg(description = EditionArg.DESCRIPTION, required = false) @EditionArg String edition) {
        boolean connu = referenceDataService.listVerrouillages().stream()
                .anyMatch(verrouillage -> verrouillage.getId() != null && verrouillage.getId().equals(id));
        if (!connu) {
            throw new BusinessError.NotFound("Verrouillage introuvable : " + id);
        }
        referenceDataService.deleteVerrouillage(id);
        return new SuppressionResult(id, true);
    }

    static VerrouillageView toView(VerrouillagePlanning verrouillage) {
        return new VerrouillageView(verrouillage.getId(),
                verrouillage.getType() == null ? null : verrouillage.getType().name(),
                verrouillage.getAnimateurId(), verrouillage.getStandId(), verrouillage.getCreneauId(),
                verrouillage.getJour(), verrouillage.getRaison(), verrouillage.getCreeLe());
    }

    /** A lock, with only the target column its type actually uses filled in. */
    public record VerrouillageView(String id, String type, String animateurId, String standId, Long creneauId,
            LocalDate jour, String raison, Instant creeLe) {
    }
}
