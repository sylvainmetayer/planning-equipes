package dev.sylvain.planning.api;

import dev.sylvain.planning.service.analyse.PlanningHoursReader;
import dev.sylvain.planning.service.analyse.PlanningHoursReader.HoursReading;
import dev.sylvain.planning.service.analyse.PlanningHoursReader.Source;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * {@code GET /api/planning/hours}: the hours report of a plan the server holds
 * — {@code ?source=publie}, the publication in force, or {@code
 * ?source=persiste}, the persisted plan; without it, the publication when there
 * is one. {@code …/export} is the same report as the payroll CSV, its first
 * line naming the plan and its date. Never computed on a plan the client sends.
 */
@Path("/planning/hours")
public class PlanningHoursResource {

    private final PlanningHoursReader reader;

    @Inject
    public PlanningHoursResource(PlanningHoursReader reader) {
        this.reader = reader;
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public HoursReading report(@QueryParam("source") @Schema(enumeration = {"persiste", "publie"}) String source) {
        return reader.read(Source.parse(source).orElse(null));
    }

    @GET
    @Path("/export")
    @Produces("text/csv")
    public Response exportCsv(@QueryParam("source") @Schema(enumeration = {"persiste", "publie"}) String source) {
        HoursReading reading = reader.read(Source.parse(source).orElse(null));
        return CsvDownload.attachment(reader.csv(reading), "heures-planning.csv");
    }
}
