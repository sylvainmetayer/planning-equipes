package dev.sylvain.planning.api;

import dev.sylvain.planning.service.BusinessError;
import dev.sylvain.planning.service.journee.ChangementsJournee;
import dev.sylvain.planning.service.journee.ChangementsJournee.ReferenceChangements;
import dev.sylvain.planning.service.journee.ChangementsJourneeService;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;

/**
 * What changed on one day of the plan since a reference — the Changements
 * rendering of the Journée page. A read, and only that: it writes nothing and
 * leaves no trace in the journal.
 */
@Path("/journees")
@Produces(MediaType.APPLICATION_JSON)
public class ChangementsJourneeResource {

    private final ChangementsJourneeService changementsService;

    @Inject
    public ChangementsJourneeResource(ChangementsJourneeService changementsService) {
        this.changementsService = changementsService;
    }

    /**
     * The day's changes against {@code reference} — {@code publication} or
     * {@code resolution}; left out, the publication when one exists, the last
     * solve otherwise. A reference that does not exist answers {@code 200}
     * with {@code referenceDisponible} false: there is nothing to compare, and
     * that is an answer rather than an error.
     */
    @GET
    @Path("/{jour}/changements")
    public ChangementsJournee changements(@PathParam("jour") String jour, @QueryParam("reference") String reference) {
        return changementsService.changements(jourDemande(jour), ReferenceChangements.fromParam(reference));
    }

    private static LocalDate jourDemande(String jour) {
        try {
            return LocalDate.parse(jour);
        } catch (DateTimeParseException _) {
            throw new BusinessError.Invalid("Journée illisible : " + jour + " (attendu AAAA-MM-JJ)");
        }
    }
}
