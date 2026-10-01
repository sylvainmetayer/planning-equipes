package dev.sylvain.planning.api;

import dev.sylvain.planning.service.publication.AdminAddress;
import dev.sylvain.planning.service.publication.MailService;
import dev.sylvain.planning.service.schema.ApplicationVersion;
import dev.sylvain.planning.service.schema.ApplicationVersionRepository;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Response;
import java.util.List;

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
 * <li>{@code GET /debug/versions} lists the application versions that opened
 * this database, the latest first — what an incident report needs to say
 * « migrated by 1.3.0, then opened by 1.2.4 » (see
 * {@code SchemaCompatibilityGuard}).</li>
 * </ul>
 *
 * <p>The simulated clock it used to carry is {@link HorlogeResource}: a
 * setting of the instance, not a check of the plumbing.</p>
 */
@Path("/debug")
public class DebugResource {

    private final MailService mailService;

    private final AdminAddress adminAddress;

    private final ApplicationVersionRepository applicationVersions;

    @Inject
    public DebugResource(
            MailService mailService, AdminAddress adminAddress, ApplicationVersionRepository applicationVersions) {
        this.mailService = mailService;
        this.adminAddress = adminAddress;
        this.applicationVersions = applicationVersions;
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

    @GET
    @Path("/versions")
    public List<ApplicationVersion> applicationVersions() {
        return applicationVersions.list();
    }
}
