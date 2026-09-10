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
 * <li>{@code GET|PUT /debug/date-du-jour} freezes the date the mode jour J
 * screen reads, so it can be exercised out of season. <b>Development only</b>,
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
     * @param dateDuJour {@code null} when the real clock is in use
     */
    public record DateJourJView(String dateDuJour, boolean modifiable) {}

    @GET
    @Path("/date-du-jour")
    @Produces(MediaType.APPLICATION_JSON)
    public DateJourJView dateJourJ() {
        LocalDate fige = clock.mockedDate();
        return new DateJourJView(fige == null ? null : fige.toString(), clock.isModifiable());
    }

    /**
     * Freezes the date, or hands it back to the machine with a blank/absent
     * {@code dateDuJour}. Answers 400 on any server not launched with
     * {@code quarkus:dev}.
     */
    @PUT
    @Path("/date-du-jour")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public DateJourJView setDateJourJ(DateJourJView demande) {
        LocalDate fige = clock.setMockedDate(parse(demande == null ? null : demande.dateDuJour()));
        return new DateJourJView(fige == null ? null : fige.toString(), clock.isModifiable());
    }

    private static LocalDate parse(String date) {
        if (date == null || date.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(date);
        } catch (DateTimeParseException e) {
            throw new BusinessError.Invalid("Date illisible : « " + date + " » (attendu AAAA-MM-JJ).");
        }
    }
}
