package dev.sylvain.planning.mcp;

import dev.sylvain.planning.service.BusinessError;
import dev.sylvain.planning.service.analyse.PlanningKpiService.PlanningKpi;
import dev.sylvain.planning.service.solve.PlanSnapshotService;
import dev.sylvain.planning.service.solve.PlanSnapshotService.AffectationSnapshot;
import dev.sylvain.planning.service.solve.PlanSnapshotService.RestaurationResult;
import dev.sylvain.planning.service.solve.PlanSnapshotService.SnapshotDetail;
import dev.sylvain.planning.service.solve.PlanSnapshotService.SnapshotMeta;
import dev.sylvain.planning.service.solve.SnapshotComparisonService;
import dev.sylvain.planning.service.solve.SnapshotComparisonService.ComparaisonSnapshots;
import dev.sylvain.planning.service.solve.SolverJobService.SolverBusyException;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;

/**
 * MCP tools over plan snapshots ({@code PlanSnapshotResource}): the only
 * persistence holding more than one plan per edition, and therefore the only
 * way back from a solve that made things worse.
 *
 * <p>An assistant that can solve but cannot capture, compare and restore can
 * degrade a planning irreversibly — the previous plan is overwritten by the
 * next run. These tools close that, and pair with the automatic capture the
 * solve path already takes on its own.</p>
 *
 * <p>A snapshot holds seats, so its content is filtered like every other seat
 * listing here: stand, créneau, hours and the animateur <b>id</b>, never a
 * name. {@code consulter_instantane} is capped, since a real snapshot carries
 * thousands of seats.</p>
 */
@EditionCiblee
@RefusMetier
@Journalise
@ApplicationScoped
public class InstantaneMcpTools {

    /**
     * Seats returned by {@code consulter_instantane} when the caller does not
     * say. A festival snapshot holds a few thousand of them: answering with
     * all of them by default would spend the assistant's whole context on one
     * call, and the interesting question is almost always about one stand or
     * one animateur.
     */
    static final int LIMITE_AFFECTATIONS_DEFAUT = 200;

    @Inject
    PlanSnapshotService snapshotService;

    @Inject
    SnapshotComparisonService comparaisonService;

    @Tool(
            description = "Liste les instantanés du planning de l'édition, du plus récent au plus ancien : "
                    + "libellé, score, nombre d'affectations et KPI au moment de la capture. Ne renvoie pas leur contenu "
                    + "(voir consulter_instantane).",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = true,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    List<InstantaneView> lister_instantanes(
            @ToolArg(description = EditionArg.DESCRIPTION, required = false) @EditionArg String edition) {
        return snapshotService.list().stream().map(InstantaneMcpTools::toView).toList();
    }

    @Tool(
            description = "Contenu d'un instantané : ses métadonnées et ses affectations, filtrables par stand ou "
                    + "par animateur. Ne renvoie que des ids, jamais de données personnelles. La liste est plafonnée : "
                    + "affectationsTotal dit combien il en contient réellement.",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = true,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    InstantaneDetailView consulter_instantane(
            @ToolArg(description = "Id de l'instantané") long id,
            @ToolArg(description = "Id de stand pour filtrer", required = false) String standId,
            @ToolArg(description = "Id d'animateur pour filtrer", required = false) String animateurId,
            @ToolArg(description = "Nombre maximum d'affectations renvoyées (défaut 200)", required = false)
                    Integer limite,
            @ToolArg(description = EditionArg.DESCRIPTION, required = false) @EditionArg String edition) {
        SnapshotDetail detail = snapshotService.load(id);
        if (detail == null) {
            throw new BusinessError.NotFound("Instantané introuvable dans cette édition : " + id);
        }
        List<AffectationSnapshot> retenues = detail.affectations().stream()
                .filter(affectation -> standId == null || standId.equals(affectation.standId()))
                .filter(affectation -> animateurId == null || animateurId.equals(affectation.animateurId()))
                .toList();
        int plafond = McpArgs.limite(limite, LIMITE_AFFECTATIONS_DEFAUT);
        return new InstantaneDetailView(
                toView(detail.meta()),
                retenues.size(),
                retenues.stream().limit(plafond).map(InstantaneMcpTools::toView).toList());
    }

    @Tool(
            description =
                    "Capture le planning persisté dans un nouvel instantané, pour pouvoir y revenir. "
                            + "Échoue s'il n'y a aucun planning persisté : un instantané vide ne serait qu'un piège à restaurer.",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = false,
                            destructiveHint = false,
                            idempotentHint = false,
                            openWorldHint = false))
    InstantaneView capturer_instantane(
            @ToolArg(description = "Libellé de l'instantané", required = false) String libelle,
            @ToolArg(description = EditionArg.DESCRIPTION, required = false) @EditionArg String edition) {
        String nom = libelle == null || libelle.isBlank() ? "Instantané" : libelle.trim();
        SnapshotMeta meta = snapshotService.capture(nom, false);
        if (meta == null) {
            throw new BusinessError.Conflict("Aucun planning persisté à enregistrer : lancez d'abord une résolution.");
        }
        return toView(meta);
    }

    /**
     * The 409 of {@code POST /{id}/restore} becomes a thrown
     * {@link BusinessError.Conflict} carrying the missing ids in its message:
     * a tool answer an assistant reads as success must not be the one that
     * says nothing was restored.
     */
    @Tool(
            description = "Restaure un instantané à la place du planning courant. Refusé si l'instantané référence "
                    + "des stands, créneaux ou animateurs qui n'existent plus, ou si une résolution est en cours sur "
                    + "l'édition (elle écraserait le plan restauré) : rien n'est alors écrit.",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = false,
                            destructiveHint = true,
                            idempotentHint = true,
                            openWorldHint = false))
    RestaurationView restaurer_instantane(
            @ToolArg(description = "Id de l'instantané") long id,
            @ToolArg(description = EditionArg.DESCRIPTION, required = false) @EditionArg String edition) {
        RestaurationResult result;
        try {
            result = snapshotService.restaurer(id);
        } catch (SolverBusyException occupe) {
            // Same refusal as the REST 409 (SolverOccupeMapper), said in the
            // assistant's terms: the run to wait for, or to stop, is named.
            throw new BusinessError.Conflict("Une résolution est en cours sur cette édition (job "
                    + occupe.getActiveJob().getId() + ") : elle écraserait le plan restauré en se terminant. "
                    + "Attendez sa fin ou arrêtez-la (arreter_solveur), puis restaurez.");
        }
        if (result == null) {
            throw new BusinessError.NotFound("Instantané introuvable dans cette édition : " + id);
        }
        if (!result.restaure()) {
            throw new BusinessError.Conflict("L'instantané référence des données qui n'existent plus, rien n'a été "
                    + "restauré : " + String.join(", ", result.referencesManquantes()));
        }
        return new RestaurationView(id, result.affectations());
    }

    @Tool(
            description = "Supprime un instantané. Le planning courant n'est pas touché.",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = false,
                            destructiveHint = true,
                            idempotentHint = false,
                            openWorldHint = false))
    SuppressionResult supprimer_instantane(
            @ToolArg(description = "Id de l'instantané") long id,
            @ToolArg(description = EditionArg.DESCRIPTION, required = false) @EditionArg String edition) {
        if (!snapshotService.delete(id)) {
            throw new BusinessError.NotFound("Instantané introuvable dans cette édition : " + id);
        }
        return new SuppressionResult(String.valueOf(id), true);
    }

    /**
     * Comparison reads across editions on purpose — since #172 a variant of an
     * edition <b>is</b> another edition, so the pair worth comparing usually
     * straddles two. The {@code edition} argument is still meaningful: it says
     * which edition {@code courant} designates.
     */
    @Tool(
            description = "Compare deux plannings, chacun désigné par un id d'instantané ou par « courant » pour le "
                    + "planning persisté. Ne relance aucune résolution et ne recalcule aucun score : lit les mesures déjà "
                    + "prises. Les deux côtés peuvent appartenir à des éditions différentes.",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = true,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    ComparaisonSnapshots comparer_instantanes(
            @ToolArg(description = "Côté de référence : id d'instantané ou « courant »") String base,
            @ToolArg(description = "Côté comparé : id d'instantané ou « courant »") String variante,
            @ToolArg(description = EditionArg.DESCRIPTION, required = false) @EditionArg String edition) {
        ComparaisonSnapshots comparaison = comparaisonService.comparer(base, variante);
        if (comparaison == null) {
            throw new BusinessError.NotFound("Instantané introuvable : « " + base + " » ou « " + variante + " »");
        }
        return comparaison;
    }

    static InstantaneView toView(SnapshotMeta meta) {
        return new InstantaneView(
                meta.id(),
                meta.libelle(),
                meta.automatique(),
                meta.score(),
                meta.nombreAffectations(),
                meta.creeLe(),
                meta.editionId(),
                meta.editionNom(),
                meta.kpi(),
                meta.publieLe());
    }

    private static AffectationInstantaneView toView(AffectationSnapshot affectation) {
        return new AffectationInstantaneView(
                affectation.posteId(),
                affectation.standId(),
                affectation.creneauId(),
                affectation.animateurId(),
                affectation.heureDebutEffective(),
                affectation.heureFinEffective());
    }

    /** A snapshot without its content — {@code SnapshotMeta} carries no personal field. */
    public record InstantaneView(
            long id,
            String libelle,
            boolean automatique,
            String score,
            int nombreAffectations,
            Instant creeLe,
            String editionId,
            String editionNom,
            PlanningKpi kpi,
            Instant publieLe) {}

    /**
     * @param affectationsTotal seats matching the filters, which may exceed the
     *                          number actually returned
     */
    public record InstantaneDetailView(
            InstantaneView instantane, int affectationsTotal, List<AffectationInstantaneView> affectations) {}

    public record AffectationInstantaneView(
            String posteId, String standId, String creneauId, String animateurId, String heureDebut, String heureFin) {}

    public record RestaurationView(long instantaneId, int affectationsRestaurees) {}
}
