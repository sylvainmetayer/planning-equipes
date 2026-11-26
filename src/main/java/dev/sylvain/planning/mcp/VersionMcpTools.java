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
 * MCP tools over plan versions ({@code VersionPlanResource}): the only
 * persistence holding more than one plan per edition, and therefore the only
 * way back from a solve that made things worse.
 *
 * <p>An assistant that can solve but cannot capture, compare and restore can
 * degrade a planning irreversibly — the previous plan is overwritten by the
 * next run. These tools close that, and pair with the automatic capture the
 * solve path already takes on its own.</p>
 *
 * <p>A version holds seats, so its content is filtered like every other seat
 * listing here: stand, créneau, hours and the animateur <b>id</b>, never a
 * name. {@code consulter_version} is capped, since a real version carries
 * thousands of seats.</p>
 */
@EditionCiblee
@RefusMetier
@Journalise
@ApplicationScoped
public class VersionMcpTools {

    /**
     * Seats returned by {@code consulter_version} when the caller does not
     * say. A festival snapshot holds a few thousand of them: answering with
     * all of them by default would spend the assistant's whole context on one
     * call, and the interesting question is almost always about one stand or
     * one animateur.
     */
    static final int LIMITE_AFFECTATIONS_DEFAUT = 200;

    private final PlanSnapshotService snapshotService;

    private final SnapshotComparisonService comparaisonService;

    @Inject
    VersionMcpTools(PlanSnapshotService snapshotService, SnapshotComparisonService comparaisonService) {
        this.snapshotService = snapshotService;
        this.comparaisonService = comparaisonService;
    }

    @Tool(
            name = "lister_versions",
            description =
                    "Liste les versions du plan de l'édition (manuelles, prises avant un calcul ou publiées), du plus récent "
                            + "au plus ancien : libellé, origine, score, nombre d'affectations et KPI au moment de la capture. Ne renvoie pas leur contenu "
                            + "(voir consulter_version).",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = true,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    List<VersionView> listSnapshots(@ToolArg(description = EditionArg.DESCRIPTION) @EditionArg String edition) {
        return snapshotService.list().stream().map(VersionMcpTools::toView).toList();
    }

    @Tool(
            name = "consulter_version",
            description = "Contenu d'une version : ses métadonnées et ses affectations, filtrables par stand ou "
                    + "par animateur. Ne renvoie que des ids, jamais de données personnelles. La liste est plafonnée : "
                    + "affectationsTotal dit combien il en contient réellement.",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = true,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    VersionDetailView getSnapshot(
            @ToolArg(description = "Id de la version") long id,
            @ToolArg(description = "Id de stand pour filtrer", required = false) String standId,
            @ToolArg(description = "Id d'animateur pour filtrer", required = false) String animateurId,
            @ToolArg(description = "Nombre maximum d'affectations renvoyées (défaut 200)", required = false)
                    Integer limite,
            @ToolArg(description = EditionArg.DESCRIPTION) @EditionArg String edition) {
        SnapshotDetail detail = snapshotService.load(id);
        if (detail == null) {
            throw new BusinessError.NotFound("Version introuvable dans cette édition : " + id);
        }
        List<AffectationSnapshot> retenues = detail.affectations().stream()
                .filter(affectation -> standId == null || standId.equals(affectation.standId()))
                .filter(affectation -> animateurId == null || animateurId.equals(affectation.animateurId()))
                .toList();
        int plafond = McpArgs.limite(limite, LIMITE_AFFECTATIONS_DEFAUT);
        return new VersionDetailView(
                toView(detail.meta()),
                retenues.size(),
                retenues.stream().limit(plafond).map(VersionMcpTools::toView).toList());
    }

    @Tool(
            name = "creer_version",
            description =
                    "Enregistre le planning persisté dans une nouvelle version (origine MANUELLE), pour pouvoir y revenir. "
                            + "Échoue s'il n'y a aucun planning persisté : une version vide ne serait qu'un piège à restaurer.",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = false,
                            destructiveHint = false,
                            idempotentHint = false,
                            openWorldHint = false))
    VersionView captureSnapshot(
            @ToolArg(description = "Libellé de la version", required = false) String libelle,
            @ToolArg(description = EditionArg.DESCRIPTION) @EditionArg String edition) {
        String nom = libelle == null || libelle.isBlank() ? "Version" : libelle.trim();
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
            name = "restaurer_version",
            description = "Restaure une version à la place du planning courant. Refusé si la version référence "
                    + "des stands, créneaux ou animateurs qui n'existent plus, ou si une résolution est en cours sur "
                    + "l'édition (elle écraserait le plan restauré) : rien n'est alors écrit. Refusé aussi si le "
                    + "référentiel a été modifié depuis la capture (champ perime) : demandez à l'utilisateur avant de "
                    + "réessayer avec forcer=true, car remettre ce plan en place annulerait silencieusement ce qui a "
                    + "changé depuis.",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = false,
                            destructiveHint = true,
                            idempotentHint = true,
                            openWorldHint = false))
    RestaurationView restoreSnapshot(
            @ToolArg(description = "Id de la version") long id,
            @ToolArg(
                            description = "Restaurer même si la version est périmée (référentiel modifié depuis la "
                                    + "capture). Ne lève aucun autre refus.",
                            required = false)
                    Boolean forcer,
            @ToolArg(description = EditionArg.DESCRIPTION) @EditionArg String edition) {
        RestaurationResult result;
        try {
            result = snapshotService.restaurer(id, Boolean.TRUE.equals(forcer));
        } catch (SolverBusyException occupe) {
            // Same refusal as the REST 409 (SolverOccupeMapper), said in the
            // assistant's terms: the run to wait for, or to stop, is named.
            throw new BusinessError.Conflict("Une résolution est en cours sur cette édition (job "
                    + occupe.getActiveJob().getId() + ") : elle écraserait le plan restauré en se terminant. "
                    + "Attendez sa fin ou arrêtez-la (arreter_solveur), puis restaurez.");
        }
        if (result == null) {
            throw new BusinessError.NotFound("Version introuvable dans cette édition : " + id);
        }
        if (result.perime()) {
            throw new BusinessError.Conflict("Le référentiel de cette édition a été modifié le "
                    + result.referenceModifieLe() + ", après la capture du " + result.creeLe()
                    + " : la version ne décrit plus les données actuelles et rien n'a été restauré. "
                    + "Demandez à l'utilisateur s'il veut quand même le remettre en place (forcer=true) ou "
                    + "relancer une résolution.");
        }
        if (!result.restaure()) {
            throw new BusinessError.Conflict("La version référence des données qui n'existent plus, rien n'a été "
                    + "restauré : " + String.join(", ", result.referencesManquantes()));
        }
        return new RestaurationView(id, result.affectations());
    }

    @Tool(
            name = "supprimer_version",
            description = "Supprime une version. Le planning courant n'est pas touché. La dernière"
                    + " publication est refusée : c'est le plan que les animateurs ont reçu. Une publication"
                    + " qu'elle a remplacée est refusée aussi tant qu'elle était le plan en vigueur au début"
                    + " d'une journée écoulée : « Réalisé vs planifié » mesure cette journée par rapport à elle."
                    + " Les autres publications remplacées se suppriment normalement.",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = false,
                            destructiveHint = true,
                            idempotentHint = false,
                            openWorldHint = false))
    SuppressionResult deleteSnapshot(
            @ToolArg(description = "Id de la version") long id,
            @ToolArg(description = EditionArg.DESCRIPTION) @EditionArg String edition) {
        if (!snapshotService.delete(id)) {
            throw new BusinessError.NotFound("Version introuvable dans cette édition : " + id);
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
            name = "comparer_versions",
            description = "Compare deux plannings, chacun désigné par un id de version ou par « courant » pour le "
                    + "planning persisté. Ne relance aucune résolution et ne recalcule aucun score : lit les mesures déjà "
                    + "prises. Les deux côtés peuvent appartenir à des éditions différentes.",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = true,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    ComparaisonSnapshots compareSnapshots(
            @ToolArg(description = "Côté de référence : id de version ou « courant »") String base,
            @ToolArg(description = "Côté comparé : id de version ou « courant »") String variante,
            @ToolArg(description = EditionArg.DESCRIPTION) @EditionArg String edition) {
        ComparaisonSnapshots comparaison = comparaisonService.comparer(base, variante);
        if (comparaison == null) {
            throw new BusinessError.NotFound("Version introuvable : « " + base + " » ou « " + variante + " »");
        }
        return comparaison;
    }

    static VersionView toView(SnapshotMeta meta) {
        return new VersionView(
                meta.id(),
                meta.libelle(),
                meta.origine().name(),
                meta.score(),
                meta.nombreAffectations(),
                meta.creeLe(),
                meta.editionId(),
                meta.editionNom(),
                meta.kpi(),
                meta.publieLe(),
                meta.referenceModifieLe(),
                meta.perime());
    }

    private static AffectationVersionView toView(AffectationSnapshot affectation) {
        return new AffectationVersionView(
                affectation.posteId(),
                affectation.standId(),
                affectation.creneauId(),
                affectation.animateurId(),
                affectation.heureDebutEffective(),
                affectation.heureFinEffective());
    }

    /**
     * A version without its content — {@code SnapshotMeta} carries no personal
     * field.
     *
     * @param referenceModifieLe last mutation of that snapshot's edition
     *                           referential, {@code null} when none is known
     * @param perime             true when the referential moved after the
     *                           capture (issue #170). Carried here so an
     *                           assistant knows what it is about to propose
     *                           restoring instead of discovering it through a
     *                           refusal
     */
    public record VersionView(
            long id,
            String libelle,
            String origine,
            String score,
            int nombreAffectations,
            Instant creeLe,
            String editionId,
            String editionNom,
            PlanningKpi kpi,
            Instant publieLe,
            Instant referenceModifieLe,
            boolean perime) {}

    /**
     * @param affectationsTotal seats matching the filters, which may exceed the
     *                          number actually returned
     */
    public record VersionDetailView(
            VersionView version, int affectationsTotal, List<AffectationVersionView> affectations) {}

    public record AffectationVersionView(
            String posteId, String standId, String creneauId, String animateurId, String heureDebut, String heureFin) {}

    public record RestaurationView(long versionId, int affectationsRestaurees) {}
}
