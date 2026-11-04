package dev.sylvain.planning.domain;

/**
 * What the responsables de stand of this edition read on their stands
 * (issue #295): names, or head counts only.
 *
 * <p>Off by default, and on purpose: while the plan still moves, « on vous
 * garantit deux animateurs » is a promise the organiser can keep, « on vous
 * garantit Bob et Alice » is not. The organiser switches it on once the plan
 * has settled. A right may override it either way
 * ({@code Habilitation.nominatif}).</p>
 *
 * <p>Names means first and last names. Never an address nor a phone number,
 * whatever this says.</p>
 *
 * @param nominatif whether the responsables read who holds each seat
 */
public record ParametresResponsables(boolean nominatif) {

    /** What an edition that never chose answers: head counts only. */
    public static ParametresResponsables defaults() {
        return new ParametresResponsables(false);
    }
}
