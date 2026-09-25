package dev.sylvain.planning.api;

import dev.sylvain.planning.service.BusinessError;
import dev.sylvain.planning.service.espace.JourJClock;
import dev.sylvain.planning.service.publication.AdminAddress;
import dev.sylvain.planning.service.publication.MailService;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * Endpoints backing the Débogage tab's plumbing checks — none of them is a
 * business operation.
 *
 * <ul>
 * <li>{@code POST /debug/test-exception} throws on purpose: it exercises
 * {@code GlobalExceptionMapper}'s report-to-Sentry/Bugsink path end-to-end,
 * the same way the "Exception front" button exercises the frontend's
 * {@code ErrorHandler}.</li>
 * <li>{@code GET /debug/mail-config} says whether an admin address is
 * configured, so the tab can warn when mail-dependent features are off.</li>
 * <li>{@code POST /debug/test-mail} really sends a mail to that address and
 * FAILS loudly when SMTP is broken — unlike the business sends, which are
 * best-effort by design.</li>
 * <li>{@code GET|PUT /debug/date-du-jour} freezes the date — and optionally
 * the time of day — that the mode jour J screen and the espace day marker read, so it can be exercised out of season. <b>Development and staging only</b>,
 * and refused here rather than hidden in the interface — see
 * {@link JourJClock}.</li>
 * </ul>
 */
@Path("/debug")
public class DebugResource {

    @Inject
    MailService mailService;

    @Inject
    AdminAddress adminAddress;

    @Inject
    JourJClock clock;

    private static final DateTimeFormatter HEURE = DateTimeFormatter.ofPattern("HH:mm");

    @POST
    @Path("/test-exception")
    public void throwTestException() {
        throw new IllegalStateException("Test exception (bouton Débogage / Exception back)");
    }

    /** {@code adminEmail} is {@code null} when MAIL_ADMIN is not set: mail notifications are disabled. */
    public record MailConfigView(String adminEmail) {}

    @GET
    @Path("/mail-config")
    public MailConfigView mailConfig() {
        return new MailConfigView(adminAddress.resolue().orElse(null));
    }

    @POST
    @Path("/test-mail")
    public Response sendTestMail() {
        try {
            return Response.ok(new MailConfigView(mailService.sendTestMail())).build();
        } catch (IllegalStateException e) {
            return Response.status(Response.Status.CONFLICT)
                    .entity(new ValidationError(e.getMessage()))
                    .build();
        }
    }

    /**
     * The frozen date, and whether this server would accept one at all.
     *
     * <p>{@code modifiable} is what the interface hides the field on. It is a
     * courtesy, not the guard: the guard is {@link #setDateJourJ}, which
     * refuses whatever the caller believes.</p>
     *
     * @param dateDuJour  {@code null} when the real clock is in use
     * @param heureDuJour {@code HH:mm}, {@code null} while the wall clock gives
     *                    the time — always {@code null} without a date
     */
    public record DateJourJView(String dateDuJour, String heureDuJour, boolean modifiable) {}

    @GET
    @Path("/date-du-jour")
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
    @Path("/date-du-jour")
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
