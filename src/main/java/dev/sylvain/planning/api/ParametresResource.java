package dev.sylvain.planning.api;

import dev.sylvain.planning.domain.ContactOrganisation;
import dev.sylvain.planning.domain.ParametresLegaux;
import dev.sylvain.planning.domain.ParametresNotifications;
import dev.sylvain.planning.domain.ParametresQualite;
import dev.sylvain.planning.domain.ParametresSolveur;
import dev.sylvain.planning.service.referentiel.ReferenceDataService;
import dev.sylvain.planning.service.solve.SolverBudgetBounds;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

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

    /** The edition's solve budget, with the instance's defaults and ceilings it is read against. */
    @GET
    @Path("/parametres-solveur")
    public SolverSettingsView getParametresSolveur() {
        return SolverSettingsView.of(
                referenceDataService.getParametresSolveur(), referenceDataService.getSolverBudgetBounds());
    }

    /**
     * Saves the edition's solve budget. Returns 400 — citing the ceiling — when
     * a duration or a plateau exceeds what the operator allows, or when the
     * plateau exceeds the duration.
     */
    @PUT
    @Path("/parametres-solveur")
    public SolverSettingsView updateParametresSolveur(ParametresSolveur parametres) {
        return SolverSettingsView.of(
                referenceDataService.updateParametresSolveur(parametres), referenceDataService.getSolverBudgetBounds());
    }

    /**
     * {@link ParametresSolveur} as stored — a {@code null} half follows the
     * deployment — plus {@code instance}, what the operator decided: the
     * defaults a {@code null} resolves to and the ceilings nothing may exceed.
     * Read-only: a {@code PUT} sends {@link ParametresSolveur} and nothing of
     * {@code instance}.
     */
    @Schema(requiredProperties = {"mailFinResolution", "instance"})
    public record SolverSettingsView(
            Integer dureeResolutionSecondes,
            Integer plateauSecondes,
            boolean mailFinResolution,
            SolverBudgetBounds instance) {

        static SolverSettingsView of(ParametresSolveur parametres, SolverBudgetBounds bounds) {
            return new SolverSettingsView(
                    parametres.dureeResolutionSecondes(),
                    parametres.plateauSecondes(),
                    parametres.mailFinResolution(),
                    bounds);
        }
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

    /**
     * The organisation's contact shown in the espace animateur — a phone
     * number and an address, both optional. An edition that never set one
     * answers two {@code null}s.
     */
    @GET
    @Path("/parametres-contact")
    public ContactOrganisation getContactOrganisation() {
        return referenceDataService.getContactOrganisation();
    }

    /** Saves it; 400 with an explanation when a half cannot be a number or an address. */
    @PUT
    @Path("/parametres-contact")
    public ContactOrganisation updateContactOrganisation(ContactOrganisation contact) {
        return referenceDataService.updateContactOrganisation(contact);
    }
}
