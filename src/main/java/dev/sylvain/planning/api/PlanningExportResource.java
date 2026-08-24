package dev.sylvain.planning.api;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.service.PlanningExportService;
import dev.sylvain.planning.service.PlanningPersistenceService;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

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
    @jakarta.ws.rs.Path("/pdf/global")
    @Produces("application/pdf")
    public Response exportGlobalPdf() {
        byte[] content = planningExportService.exportGlobalPdf(persistenceService.loadPersistedPlanning());
        String filename = "planning-global-" + LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE) + ".pdf";
        return Response.ok(content)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .build();
    }

    @POST
    @Path("/pdf/all")
    @Produces("application/zip")
    public Response exportAllPdfZip(PlanningEvenement planningEvenement) {
        byte[] content = planningExportService.exportAllPdfZip(planningEvenement);
        return Response.ok(content)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"planning-pdf.zip\"")
                .build();
    }

    @POST
    @Path("/pdf/animateur/{animateurId}")
    @Produces("application/pdf")
    public Response exportAnimateurPdf(@PathParam("animateurId") String animateurId, PlanningEvenement planningEvenement) {
        byte[] content = planningExportService.exportAnimateurPdf(planningEvenement, animateurId);
        String safeAnimateurId = (animateurId == null ? "unknown" : animateurId).replaceAll("[\\\\/\\r\\n\\\"]", "_");
        return Response.ok(content)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"planning-" + safeAnimateurId + ".pdf\"")
                .build();
    }

    @POST
    @Path("/bundle/all")
    @Produces("application/zip")
    public Response exportAllBundleZip(PlanningEvenement planningEvenement) {
        byte[] content = planningExportService.exportAllBundleZip(planningEvenement);
        return Response.ok(content)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"planning.zip\"")
                .build();
    }

    @POST
    @Path("/ics/all")
    @Produces("application/zip")
    public Response exportAllIcsZip(PlanningEvenement planningEvenement) {
        byte[] content = planningExportService.exportAllIcsZip(planningEvenement);
        return Response.ok(content)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"planning-ics.zip\"")
                .build();
    }

    @POST
    @Path("/ics/animateur/{animateurId}")
    @Produces("text/calendar")
    public Response exportAnimateurIcs(@PathParam("animateurId") String animateurId, PlanningEvenement planningEvenement) {
        String content = planningExportService.exportAnimateurIcs(planningEvenement, animateurId);
        String displayName = planningExportService.resolveAnimateurName(planningEvenement, animateurId);
        String safeFilename = (displayName == null ? "planning" : displayName).replaceAll("[\\\\/\\r\\n\\\"]", "_");
        return Response.ok(content)
                .type("text/calendar; charset=utf-8")
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + safeFilename + ".ics\"")
                .build();
    }
}
