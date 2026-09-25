package dev.sylvain.planning.api;

import dev.sylvain.planning.service.analyse.EquiteService;
import dev.sylvain.planning.service.analyse.EquiteService.RapportEquite;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * {@code GET /api/planning/equite}: the equity table of the persisted plan —
 * one line per animateur holding a seat, and per column the median, min, max
 * and standard deviation. A read-out under the organiser's <em>current</em>
 * legal parameters (the evening starts where they say today), never a solve;
 * an empty table rather than an error when nothing is persisted yet.
 *
 * <p>{@code GET /api/planning/equite/export} is the same table as the CSV the
 * Hours screen already exports: {@code ;}, decimal comma, byte order mark.</p>
 */
@Path("/planning/equite")
public class EquiteResource {

    private final EquiteService equiteService;

    @Inject
    public EquiteResource(EquiteService equiteService) {
        this.equiteService = equiteService;
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public RapportEquite rapport() {
        return equiteService.rapport();
    }

    @GET
    @Path("/export")
    @Produces("text/csv")
    public Response exportCsv() {
        return CsvDownload.attachment(EquiteService.generateCsv(equiteService.rapport()), "equite-planning.csv");
    }
}
