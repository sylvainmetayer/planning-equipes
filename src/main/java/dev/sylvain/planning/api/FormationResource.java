package dev.sylvain.planning.api;

import dev.sylvain.planning.service.analyse.FormationAnalyzer;
import dev.sylvain.planning.service.analyse.FormationAnalyzer.PlanFormation;
import dev.sylvain.planning.service.analyse.FormationService;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * Who to train, game category by game category — the « À former » tab of the
 * Diagnostic. Read-only, <b>no solve involved</b>: the shortage is the one the
 * Besoin and Fragilité tabs already report, and the candidates are read from
 * the competence referential.
 *
 * <p>{@code GET /api/formation/export} is the same tab as a CSV, names
 * included, like the Équité export.</p>
 */
@Path("/formation")
public class FormationResource {

    @Inject
    FormationService formationService;

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public PlanFormation plan() {
        return formationService.plan();
    }

    @GET
    @Path("/export")
    @Produces("text/csv")
    public Response exportCsv() {
        return CsvDownload.attachment(FormationAnalyzer.generateCsv(formationService.plan()), "plan-formation.csv");
    }
}
