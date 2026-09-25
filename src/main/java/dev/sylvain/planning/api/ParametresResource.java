package dev.sylvain.planning.api;

import dev.sylvain.planning.domain.ParametresLegaux;
import dev.sylvain.planning.domain.ParametresNotifications;
import dev.sylvain.planning.domain.ParametresQualite;
import dev.sylvain.planning.domain.ParametresSolveur;
import dev.sylvain.planning.service.referentiel.ReferenceDataService;
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

    private final ReferenceDataService referenceDataService;

    @Inject
    public ParametresResource(ReferenceDataService referenceDataService) {
        this.referenceDataService = referenceDataService;
    }

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
        return Response.ok(referenceDataService.updateParametresLegaux(parametres))
                .build();
    }

    /**
     * The organisational-quality thresholds of this edition (issue #591): how
     * many emplacements and how many typologies one animateur may spread over,
     * and what a late closing followed by an early opening is. An edition that
     * has never been configured answers the deployment's own
     * {@code planning.contraintes.*} values.
     */
    @GET
    @Path("/parametres-qualite")
    public ParametresQualite getParametresQualite() {
        return referenceDataService.getParametresQualite();
    }

    /** Saves them. Returns 400 with an explanation when a threshold could not mean anything. */
    @PUT
    @Path("/parametres-qualite")
    public Response updateParametresQualite(ParametresQualite parametres) {
        return Response.ok(referenceDataService.updateParametresQualite(parametres))
                .build();
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
        return Response.ok(referenceDataService.updateParametresSolveur(parametres))
                .build();
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
        return Response.ok(referenceDataService.updateParametresNotifications(parametres))
                .build();
    }
}
