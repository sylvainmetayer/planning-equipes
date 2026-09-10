package dev.sylvain.planning.service.solve;

import dev.sylvain.planning.service.BusinessError;

/**
 * Where a full solve starts from (issue #174).
 *
 * <p>Measured on a real edition (3 499 seats, 600 s): a solve started from
 * scratch ended 1 202 medium points <em>below</em> the plan already in the
 * database, while one started from that plan kept it and improved it by
 * ~3 %. Starting cold is therefore the harmful, rare case, and the default
 * says so — {@link #AUTO} re-seeds whenever there is a plan to re-seed from.
 * Re-seeding is <b>not</b> pinning: every seat stays movable, only the
 * explicit locks of issue #87 are pinned, which is what tells it apart from
 * the incremental re-solve (which pins everything still valid).</p>
 */
public enum Reamorcage {

    /** Start from the persisted plan when there is one, from scratch otherwise. The default everywhere. */
    AUTO,

    /** Start from the persisted plan, and fail when there is none rather than silently starting cold. */
    PLAN_COURANT,

    /** Start from scratch, knowingly: the only way to lose what the previous solve had reached. */
    AUCUN;

    /**
     * Parses a query or tool argument; {@code null} or blank means the default.
     *
     * @throws BusinessError.Invalid on an unknown value — the caller sent a
     *                               word, the answer names the three it may use
     */
    public static Reamorcage parse(String value) {
        if (value == null || value.isBlank()) {
            return AUTO;
        }
        try {
            return valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BusinessError.Invalid("reamorcage : valeur inconnue « " + value
                    + " », attendu AUTO, PLAN_COURANT ou AUCUN");
        }
    }
}
