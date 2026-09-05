package dev.sylvain.planning.api;

import java.time.Instant;

/**
 * Body of the {@code 409} a stale write gets (issue #362, {@code BusinessError.Stale}).
 *
 * @param message   what happened, in the user's words — the frontend shows it as is
 * @param code      always {@link #CODE}: the discriminator the frontend switches
 *                  on to offer « recharger » or « écraser » rather than a plain
 *                  error banner
 * @param modifieLe when the row was actually last written: sending it back as
 *                  the entity's {@code modifieLe} overwrites knowingly
 */
public record StaleWriteError(String message, String code, Instant modifieLe) {

    public static final String CODE = "MODIFICATION_CONCURRENTE";

    public StaleWriteError(String message, Instant modifieLe) {
        this(message, CODE, modifieLe);
    }
}
