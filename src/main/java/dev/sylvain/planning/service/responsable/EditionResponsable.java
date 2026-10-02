package dev.sylvain.planning.service.responsable;

import java.time.Instant;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * An edition where the caller holds a right of responsable de stand in force.
 *
 * @param active   whether it is the active edition (ADR 0072) — the one
 *                 the screen opens first
 * @param expireLe when the last of the caller's rights on it expires
 */
@Schema(requiredProperties = {"editionId", "editionNom", "active", "expireLe"})
public record EditionResponsable(String editionId, String editionNom, boolean active, Instant expireLe) {}
