package dev.sylvain.planning.api;

import dev.sylvain.planning.service.AdminAddress;
import dev.sylvain.planning.service.MailService;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Response;

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
 * </ul>
 */
@Path("/debug")
public class DebugResource {

    @Inject
    MailService mailService;

    @Inject
    AdminAddress adminAddress;

    @POST
    @Path("/test-exception")
    public void throwTestException() {
        throw new IllegalStateException("Test exception (bouton Débogage / Exception back)");
    }

    /** {@code adminEmail} is {@code null} when MAIL_ADMIN is not set: mail notifications are disabled. */
    public record MailConfigView(String adminEmail) {
    }


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
}
