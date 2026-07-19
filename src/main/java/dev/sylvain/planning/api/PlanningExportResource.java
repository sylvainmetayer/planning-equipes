package dev.sylvain.planning.api;

import dev.sylvain.planning.domain.PlanningFestival;
import dev.sylvain.planning.service.PlanningExportService;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@Path("/api/planning/export")
@Consumes(MediaType.APPLICATION_JSON)
public class PlanningExportResource {

    @Inject
    PlanningExportService planningExportService;

    @POST
    @Path("/pdf/global")
    @Produces("application/pdf")
    public Response exportGlobalPdf(PlanningFestival planningFestival) {
        byte[] content = planningExportService.exportGlobalPdf(planningFestival);
        return Response.ok(content)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"planning-global.pdf\"")
                .build();
    }

    @POST
    @Path("/pdf/animateur/{animateurId}")
    @Produces("application/pdf")
    public Response exportAnimateurPdf(@PathParam("animateurId") String animateurId, PlanningFestival planningFestival) {
        byte[] content = planningExportService.exportAnimateurPdf(planningFestival, animateurId);
        String safeAnimateurId = (animateurId == null ? "unknown" : animateurId).replaceAll("[\\\\/\\r\\n\\\"]", "_");
        return Response.ok(content)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"planning-" + safeAnimateurId + ".pdf\"")
                .build();
    }

    @POST
    @Path("/ics/animateur/{animateurId}")
    @Produces("text/calendar")
    public Response exportAnimateurIcs(@PathParam("animateurId") String animateurId, PlanningFestival planningFestival) {
        String content = planningExportService.exportAnimateurIcs(planningFestival, animateurId);
        String displayName = planningExportService.resolveAnimateurName(planningFestival, animateurId);
        String safeFilename = (displayName == null ? "planning" : displayName).replaceAll("[\\\\/\\r\\n\\\"]", "_");
        return Response.ok(content)
                .type("text/calendar; charset=utf-8")
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + safeFilename + ".ics\"")
                .build();
    }
}
