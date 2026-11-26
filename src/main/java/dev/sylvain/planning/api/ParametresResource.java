package dev.sylvain.planning.api;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import dev.sylvain.planning.domain.ContactOrganisation;
import dev.sylvain.planning.domain.ParametresLegaux;
import dev.sylvain.planning.domain.ParametresNotifications;
import dev.sylvain.planning.domain.ParametresQualite;
import dev.sylvain.planning.domain.ParametresSolveur;
import dev.sylvain.planning.service.BusinessError;
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
import java.time.Instant;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.RequestBody;

/**
 * The edition's parameters, in four sections — {@code legal}, {@code organisation},
 * {@code notifications}, {@code calcul} — under {@code /parametres}. The
 * former roots ({@code /parametres-legaux}, {@code -qualite}, {@code -solveur},
 * {@code -notifications}) stay as deprecated aliases that answer the same thing,
 * for the screens and clients of the 3.x; they are removed in 5.0.
 * {@code /parametres-contact} is not a section and stays as it is, and so does
 * the weather alert, which has its own page.
 *
 * <p>A section's {@code PUT} carries the {@code modifieLe} its {@code GET} gave:
 * a section written since is a 409 {@code MODIFICATION_CONCURRENTE}, and a body
 * without it checks nothing. The deprecated aliases keep their 3.x behaviour —
 * last writer wins — so they drop the stamp even when a client sends one.</p>
 */
@Path("/")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ParametresResource {

    private final ReferenceDataService referenceDataService;

    private final ObjectMapper objectMapper;

    @Inject
    public ParametresResource(ReferenceDataService referenceDataService, ObjectMapper objectMapper) {
        this.referenceDataService = referenceDataService;
        this.objectMapper = objectMapper;
    }

    /**
     * A section's body, read strictly: a field that is not of {@code section}
     * is a 400 naming it, as {@code modifier_parametres} refuses it over MCP,
     * rather than a write that silently ignores it. The 3.x aliases keep the
     * lenient reading their clients were written against.
     */
    private <T> T section(JsonNode body, Class<T> type, String section) {
        try {
            return objectMapper
                    .readerFor(type)
                    .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .readValue(body == null ? objectMapper.nullNode() : body);
        } catch (UnrecognizedPropertyException e) {
            throw new BusinessError.Invalid("Le champ « " + e.getPropertyName() + " » n'est pas de la section « "
                    + section + " » : passez par la section qui le porte.");
        } catch (java.io.IOException e) {
            throw new BusinessError.Invalid("Corps illisible pour la section « " + section + " ».");
        }
    }

    /** The four sections at once. */
    @GET
    @Path("/parametres")
    public ParametresView getParametres() {
        return new ParametresView(getLegal(), getOrganisation(), getNotifications(), getCalcul());
    }

    /**
     * The sections as one answer; {@code calcul} is the end-of-solve mail with
     * the instance's solve budget, as {@link #getCalcul()} gives it.
     */
    @Schema(requiredProperties = {"legal", "organisation", "notifications", "calcul"})
    public record ParametresView(
            ParametresLegaux legal,
            ParametresQualite organisation,
            ParametresNotifications notifications,
            SolverSettingsView calcul) {}

    @GET
    @Path("/parametres/legal")
    public ParametresLegaux getLegal() {
        return referenceDataService.getParametresLegaux();
    }

    /**
     * Saves the legal parameters. Returns 400 with an explanation when a value
     * exceeds its ordre public ceiling (48 h for adults, art. L3121-20; 35 h
     * for minors, art. L3162-1) rather than letting the exception surface as a
     * 500 — the message is shown as-is to the administrator.
     */
    @PUT
    @Path("/parametres/legal")
    public Response updateLegal(
            @RequestBody(required = true, content = @Content(schema = @Schema(implementation = ParametresLegaux.class)))
                    JsonNode body) {
        return writeLegaux(section(body, ParametresLegaux.class, "legal"));
    }

    private Response writeLegaux(ParametresLegaux parametres) {
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
    @Path("/parametres/organisation")
    public ParametresQualite getOrganisation() {
        return referenceDataService.getParametresQualite();
    }

    /** Saves them. Returns 400 with an explanation when a threshold could not mean anything. */
    @PUT
    @Path("/parametres/organisation")
    public Response updateOrganisation(
            @RequestBody(
                            required = true,
                            content = @Content(schema = @Schema(implementation = ParametresQualite.class)))
                    JsonNode body) {
        return writeQualite(section(body, ParametresQualite.class, "organisation"));
    }

    private Response writeQualite(ParametresQualite parametres) {
        return Response.ok(referenceDataService.updateParametresQualite(parametres))
                .build();
    }

    /**
     * The delays of the scheduled notifications on this edition (issues #298,
     * #299, #300). An edition that has never been configured answers the
     * defaults. Whether anything leaves is not decided here: only the active
     * edition sends (ADR 0072).
     */
    @GET
    @Path("/parametres/notifications")
    public ParametresNotifications getNotifications() {
        return referenceDataService.getParametresNotifications();
    }

    @PUT
    @Path("/parametres/notifications")
    public Response updateNotifications(
            @RequestBody(
                            required = true,
                            content = @Content(schema = @Schema(implementation = ParametresNotifications.class)))
                    JsonNode body) {
        return writeNotifications(section(body, ParametresNotifications.class, "notifications"));
    }

    private Response writeNotifications(ParametresNotifications parametres) {
        return Response.ok(referenceDataService.updateParametresNotifications(parametres))
                .build();
    }

    /**
     * What an edition decides about its solves — the end-of-solve mail — beside
     * the instance's solve budget, which the operator sets and nothing here
     * writes (ADR 0110).
     */
    @GET
    @Path("/parametres/calcul")
    public SolverSettingsView getCalcul() {
        return SolverSettingsView.of(
                referenceDataService.getParametresSolveur(), referenceDataService.getSolverBudgetBounds());
    }

    /** Saves the edition's end-of-solve mail switch. */
    @PUT
    @Path("/parametres/calcul")
    public SolverSettingsView updateCalcul(
            @RequestBody(
                            required = true,
                            content = @Content(schema = @Schema(implementation = ParametresSolveur.class)))
                    JsonNode body) {
        return writeSolveur(section(body, ParametresSolveur.class, "calcul"));
    }

    private SolverSettingsView writeSolveur(ParametresSolveur parametres) {
        return SolverSettingsView.of(
                referenceDataService.updateParametresSolveur(parametres), referenceDataService.getSolverBudgetBounds());
    }

    /**
     * {@link ParametresSolveur} as stored plus {@code instance}, what the
     * operator decided: the solve budget and the ceiling nothing may exceed.
     * Read-only: a {@code PUT} sends {@link ParametresSolveur} and nothing of
     * {@code instance}. {@code modifieLe} is the stamp to send back with the write.
     */
    @Schema(requiredProperties = {"mailFinResolution", "instance"})
    public record SolverSettingsView(boolean mailFinResolution, SolverBudgetBounds instance, Instant modifieLe) {

        static SolverSettingsView of(ParametresSolveur parametres, SolverBudgetBounds bounds) {
            return new SolverSettingsView(parametres.mailFinResolution(), bounds, parametres.modifieLe());
        }
    }

    /* ----- The 3.x roots: aliases of the sections above, removed in 5.0 ----- */

    /** @deprecated alias of {@code /parametres/legal}, removed in 5.0 */
    @Deprecated
    @GET
    @Path("/parametres-legaux")
    public ParametresLegaux getParametresLegaux() {
        return getLegal();
    }

    /** @deprecated alias of {@code /parametres/legal}, removed in 5.0 */
    @Deprecated
    @PUT
    @Path("/parametres-legaux")
    public Response updateParametresLegaux(ParametresLegaux parametres) {
        parametres.setModifieLe(null);
        return writeLegaux(parametres);
    }

    /** @deprecated alias of {@code /parametres/organisation}, removed in 5.0 */
    @Deprecated
    @GET
    @Path("/parametres-qualite")
    public ParametresQualite getParametresQualite() {
        return getOrganisation();
    }

    /** @deprecated alias of {@code /parametres/organisation}, removed in 5.0 */
    @Deprecated
    @PUT
    @Path("/parametres-qualite")
    public Response updateParametresQualite(ParametresQualite parametres) {
        return writeQualite(parametres.withStamp(null));
    }

    /** @deprecated alias of {@code /parametres/calcul}, removed in 5.0 */
    @Deprecated
    @GET
    @Path("/parametres-solveur")
    public SolverSettingsView getParametresSolveur() {
        return getCalcul();
    }

    /** @deprecated alias of {@code /parametres/calcul}, removed in 5.0 */
    @Deprecated
    @PUT
    @Path("/parametres-solveur")
    public SolverSettingsView updateParametresSolveur(ParametresSolveur parametres) {
        return writeSolveur(parametres.withStamp(null));
    }

    /** @deprecated alias of {@code /parametres/notifications}, removed in 5.0 */
    @Deprecated
    @GET
    @Path("/parametres-notifications")
    public ParametresNotifications getParametresNotifications() {
        return getNotifications();
    }

    /** @deprecated alias of {@code /parametres/notifications}, removed in 5.0 */
    @Deprecated
    @PUT
    @Path("/parametres-notifications")
    public Response updateParametresNotifications(ParametresNotifications parametres) {
        return writeNotifications(parametres.withStamp(null));
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
