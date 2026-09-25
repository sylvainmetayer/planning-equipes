package dev.sylvain.planning.api;

import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.service.export.FormatPlanning;
import dev.sylvain.planning.service.export.PlanningExportService;
import dev.sylvain.planning.service.solve.PlanningPersistenceService;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

@Path("/planning/export")
@Consumes(MediaType.APPLICATION_JSON)
public class PlanningExportResource {

    @Inject
    PlanningExportService planningExportService;

    @Inject
    PlanningPersistenceService persistenceService;

    /**
     * The whole planning in one PDF, for the organiser — the only export that
     * reads the persisted planning server-side instead of taking it in the
     * request body: an event-sized planning weighs several megabytes as JSON,
     * which is exactly what the caller should not have to upload just to get a
     * document back. Same read-only source as the calendars
     * ({@code GET /api/planning/persisted}).
     */
    @GET
    @Path("/pdf/global")
    @Produces("application/pdf")
    public Response exportGlobalPdf() {
        byte[] content = planningExportService.exportGlobalPdf(persistenceService.loadPersistedPlanning());
        String filename = "planning-global-"
                + LocalDate.now(ZoneId.systemDefault()).format(DateTimeFormatter.ISO_LOCAL_DATE) + ".pdf";
        return Response.ok(content)
                .header(HttpHeaders.CONTENT_DISPOSITION, attachment(filename))
                .build();
    }

    /**
     * One PDF per animateur in a ZIP, in the layout asked for: {@code livret}
     * (the default) or {@code feuille} — the folded landscape sheet, one page
     * per person, which is what a mass print run wants.
     */
    @POST
    @Path("/pdf/all")
    @Produces("application/zip")
    public Response exportAllPdfZip(@QueryParam("format") String format, PlanningEvenement planningEvenement) {
        FormatPlanning layout = FormatPlanning.fromParameter(format);
        byte[] content = planningExportService.exportAllPdfZip(planningEvenement, layout);
        String filename = layout == FormatPlanning.FEUILLE ? "planning-feuilles.zip" : "planning-pdf.zip";
        return Response.ok(content)
                .header(HttpHeaders.CONTENT_DISPOSITION, attachment(filename))
                .build();
    }

    @POST
    @Path("/pdf/animateur/{animateurId}")
    @Produces("application/pdf")
    public Response exportAnimateurPdf(
            @PathParam("animateurId") String animateurId,
            @QueryParam("format") String format,
            PlanningEvenement planningEvenement) {
        FormatPlanning layout = FormatPlanning.fromParameter(format);
        byte[] content = planningExportService.exportAnimateurPdf(planningEvenement, animateurId, layout);
        String safeAnimateurId = (animateurId == null ? "unknown" : animateurId).replaceAll("[\\\\/\\r\\n\\\"]", "_");
        // The layout is part of the name: downloading both would otherwise
        // leave one file, the second having overwritten the first.
        String filename = layout == FormatPlanning.FEUILLE
                ? "planning-" + safeAnimateurId + "-feuille.pdf"
                : "planning-" + safeAnimateurId + ".pdf";
        return Response.ok(content)
                .header(HttpHeaders.CONTENT_DISPOSITION, attachment(filename))
                .build();
    }

    @POST
    @Path("/bundle/all")
    @Produces("application/zip")
    public Response exportAllBundleZip(@QueryParam("format") String format, PlanningEvenement planningEvenement) {
        FormatPlanning layout = FormatPlanning.fromParameter(format);
        byte[] content = planningExportService.exportAllBundleZip(planningEvenement, layout);
        String filename = layout == FormatPlanning.FEUILLE ? "planning-feuilles.zip" : "planning.zip";
        return Response.ok(content)
                .header(HttpHeaders.CONTENT_DISPOSITION, attachment(filename))
                .build();
    }

    @POST
    @Path("/ics/all")
    @Produces("application/zip")
    public Response exportAllIcsZip(PlanningEvenement planningEvenement) {
        byte[] content = planningExportService.exportAllIcsZip(planningEvenement);
        return Response.ok(content)
                .header(HttpHeaders.CONTENT_DISPOSITION, attachment("planning-ics.zip"))
                .build();
    }

    @POST
    @Path("/ics/animateur/{animateurId}")
    @Produces("text/calendar")
    public Response exportAnimateurIcs(
            @PathParam("animateurId") String animateurId, PlanningEvenement planningEvenement) {
        String content = planningExportService.exportAnimateurIcs(planningEvenement, animateurId);
        String displayName = PlanningExportService.resolveAnimateurName(planningEvenement, animateurId);
        String safeFilename = (displayName == null ? "planning" : displayName).replaceAll("[\\\\/\\r\\n\\\"]", "_");
        return Response.ok(content)
                .type("text/calendar; charset=utf-8")
                .header(HttpHeaders.CONTENT_DISPOSITION, attachment(safeFilename + ".ics"))
                .build();
    }

    private static String attachment(String filename) {
        return "attachment; filename=\"" + filename + "\"";
    }
}
