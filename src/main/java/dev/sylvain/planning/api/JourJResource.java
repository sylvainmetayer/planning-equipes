package dev.sylvain.planning.api;

import dev.sylvain.planning.service.BusinessError;
import dev.sylvain.planning.service.espace.JourJService;
import dev.sylvain.planning.service.espace.JourJService.AbsenceMarquee;
import dev.sylvain.planning.service.espace.JourJService.EtatJourJ;
import dev.sylvain.planning.service.solve.PlanningWhatIf.SuggestionsReparation;
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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * The event-day screen (issue #297): mark somebody absent, see who can take
 * their seats, hand one over — without a solve, and without leaving the page.
 *
 * <p>Applying a replacement is <b>not</b> here: it is
 * {@code POST /api/postes/{id}/affectation}, the surgical write the repair
 * assistant already exposes. Two paths writing the same row would be two places
 * to keep the lock rule in.</p>
 *
 * <p>{@code date} names the journée and {@code maintenant} the moment; both
 * default to what the server's own clock says. They are overridable because "the
 * day of the event" is not always the day the operator is sitting in — a
 * rehearsal, a demonstration, a test — and because a screen whose behaviour
 * depends on an unstatable clock cannot be verified.</p>
 *
 * <p>{@code maintenant} is a full instant rather than an hour, because a journée
 * running past midnight puts the two on different calendar dates: at
 * {@code 2026-07-09T01:00} the journée being looked at is still
 * {@code 2026-07-08}.</p>
 */
@Path("/jour-j")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class JourJResource {

    @Inject
    JourJService jourJService;

    /** The whole screen: remaining timeslots, who is on duty, the holes, the absences. */
    @GET
    public EtatJourJ etat(@QueryParam("date") String date, @QueryParam("maintenant") String maintenant) {
        return jourJService.etat(jour(date), moment(maintenant));
    }

    /**
     * Marks somebody absent for the rest of the day: a forced unavailability on
     * every remaining timeslot, and their seats over those timeslots emptied.
     * Refused as a whole (400) when it would contradict an exception already
     * recorded or free a locked seat.
     */
    @POST
    @Path("/absences")
    public AbsenceMarquee recordAbsence(
            DemandeAbsence demande, @QueryParam("date") String date, @QueryParam("maintenant") String maintenant) {
        String animateurId = demande == null ? null : demande.animateurId();
        return jourJService.recordAbsence(
                animateurId, demande == null ? null : demande.raison(), jour(date), moment(maintenant));
    }

    /**
     * Undoes a marked absence — one timeslot with {@code creneauId}, the whole
     * day without. The seats are not handed back: see
     * {@link JourJService#cancelAbsence}.
     */
    @DELETE
    @Path("/absences/{animateurId}")
    public AnnulationAbsence cancelAbsence(
            @PathParam("animateurId") String animateurId,
            @QueryParam("date") String date,
            @QueryParam("creneauId") Long creneauId) {
        return new AnnulationAbsence(jourJService.cancelAbsence(animateurId, jour(date), creneauId));
    }

    /**
     * The repair assistant of issue #71 over the <b>persisted</b> plan: same
     * search, same plafond (20 by default, 100 at most), but the caller names a
     * seat instead of uploading the whole planning — this screen runs on a
     * phone in an aisle.
     */
    @POST
    @Path("/postes/{posteId}/suggestions")
    @Consumes(MediaType.WILDCARD)
    public SuggestionsReparation suggestions(
            @PathParam("posteId") String posteId, @QueryParam("plafond") Integer plafond) {
        return jourJService.suggestions(posteId, plafond);
    }

    /**
     * Reads the two URL parameters. A malformed one is a 400 naming it, not the
     * 500 a raw {@code DateTimeParseException} would become — this is transport,
     * which is what a resource is for.
     */
    private static LocalDate jour(String date) {
        if (date == null || date.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(date);
        } catch (DateTimeParseException e) {
            throw new BusinessError.Invalid("Date illisible : « " + date + " » (attendu AAAA-MM-JJ).");
        }
    }

    private static LocalDateTime moment(String maintenant) {
        if (maintenant == null || maintenant.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(maintenant);
        } catch (DateTimeParseException e) {
            throw new BusinessError.Invalid("Moment illisible : « " + maintenant + " » (attendu AAAA-MM-JJTHH:MM).");
        }
    }

    /** Body of « marquer absent ». The reason is optional and lands in the exception's trace. */
    public record DemandeAbsence(String animateurId, String raison) {}

    /** How many exceptions a cancellation removed. */
    @Schema(requiredProperties = {"supprimees"})
    public record AnnulationAbsence(int supprimees) {}
}
