package dev.sylvain.planning.api;

import dev.sylvain.planning.service.publication.AdminAddress;
import dev.sylvain.planning.service.publication.MailService;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Response;

/**
 * Endpoints backing the Débogage page's plumbing checks — none of them is a
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
 *
 * <p>The simulated clock it used to carry is {@link HorlogeResource}: a
 * setting of the instance, not a check of the plumbing.</p>
 */
@Path("/debug")
public class DebugResource {

    private final MailService mailService;

    private final AdminAddress adminAddress;

    @Inject
    public DebugResource(MailService mailService, AdminAddress adminAddress) {
        this.mailService = mailService;
        this.adminAddress = adminAddress;
    }

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
}
