package dev.sylvain.planning.api;

import dev.sylvain.planning.domain.ValidationJournee;
import dev.sylvain.planning.service.BusinessError;
import dev.sylvain.planning.service.validation.ValidationJourneeService;
import dev.sylvain.planning.service.validation.ValidationJourneeService.DemandeValidation;
import dev.sylvain.planning.service.validation.ValidationPrerequisService;
import dev.sylvain.planning.service.validation.ValidationPrerequisService.PrerequisJournee;
import dev.sylvain.planning.service.validation.ValidationPrerequisService.ProgressionValidations;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * « Relu et accepté » on a day of the planning — the review state the lock
 * mechanism never carried.
 *
 * <p>Shaped like {@code /api/verrouillages}: list, create, delete. A validation
 * is state, so it is never updated — re-reading a day replaces its row.</p>
 */
@Path("/validations")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ValidationJourneeResource {

    @Inject
    ValidationJourneeService validationService;

    @Inject
    ValidationPrerequisService prerequisService;

    /**
     * What a screen posts to accept a day.
     *
     * @param poserVerrou lay a {@code JOUR} lock down at the same time. False by
     *                    default, and deliberately: accepting a day says
     *                    somebody read it, not that the solver may no longer
     *                    touch it
     */
    @Schema(requiredProperties = {"jour"})
    public record DemandeValidationJournee(LocalDate jour, String standId, String commentaire, boolean poserVerrou) {}

    /** A validation, and whether the request's lock was actually laid down. */
    @Schema(requiredProperties = {"validation", "verrouPose"})
    public record ResultatValidationJournee(ValidationJournee validation, boolean verrouPose) {}

    /** Every validation of the edition, most recently read first. */
    @GET
    public List<ValidationJournee> list() {
        return validationService.list();
    }

    /** How far the reading has got — what the progress banners show. */
    @GET
    @Path("/progression")
    public ProgressionValidations progression() {
        return prerequisService.progression();
    }

    /**
     * What to check before accepting {@code jour}. Read-only, and never a
     * refusal: the panel says what is wrong and still lets the day through.
     */
    @GET
    @Path("/prerequis")
    public PrerequisJournee prerequis(@QueryParam("jour") String jour, @QueryParam("stand") String standId) {
        return prerequisService.prerequis(jourDemande(jour), standId == null || standId.isBlank() ? null : standId);
    }

    /** Records a reading of one day, and lays its lock down when asked to. */
    @POST
    public ResultatValidationJournee accept(DemandeValidationJournee demande) {
        if (demande == null) {
            throw new BusinessError.Invalid("Journée à valider manquante");
        }
        var resultat = validationService.accept(
                new DemandeValidation(demande.jour(), demande.standId(), demande.commentaire(), demande.poserVerrou()));
        return new ResultatValidationJournee(resultat.validation(), resultat.verrouPose());
    }

    /** Withdraws a reading. The lock it may have laid down is left alone. */
    @DELETE
    @Path("/{id}")
    public Response withdraw(@PathParam("id") String id) {
        validationService.withdraw(id);
        return Response.noContent().build();
    }

    private static LocalDate jourDemande(String jour) {
        if (jour == null || jour.isBlank()) {
            throw new BusinessError.Invalid("Paramètre « jour » manquant (AAAA-MM-JJ)");
        }
        try {
            return LocalDate.parse(jour);
        } catch (DateTimeParseException e) {
            throw new BusinessError.Invalid("Journée illisible : " + jour + " (attendu AAAA-MM-JJ)");
        }
    }
}
