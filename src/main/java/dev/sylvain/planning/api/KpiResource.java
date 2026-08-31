package dev.sylvain.planning.api;

import java.util.List;

import dev.sylvain.planning.service.KpiHistoriqueService;
import dev.sylvain.planning.service.KpiHistoriqueService.KpiHistoriqueEntry;
import jakarta.inject.Inject;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * KPI history (issue #89). Unlike every other resource, the listing spans
 * <b>all</b> editions: comparing 2025 to 2026 is the point, and a row must
 * survive the deletion of the edition it describes (its labels are
 * denormalised for exactly that). Rows are written by the solve jobs
 * themselves, never through this API.
 *
 * <p>The deletion follows the listing: it takes an id, not an edition, because
 * the row deleted is one of those the caller has just been shown — including
 * the rows of an edition that no longer exists, which nothing else could ever
 * clean up.</p>
 */
@Path("/kpi/historique")
@Produces(MediaType.APPLICATION_JSON)
public class KpiResource {

    @Inject
    KpiHistoriqueService kpiHistoriqueService;

    @GET
    public List<KpiHistoriqueEntry> list() {
        return kpiHistoriqueService.list();
    }

    @DELETE
    @Path("/{id}")
    public Response delete(@PathParam("id") long id) {
        if (!kpiHistoriqueService.delete(id)) {
            throw new NotFoundException("Unknown KPI history row: " + id);
        }
        return Response.noContent().build();
    }
}
