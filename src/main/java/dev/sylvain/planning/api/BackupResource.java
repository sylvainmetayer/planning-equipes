package dev.sylvain.planning.api;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import dev.sylvain.planning.service.backup.BackupService;
import dev.sylvain.planning.service.backup.BackupState;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * The automatic backup, as the Paramètres screen reads and suspends it.
 *
 * <p>Read-mostly on purpose: the destination directory and the retention come
 * from the environment and are only reported here, and the dumps themselves are
 * never served — they hold every animateur's name, birth date and e-mail, and
 * the browser is not a distribution channel for that. Restoring one is an
 * infrastructure operation on PostgreSQL, not an endpoint.</p>
 */
@Path("/backups")
@Produces(MediaType.APPLICATION_JSON)
public class BackupResource {

    @Inject
    BackupService backupService;

    @GET
    public BackupState get() {
        return backupService.state();
    }

    /** Suspends or resumes the nightly run. */
    @PUT
    @Path("/active")
    @Consumes(MediaType.APPLICATION_JSON)
    public BackupState setActive(ActiveRequest request) {
        return backupService.setActive(request != null && request.active());
    }

    
    public record ActiveRequest(boolean active) {
    }
}
