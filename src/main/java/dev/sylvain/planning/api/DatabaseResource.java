package dev.sylvain.planning.api;

import dev.sylvain.planning.service.export.DatabaseDumpService;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Test tooling: download and replay the whole dataset as a SQL script, which is
 * how the suites seed and restore a database. No longer offered to operators
 * (backups are the nightly {@code pg_dump}, exchange is the YAML scenario), so
 * both routes answer 404 unless {@code planning.outillage-test.dump-sql} is on —
 * as it is under {@code %test}, {@code %dev} and the E2E stack.
 */
@Path("/database")
public class DatabaseResource {

    private final DatabaseDumpService databaseDumpService;

    private final boolean enabled;

    @Inject
    public DatabaseResource(
            DatabaseDumpService databaseDumpService,
            @ConfigProperty(name = "planning.outillage-test.dump-sql", defaultValue = "false") boolean enabled) {
        this.databaseDumpService = databaseDumpService;
        this.enabled = enabled;
    }

    private void requireEnabled() {
        if (!enabled) {
            throw new NotFoundException();
        }
    }

    @GET
    @Path("/export")
    @Produces("application/sql")
    public Response export() {
        requireEnabled();
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
        requireEnabled();
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
