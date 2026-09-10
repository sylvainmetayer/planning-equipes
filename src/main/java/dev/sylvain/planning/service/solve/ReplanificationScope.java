package dev.sylvain.planning.service.solve;

import java.time.LocalDate;
import java.util.Set;

import dev.sylvain.planning.domain.PosteAffectation;

/**
 * What an incremental re-solve (issue #86) deliberately re-opens, <b>on top of</b>
 * what a late change already invalidated by itself.
 *
 * <p>The automatic part of the perimeter needs no input: a seat whose holder
 * was deleted or has become unavailable that day is freed anyway. This record
 * covers the other half of the real workflow — "untel se désiste, refais sa
 * journée" — where the operator knows what must move before the referential
 * says so, or wants a wider area re-optimised than the strict consequence of
 * the change.</p>
 *
 * <p>Naming a target here never touches the referential: the perimeter lives
 * for the duration of one job. That is the answer to the design question the
 * issue left open — the freeze of an incremental solve is volatile, and
 * {@link dev.sylvain.planning.domain.VerrouillagePlanning}
 * stays what the user deliberately locks, never a technical artefact left
 * behind by a replanning.</p>
 *
 * @param animateurIds animateurs whose seats are all re-opened
 * @param jours        days whose seats are all re-opened
 * @param standIds     stands whose seats are all re-opened
 */
public record ReplanificationScope(Set<String> animateurIds, Set<LocalDate> jours, Set<String> standIds) {

    public ReplanificationScope {
        animateurIds = animateurIds == null ? Set.of() : Set.copyOf(animateurIds);
        jours = jours == null ? Set.of() : Set.copyOf(jours);
        standIds = standIds == null ? Set.of() : Set.copyOf(standIds);
    }

    /** Nothing re-opened by hand: the perimeter is exactly what the changes invalidated. */
    public static ReplanificationScope automatic() {
        return new ReplanificationScope(Set.of(), Set.of(), Set.of());
    }

    public boolean hasNoTarget() {
        return animateurIds.isEmpty() && jours.isEmpty() && standIds.isEmpty();
    }

    /**
     * Whether this seat must be re-opened by hand, {@code tenantId} being the
     * animateur the persisted plan gave it. A seat matches on any of the three
     * axes — they are a union, not an intersection: "cette journée" and "ce
     * stand" are two independent ways of saying what to redo.
     */
    public boolean release(PosteAffectation poste, String tenantId) {
        if (hasNoTarget()) {
            return false;
        }
        if (tenantId != null && animateurIds.contains(tenantId)) {
            return true;
        }
        if (poste.getCreneau() != null && jours.contains(poste.getCreneau().getDate())) {
            return true;
        }
        return poste.getStand() != null && standIds.contains(poste.getStand().getId());
    }
}
