package dev.sylvain.planning.api;

import dev.sylvain.planning.domain.ParametresDecoupage;
import dev.sylvain.planning.domain.ParametresLegaux;
import dev.sylvain.planning.domain.ParametresNotifications;
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
 * The parameter sets the Données and Débogage tabs can tune. Several
 * distinct published URL roots, hence the class-level {@code @Path} at the
 * root: grouping them under a common prefix would break the frontend and every
 * MCP client for a purely cosmetic gain.
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

    /**
     * Writes the slicing settings. {@code modeGrille} is <b>not</b> read from
     * this body — it is declared on the Créneaux page and written by
     * {@link #updateModeGrille}, so a tab left open here cannot revert it.
     */
    @PUT
    @Path("/parametres-decoupage")
    public ParametresDecoupage updateParametresDecoupage(ParametresDecoupage parametres) {
        return referenceDataService.updateParametresDecoupage(parametres);
    }

    /** What the edition's créneaux are: {@code {"modeGrille": "AMPLITUDES"}} or {@code "VACATIONS"}. */
    public record ModeGrilleRequest(String modeGrille) {
    }

    @PUT
    @Path("/parametres-decoupage/mode-grille")
    public ParametresDecoupage updateModeGrille(ModeGrilleRequest requete) {
        return referenceDataService.updateModeGrille(requete == null ? null : requete.modeGrille());
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

    /**
     * What the scheduled notifications may do on this edition (issues #298,
     * #299, #300). An edition that has never been configured answers the
     * defaults — {@code actives} false above all: nothing leaves an edition
     * nobody armed.
     */
    @GET
    @Path("/parametres-notifications")
    public ParametresNotifications getParametresNotifications() {
        return referenceDataService.getParametresNotifications();
    }

    @PUT
    @Path("/parametres-notifications")
    public Response updateParametresNotifications(ParametresNotifications parametres) {
        return Response.ok(referenceDataService.updateParametresNotifications(parametres)).build();
    }
}
