package dev.sylvain.planning.api;

import dev.sylvain.planning.service.CsvImportService;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * CSV import of the reference data. Each call overwrites the whole matching
 * dataset, so a test dataset can be loaded from a spreadsheet export.
 */
@Path("/api/import/csv")
public class CsvImportResource {

    @Inject
    CsvImportService csvImportService;

    @POST
    @Path("/{entity}")
    @Consumes(MediaType.WILDCARD)
    @Produces(MediaType.APPLICATION_JSON)
    public Response importCsv(@PathParam("entity") String entity, String csv) {
        try {
            int imported = switch (entity == null ? "" : entity.toLowerCase()) {
                case "animateurs" -> csvImportService.importAnimateurs(csv);
                case "stands" -> csvImportService.importStands(csv);
                case "creneaux" -> csvImportService.importCreneaux(csv);
                default -> throw new UnknownEntityException(entity);
            };
            return Response.ok(new ImportSummary(entity, imported,
                    imported + " " + entity + " importés (données précédentes remplacées).")).build();
        } catch (UnknownEntityException e) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(new ImportSummary(entity, 0, e.getMessage()))
                    .build();
        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(new ImportSummary(entity, 0, e.getMessage()))
                    .build();
        }
    }

    public record ImportSummary(String entity, int imported, String message) {
    }

    private static class UnknownEntityException extends RuntimeException {
        UnknownEntityException(String entity) {
            super("Unknown CSV entity: " + entity + " (expected animateurs, stands or creneaux)");
        }
    }
}
