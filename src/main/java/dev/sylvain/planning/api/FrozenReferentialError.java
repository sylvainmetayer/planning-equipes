package dev.sylvain.planning.api;

import java.util.List;

/**
 * Body of the {@code 409} a write of a frozen family gets
 * ({@code BusinessError.Frozen}, ADR 0052).
 *
 * @param message  what happened and which freeze to lift, in the user's words
 * @param code     always {@link #FROZEN_CODE}: the discriminator the frontend
 *                 switches on to refresh its padlocks rather than only show a
 *                 banner
 * @param familles the frozen families that refused the write, as their wire names
 */
public record FrozenReferentialError(String message, String code, List<String> familles) {

    public static final String FROZEN_CODE = "REFERENTIEL_FIGE";

    public FrozenReferentialError(String message, List<String> familles) {
        this(message, FROZEN_CODE, familles);
    }
}
