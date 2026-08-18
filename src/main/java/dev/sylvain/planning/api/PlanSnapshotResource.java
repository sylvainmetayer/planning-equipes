package dev.sylvain.planning.api;

import java.util.List;

import dev.sylvain.planning.service.PlanSnapshotService;
import dev.sylvain.planning.service.PlanSnapshotService.RestaurationResult;
import dev.sylvain.planning.service.PlanSnapshotService.SnapshotDetail;
import dev.sylvain.planning.service.PlanSnapshotService.SnapshotMeta;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * Plan snapshots (issue #138): the only persistence able to hold more than one
 * plan per edition. Capture is explicit here; the automatic capture before each
 * solve is taken by the solve path itself, not by a caller.
 */
@Path("/planning/snapshots")
@Produces(MediaType.APPLICATION_JSON)
public class PlanSnapshotResource {

    @Inject
    PlanSnapshotService snapshotService;

    @GET
    public List<SnapshotMeta> list() {
        return snapshotService.lister();
    }

    @GET
    @Path("/{id}")
    public SnapshotDetail get(@PathParam("id") long id) {
        SnapshotDetail detail = snapshotService.charger(id);
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
        String libelle = request == null || request.libelle() == null || request.libelle().isBlank()
                ? "Instantané"
                : request.libelle().trim();
        SnapshotMeta meta = snapshotService.capturer(libelle, false);
        if (meta == null) {
            return Response.status(Response.Status.CONFLICT)
                    .entity(new ErreurRestauration("Aucun plan persisté à enregistrer.", List.of()))
                    .build();
        }
        return Response.status(Response.Status.CREATED).entity(meta).build();
    }

    /**
     * Puts a snapshot back in place of the current plan. Answers 409 with the
     * missing ids when the referential has moved on: restoring half a plan
     * would produce a planning nobody ever computed.
     */
    @POST
    @Path("/{id}/restore")
    @Consumes(MediaType.WILDCARD)
    public Response restore(@PathParam("id") long id) {
        RestaurationResult result = snapshotService.restaurer(id);
        if (result == null) {
            throw new NotFoundException("Unknown snapshot: " + id);
        }
        if (!result.restaure()) {
            return Response.status(Response.Status.CONFLICT)
                    .entity(new ErreurRestauration(
                            "L'instantané référence des données qui n'existent plus : rien n'a été restauré.",
                            result.referencesManquantes()))
                    .build();
        }
        return Response.ok(result).build();
    }

    @DELETE
    @Path("/{id}")
    public Response delete(@PathParam("id") long id) {
        if (!snapshotService.supprimer(id)) {
            throw new NotFoundException("Unknown snapshot: " + id);
        }
        return Response.noContent().build();
    }

    /** @param libelle free-text name; a blank one falls back to a generic label */
    public record CaptureRequest(String libelle) {
    }

    public record ErreurRestauration(String message, List<String> referencesManquantes) {
    }
}
