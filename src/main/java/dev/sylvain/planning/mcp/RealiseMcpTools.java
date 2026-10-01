package dev.sylvain.planning.mcp;

import dev.sylvain.planning.service.analyse.RealisedVsPlanned;
import dev.sylvain.planning.service.analyse.RealisedVsPlannedService;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * MCP tool mirroring {@code GET /api/planning/realise}: the gap between the
 * published plan and the plan held, per stand and per elapsed day.
 *
 * <p>The report is returned as the screen reads it, because it carries no
 * person by construction — counts per stand and per day, stand names, never
 * a holder. The detail of a cell, which names the holders, has no tool.</p>
 */
@EditionCiblee
@RefusMetier
@Journalise
@ApplicationScoped
public class RealiseMcpTools {

    private final RealisedVsPlannedService realisedService;

    @Inject
    RealiseMcpTools(RealisedVsPlannedService realisedService) {
        this.realisedService = realisedService;
    }

    @Tool(
            name = "realise_vs_planifie",
            description = "Réalisé vs planifié : l'écart entre le plan publié et le plan tenu, par stand et par "
                    + "journée écoulée — sa dernière vacation terminée, jamais les jours à venir. Chaque journée est "
                    + "comparée à la publication en vigueur à son début (son premier créneau) : une republication en "
                    + "cours d'événement ne déplace pas l'écart des journées déjà passées. Une journée commencée "
                    + "avant toute publication (lateReference) est montrée mais exclue de tous les totaux. Par case : sièges publiés, tenus, absences (titulaire "
                    + "publié marqué absent), remplacements, vides, retirés (consigne ou créneau supprimé — jamais "
                    + "une absence), ajoutés, minutes publiées, réalisées et perdues ; totaux par stand, par jour, "
                    + "par typologie (un stand à plusieurs typologies compte dans chacune) et pour l'événement. "
                    + "Le réalisé est déclaré (le plan enregistré, tel que le mode jour J l'a laissé), "
                    + "referenceAvailable faux veut dire « rien n'a jamais été publié », frozenPast faux que le "
                    + "passé figé est désactivé et que le réalisé n'est plus garanti. Comptes seulement, aucun nom.",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = true,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    RealisedVsPlanned realisedVsPlanned(
            @ToolArg(description = EditionArg.DESCRIPTION, required = false) @EditionArg String edition) {
        return realisedService.report();
    }
}
