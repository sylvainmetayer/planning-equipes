package dev.sylvain.planning.domain;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * The solve budget an edition asks for (Solveur page), and whether a finished
 * solve mails its outcome. Never seen by the solver as a problem fact: the
 * server reads it when a job is launched and turns it into that job's
 * termination, bounded by the ceiling the operator set for the whole instance.
 *
 * <p>A record: it is read, written whole, and never mutated field by field.</p>
 *
 * @param dureeResolutionSecondes how long a solve runs at most; {@code null}
 *                                follows the deployment default
 *                                ({@code planning.solver.seconds-limit}), which
 *                                is what « Revenir au défaut » writes
 * @param plateauSecondes         how long an <b>already feasible</b> planning
 *                                may go without improving before the solve
 *                                stops; {@code 0} never stops early, {@code null}
 *                                follows the deployment default
 *                                ({@code planning.solver.unimproved-seconds-limit})
 * @param mailFinResolution       whether a finished solve mails its outcome to
 *                                the admin address. Off by default — sending
 *                                mail is never something an application should
 *                                start doing on its own — and inert until
 *                                {@code MAIL_ADMIN} is configured
 */
@Schema(requiredProperties = {"mailFinResolution"})
public record ParametresSolveur(Integer dureeResolutionSecondes, Integer plateauSecondes, boolean mailFinResolution) {

    /** Nothing set: the deployment's budget, no mail. */
    public ParametresSolveur() {
        this(null, null, false);
    }

    /** A duration alone, as a scenario file of an older format carries it. */
    public ParametresSolveur(Integer dureeResolutionSecondes) {
        this(dureeResolutionSecondes, null, false);
    }
}
