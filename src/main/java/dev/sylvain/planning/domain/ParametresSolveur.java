package dev.sylvain.planning.domain;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * What an edition decides about how its solves end: whether a finished solve
 * mails its outcome. The solve budget (the longest a solve runs, how long a
 * feasible plan may go without improving) is no longer an edition's setting:
 * it is the instance's, set by the operator (ADR 0110), because the solver is
 * shared by every edition, one job at a time. Never seen by the solver as a
 * problem fact.
 *
 * <p>A record: it is read, written whole, and never mutated field by field.</p>
 *
 * @param mailFinResolution whether a finished solve mails its outcome to the
 *                          admin address. Off by default — sending mail is
 *                          never something an application should start doing
 *                          on its own — and inert until {@code MAIL_ADMIN} is
 *                          configured
 */
@Schema(requiredProperties = {"mailFinResolution"})
public record ParametresSolveur(boolean mailFinResolution) {

    /** Nothing set: no mail. */
    public ParametresSolveur() {
        this(false);
    }
}
