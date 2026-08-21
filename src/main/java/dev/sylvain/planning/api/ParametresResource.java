package dev.sylvain.planning.api;

import dev.sylvain.planning.domain.ParametresDecoupage;
import dev.sylvain.planning.domain.ParametresLegaux;
import dev.sylvain.planning.domain.ParametresSolveur;
import dev.sylvain.planning.service.ReferenceDataService;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * Les trois jeux de paramètres réglables depuis les onglets Données et
 * Débogage. Trois racines d'URL distinctes et publiées, d'où le {@code @Path}
 * de classe à la racine : les regrouper sous un préfixe commun casserait le
 * frontend et les clients MCP pour un gain purement cosmétique.
 */
@Path("/")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ParametresResource {

    @Inject
    ReferenceDataService referenceDataService;

    @GET
    @Path("/parametres-legaux")
    public ParametresLegaux getParametresLegaux() {
        return referenceDataService.getParametresLegaux();
    }

    /**
     * Saves the legal parameters. Returns 400 with an explanation when a value
     * exceeds its ordre public ceiling (48 h for adults, art. L3121-20; 35 h
     * for minors, art. L3162-1) rather than letting the exception surface as a
     * 500 — the message is shown as-is to the administrator.
     */
    @PUT
    @Path("/parametres-legaux")
    public Response updateParametresLegaux(ParametresLegaux parametres) {
        return Response.ok(referenceDataService.updateParametresLegaux(parametres)).build();
    }

    @GET
    @Path("/parametres-decoupage")
    public ParametresDecoupage getParametresDecoupage() {
        return referenceDataService.getParametresDecoupage();
    }

    @PUT
    @Path("/parametres-decoupage")
    public ParametresDecoupage updateParametresDecoupage(ParametresDecoupage parametres) {
        return referenceDataService.updateParametresDecoupage(parametres);
    }

    @GET
    @Path("/parametres-solveur")
    public ParametresSolveur getParametresSolveur() {
        return referenceDataService.getParametresSolveur();
    }

    /** Saves the solver's default termination duration (Données tab). Returns 400 when the value isn't positive. */
    @PUT
    @Path("/parametres-solveur")
    public Response updateParametresSolveur(ParametresSolveur parametres) {
        return Response.ok(referenceDataService.updateParametresSolveur(parametres)).build();
    }
}
