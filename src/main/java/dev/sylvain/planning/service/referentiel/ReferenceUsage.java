package dev.sylvain.planning.service.referentiel;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * What a referential entity — a stand, an animateur, a timeslot — is referenced
 * by, counted before it is deleted so the confirmation can say what goes with
 * it: seats of the persisted plan, ad hoc constraints, locks, and the
 * consignes (issue #4) that open the stand or added the timeslot.
 *
 * <p>It informs, it never blocks: no threshold, no refusal. The row itself
 * shows none of this, which is exactly why the number has to come from the
 * server rather than from a screen that never loaded the plan.</p>
 *
 * @param consignes dates under consigne naming the row: the stand is opened
 *                  on one of them, or the timeslot was added by one — the
 *                  delete would cascade the opening or the « added by
 *                  consigne » marker away in silence otherwise. Always zero
 *                  for an animateur, whom a consigne never names
 */
@Schema(requiredProperties = {"affectations", "contraintesAdHoc", "verrouillages", "consignes"})
public record ReferenceUsage(int affectations, int contraintesAdHoc, int verrouillages, int consignes) {

    public static final ReferenceUsage AUCUN = new ReferenceUsage(0, 0, 0, 0);
}
