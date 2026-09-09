package dev.sylvain.planning.service;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * What a referential entity — a stand, an animateur, a timeslot — is referenced
 * by, counted before it is deleted so the confirmation can say what goes with
 * it: seats of the persisted plan, ad hoc constraints, locks.
 *
 * <p>It informs, it never blocks: no threshold, no refusal. The row itself
 * shows none of this, which is exactly why the number has to come from the
 * server rather than from a screen that never loaded the plan.</p>
 */
@Schema(requiredProperties = {"affectations", "contraintesAdHoc", "verrouillages"})
public record ReferenceUsage(int affectations, int contraintesAdHoc, int verrouillages) {

    public static final ReferenceUsage AUCUN = new ReferenceUsage(0, 0, 0);
}
