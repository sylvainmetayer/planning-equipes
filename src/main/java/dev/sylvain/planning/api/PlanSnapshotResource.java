package dev.sylvain.planning.api;

import dev.sylvain.planning.service.solve.PlanSnapshotService;
import dev.sylvain.planning.service.solve.PlanSnapshotService.RestaurationResult;
import dev.sylvain.planning.service.solve.PlanSnapshotService.SnapshotDetail;
import dev.sylvain.planning.service.solve.PlanSnapshotService.SnapshotMeta;
import dev.sylvain.planning.service.solve.SnapshotComparisonService;
import dev.sylvain.planning.service.solve.SnapshotComparisonService.ComparaisonSnapshots;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.time.Instant;
import java.util.List;

/**
 * Plan snapshots (issue #138): the only persistence able to hold more than one
 * plan per edition. Capture is explicit here; the automatic capture before each
 * solve is taken by the solve path itself, not by a caller.
 */
@Path("/planning/snapshots")
@Produces(MediaType.APPLICATION_JSON)
public class PlanSnapshotResource {

    private final PlanSnapshotService snapshotService;

    private final SnapshotComparisonService comparaisonService;

    @Inject
    public PlanSnapshotResource(PlanSnapshotService snapshotService, SnapshotComparisonService comparaisonService) {
        this.snapshotService = snapshotService;
        this.comparaisonService = comparaisonService;
    }

    @GET
    public List<SnapshotMeta> list() {
        return snapshotService.list();
    }

    /**
     * Every edition's snapshots, for the A/B comparator (issue #70) to offer
     * them as sides. Deliberately not edition-scoped, unlike {@link #list()}:
     * since #172 the variant of an edition <b>is</b> another edition, so the
     * pair worth comparing usually straddles two of them. Read-only — nothing
     * here can restore anything.
     */
    @GET
    @Path("/comparables")
    public List<SnapshotMeta> comparables() {
        return snapshotService.listAllEditions();
    }

    /**
     * A/B comparison (issue #70) of two sides, each designated by a snapshot id
     * or by {@code courant} for the currently persisted plan. Reads metrics
     * already measured: <b>no solve is launched and no score is recomputed</b>.
     */
    @GET
    @Path("/compare")
    public ComparaisonSnapshots compare(@QueryParam("base") String base, @QueryParam("variante") String variante) {
        ComparaisonSnapshots comparaison = comparaisonService.comparer(base, variante);
        if (comparaison == null) {
            throw new NotFoundException("Unknown snapshot: " + base + " or " + variante);
        }
        return comparaison;
    }

    @GET
    @Path("/{id}")
    public SnapshotDetail get(@PathParam("id") long id) {
        SnapshotDetail detail = snapshotService.load(id);
        if (detail == null) {
            throw new NotFoundException("Unknown snapshot: " + id);
        }
        return detail;
    }

    /**
     * Captures the currently persisted plan. Answers 409 when there is nothing
     * to capture: an empty snapshot would only be a trap to restore later.
     */
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    public Response capture(CaptureRequest request) {
        String libelle = request == null
                        || request.libelle() == null
                        || request.libelle().isBlank()
                ? "Instantané"
                : request.libelle().trim();
        SnapshotMeta meta = snapshotService.capture(libelle, false);
        if (meta == null) {
            return Response.status(Response.Status.CONFLICT)
                    .entity(new ErreurRestauration("Aucun plan persisté à enregistrer.", List.of(), false, null))
                    .build();
        }
        return Response.status(Response.Status.CREATED).entity(meta).build();
    }

    /**
     * Puts a snapshot back in place of the current plan. Answers 409 with the
     * missing ids when the referential has moved on: restoring half a plan
     * would produce a planning nobody ever computed.
     *
     * <p>Also answers 409 — {@code perime} true, and nothing written — when the
     * referential was merely <b>modified</b> after the capture (issue #170).
     * That refusal is a question the caller answers with {@code ?forcer=true};
     * the missing-references one is not, and no parameter lifts it.</p>
     */
    @POST
    @Path("/{id}/restore")
    @Consumes(MediaType.WILDCARD)
    public Response restore(@PathParam("id") long id, @QueryParam("forcer") boolean forcer) {
        RestaurationResult result = snapshotService.restaurer(id, forcer);
        if (result == null) {
            throw new NotFoundException("Unknown snapshot: " + id);
        }
        if (result.perime()) {
            return Response.status(Response.Status.CONFLICT)
                    .entity(new ErreurRestauration(
                            "Le référentiel a été modifié depuis cette capture : l'instantané ne décrit plus les "
                                    + "données actuelles. Rien n'a été restauré.",
                            List.of(),
                            true,
                            result.referenceModifieLe()))
                    .build();
        }
        if (!result.restaure()) {
            return Response.status(Response.Status.CONFLICT)
                    .entity(new ErreurRestauration(
                            "L'instantané référence des données qui n'existent plus : rien n'a été restauré.",
                            result.referencesManquantes(),
                            false,
                            null))
                    .build();
        }
        return Response.ok(result).build();
    }

    @DELETE
    @Path("/{id}")
    public Response delete(@PathParam("id") long id) {
        if (!snapshotService.delete(id)) {
            throw new NotFoundException("Unknown snapshot: " + id);
        }
        return Response.noContent().build();
    }

    /** @param libelle free-text name; a blank one falls back to a generic label */
    public record CaptureRequest(String libelle) {}

    /**
     * Body of a refused restore or capture.
     *
     * @param referencesManquantes ids the snapshot names and the referential
     *                             lost — empty on every other refusal
     * @param perime               true when the refusal is the staleness guard
     *                             (issue #170), the one a caller may retry with
     *                             {@code ?forcer=true}
     * @param referenceModifieLe   when the referential was last written,
     *                             {@code null} outside that refusal: it is what
     *                             lets the screen say <i>since when</i> rather
     *                             than just <i>no</i>
     */
    public record ErreurRestauration(
            String message, List<String> referencesManquantes, boolean perime, Instant referenceModifieLe) {}
}
