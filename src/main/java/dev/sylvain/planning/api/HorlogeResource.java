package dev.sylvain.planning.api;

import dev.sylvain.planning.service.BusinessError;
import dev.sylvain.planning.service.espace.JourJClock;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * The simulated clock: the date — and optionally the time of day — that the
 * mode jour J screen, the espace day marker and the frozen past read, so they
 * can be exercised out of season. Set from Paramètres › Instance, on a
 * demonstration or staging server and in development only, and refused here
 * rather than hidden in the interface — see {@link JourJClock}.
 *
 * <p>Under {@code /api/horloge} rather than under the Débogage plumbing it
 * used to share an address with: a demonstration server sets it from the
 * settings of the instance, not from a debugging screen.</p>
 */
@Path("/horloge")
public class HorlogeResource {

    private static final DateTimeFormatter HEURE = DateTimeFormatter.ofPattern("HH:mm");

    private final JourJClock clock;

    @Inject
    public HorlogeResource(JourJClock clock) {
        this.clock = clock;
    }

    /**
     * The frozen date, and whether this server would accept one at all.
     *
     * <p>{@code modifiable} is what the interface hides the card on. It is a
     * courtesy, not the guard: the guard is {@link #setDateJourJ}, which
     * refuses whatever the caller believes.</p>
     *
     * @param dateDuJour  {@code null} when the real clock is in use
     * @param heureDuJour {@code HH:mm}, {@code null} while the wall clock gives
     *                    the time — always {@code null} without a date
     */
    public record DateJourJView(String dateDuJour, String heureDuJour, boolean modifiable) {}

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public DateJourJView dateJourJ() {
        return view(clock.mocked());
    }

    /**
     * Freezes the date and, with {@code heureDuJour}, the time of day; a
     * blank/absent {@code dateDuJour} hands both back to the machine. Answers 400
     * on any server without {@code SimulatedClockPermission}, and for a time
     * without a date.
     */
    @PUT
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public DateJourJView setDateJourJ(DateJourJView demande) {
        return view(clock.setMocked(
                parse(demande == null ? null : demande.dateDuJour()),
                parseHeure(demande == null ? null : demande.heureDuJour())));
    }

    private DateJourJView view(JourJClock.Horloge horloge) {
        return new DateJourJView(
                horloge.date() == null ? null : horloge.date().toString(),
                horloge.heure() == null ? null : horloge.heure().format(HEURE),
                clock.isModifiable());
    }

    private static LocalTime parseHeure(String heure) {
        if (heure == null || heure.isBlank()) {
            return null;
        }
        try {
            return LocalTime.parse(heure).withSecond(0).withNano(0);
        } catch (DateTimeParseException _) {
            throw new BusinessError.Invalid("Heure illisible : « " + heure + " » (attendu HH:mm).");
        }
    }

    private static LocalDate parse(String date) {
        if (date == null || date.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(date);
        } catch (DateTimeParseException _) {
            throw new BusinessError.Invalid("Date illisible : « " + date + " » (attendu AAAA-MM-JJ).");
        }
    }
}
