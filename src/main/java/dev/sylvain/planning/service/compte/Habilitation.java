package dev.sylvain.planning.service.compte;

import java.time.Instant;
import java.util.List;

/**
 * What one account may do, and where. Withdrawn by {@code retireeLe}, never
 * deleted, so the history can still say who held which right on which date.
 *
 * @param editionId {@code null} for every edition
 * @param expireLe  {@code null} for no expiry; past it, the right opens nothing
 * @param creePar   the id of the account that granted it, never an address
 * @param retireePar the id of the account that withdrew it, never an address
 * @param standIds  the stands a {@link RoleHabilitation#RESPONSABLE_STAND} right covers; empty for the other roles
 */
public record Habilitation(
        String id,
        RoleHabilitation role,
        String editionId,
        Instant expireLe,
        String creePar,
        Instant creeLe,
        Instant retireeLe,
        String retireePar,
        List<String> standIds) {}
