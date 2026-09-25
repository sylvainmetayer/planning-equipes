package dev.sylvain.planning.api;

import dev.sylvain.planning.service.export.DatabaseDumpService;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Download and replay the whole dataset as a SQL script, so a problematic
 * dataset can be shared and reloaded when analysing a planning.
 */
@Path("/database")
public class DatabaseResource {

    private final DatabaseDumpService databaseDumpService;

    @Inject
    public DatabaseResource(DatabaseDumpService databaseDumpService) {
        this.databaseDumpService = databaseDumpService;
    }

    @GET
    @Path("/export")
    @Produces("application/sql")
    public Response export() {
        String dump = databaseDumpService.exportDump();
        String filename = "planning-equipes-"
                + LocalDate.now(ZoneId.systemDefault()).format(DateTimeFormatter.ISO_LOCAL_DATE) + ".sql";
        return Response.ok(dump)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .build();
    }

    @POST
    @Path("/import")
    @Consumes(MediaType.WILDCARD)
    @Produces(MediaType.APPLICATION_JSON)
    public Response importDump(String script) {
        try {
            int statements = databaseDumpService.importDump(script);
            return Response.ok(
                            new ImportSummary(statements, "Import terminé : " + statements + " instructions rejouées."))
                    .build();
        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(new ImportSummary(0, e.getMessage()))
                    .build();
        }
    }

    public record ImportSummary(int statements, String message) {}
}
