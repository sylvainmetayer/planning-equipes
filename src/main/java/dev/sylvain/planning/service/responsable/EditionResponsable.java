package dev.sylvain.planning.service.responsable;

import java.time.Instant;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * An edition where the caller holds a right of responsable de stand in force.
 *
 * @param defaut   whether it is the deployment's default edition — the one
 *                 the screen opens first
 * @param expireLe when the last of the caller's rights on it expires
 */
@Schema(requiredProperties = {"editionId", "editionNom", "defaut", "expireLe"})
public record EditionResponsable(String editionId, String editionNom, boolean defaut, Instant expireLe) {}
