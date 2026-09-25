package dev.sylvain.planning.service.mural;

import java.time.Instant;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * A live wall display link, as the admin lists it. The token is not here and
 * cannot be: only its hash is stored, and it is shown once, at creation
 * ({@link CreatedAffichageMuralLink}).
 *
 * @param fullNames    full names on screen rather than the first name and an
 *                     initial
 * @param restricted   created for a few emplacements: the screen shows those
 *                     of {@code emplacements} still in the edition, and
 *                     nothing once they are all deleted
 * @param emplacements the emplacements the screen is restricted to
 * @param lastAccessAt the last read by a screen, to the minute; {@code null}
 *                     while no screen has opened it
 */
@Schema(requiredProperties = {"id", "libelle", "fullNames", "restricted", "emplacements", "createdAt"})
public record AffichageMuralLink(
        long id,
        String libelle,
        boolean fullNames,
        boolean restricted,
        List<String> emplacements,
        Instant createdAt,
        Instant lastAccessAt) {}
