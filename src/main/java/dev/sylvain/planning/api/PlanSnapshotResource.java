package dev.sylvain.planning.api;

import dev.sylvain.planning.api.VersionPlanResource.CaptureRequest;
import dev.sylvain.planning.service.solve.PlanSnapshotService.SnapshotDetail;
import dev.sylvain.planning.service.solve.PlanSnapshotService.SnapshotMeta;
import dev.sylvain.planning.service.solve.SnapshotComparisonService.ComparaisonSnapshots;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.List;

/**
 * The 3.x root of the plan versions, when they were called snapshots: every
 * route answers as {@code /planning/versions} does (ADR 0108).
 *
 * @deprecated alias of {@link VersionPlanResource}, removed in 5.0
 */
@Deprecated
@Path("/planning/snapshots")
@Produces(MediaType.APPLICATION_JSON)
public class PlanSnapshotResource {

    private final VersionPlanResource versions;

    @Inject
    public PlanSnapshotResource(VersionPlanResource versions) {
        this.versions = versions;
    }

    @GET
    public List<SnapshotMeta> list() {
        return versions.list();
    }

    @GET
    @Path("/comparables")
    public List<SnapshotMeta> comparables() {
        return versions.comparables();
    }

    @GET
    @Path("/compare")
    public ComparaisonSnapshots compare(@QueryParam("base") String base, @QueryParam("variante") String variante) {
        return versions.compare(base, variante);
    }

    @GET
    @Path("/{id}")
    public SnapshotDetail get(@PathParam("id") long id) {
        return versions.get(id);
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    public Response capture(CaptureRequest request) {
        return versions.capture(request);
    }

    @POST
    @Path("/{id}/restore")
    @Consumes(MediaType.WILDCARD)
    public Response restore(@PathParam("id") long id, @QueryParam("forcer") boolean forcer) {
        return versions.restore(id, forcer);
    }

    @DELETE
    @Path("/{id}")
    public Response delete(@PathParam("id") long id) {
        return versions.delete(id);
    }
}
