package dev.sylvain.planning.service.compte;

import java.time.Instant;

/**
 * What one account may do, and where. Withdrawn by {@code retireeLe}, never
 * deleted, so the history can still say who held which right on which date.
 *
 * @param editionId {@code null} for every edition
 * @param expireLe  {@code null} for no expiry; past it, the right opens nothing
 * @param creePar   the id of the account that granted it, never an address
 */
public record Habilitation(
        String id,
        RoleHabilitation role,
        String editionId,
        Instant expireLe,
        String creePar,
        Instant creeLe,
        Instant retireeLe) {

    /** Neither withdrawn nor expired at {@code instant}. */
    public boolean inForce(Instant instant) {
        return retireeLe == null && (expireLe == null || expireLe.isAfter(instant));
    }
}
