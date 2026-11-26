package dev.sylvain.planning.api;

import dev.sylvain.planning.domain.DayState;
import dev.sylvain.planning.service.BusinessError;
import dev.sylvain.planning.service.validation.ValidationJourneeService;
import dev.sylvain.planning.service.validation.ValidationJourneeService.DayStateView;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * The state of each day of the plan — à relire, relue, figée (ADR 0121). It
 * replaces the {@code /validations} writes, kept as aliases until 5.0.
 */
@Path("/etats-journee")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class DayStateResource {

    private final ValidationJourneeService validationService;

    @Inject
    public DayStateResource(ValidationJourneeService validationService) {
        this.validationService = validationService;
    }

    /**
     * What a screen sends to move a day.
     *
     * @param commentaire the reading's comment, kept when the day is read or
     *                    frozen, dropped when it goes back to be read
     */
    @Schema(requiredProperties = {"etat"})
    public record DayStateChange(DayState etat, String commentaire) {}

    /** Every day of the edition's grid and its state, in calendar order. */
    @GET
    public List<DayStateView> list() {
        return validationService.states();
    }

    /**
     * Moves one day. {@code FIGEE} lays the day lock down, the two others
     * lift it; besides the Verrouillages screen, only the deprecated
     * {@code POST /validations} with {@code poserVerrou} also lays one (ADR 0039).
     */
    @PUT
    @Path("/{jour}")
    public DayStateView set(@PathParam("jour") String jour, DayStateChange change) {
        if (change == null) {
            throw new BusinessError.Invalid("État manquant : A_RELIRE, RELUE ou FIGEE");
        }
        return validationService.setState(day(jour), change.etat(), change.commentaire());
    }

    private static LocalDate day(String jour) {
        try {
            return LocalDate.parse(jour);
        } catch (DateTimeParseException _) {
            throw new BusinessError.Invalid("Journée illisible : " + jour + " (attendu AAAA-MM-JJ)");
        }
    }
}
