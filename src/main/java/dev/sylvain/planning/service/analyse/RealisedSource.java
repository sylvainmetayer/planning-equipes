package dev.sylvain.planning.service.analyse;

import dev.sylvain.planning.domain.PlanningEvenement;
import java.util.Set;

/**
 * Where « what really happened » is read from, for the Réalisé vs planifié
 * screen.
 *
 * <p>Isolated behind an interface because there are two honest answers and
 * only one exists today. The <b>declared</b> realised is the persisted plan of
 * the elapsed days — frozen by the past rule (ADR 0044), and as the mode jour J
 * recorded it: an absence written there frees the seats, a replacement fills
 * them. A future <b>observed</b> realised — a presence check-in where a seat
 * scanned « arrivé » counts as held and a seat never scanned as a presumed
 * absence — answers the same two questions from other facts. The comparison
 * does not change when the source does, so a check-in source only has to
 * replace this bean ({@link DeclaredRealisedSource} is the default one).</p>
 */
public interface RealisedSource {

    /** The realised of the current edition, as this source knows it. */
    Realised read();

    /** How the realised was obtained — what the screen tells its reader. */
    enum RealisedNature {

        /** The plan as it was recorded in the application: declared, not observed. */
        DECLARED,

        /** The plan as a presence check-in observed it. No source provides it yet. */
        OBSERVED
    }

    /**
     * A published holder recorded missing on one timeslot.
     *
     * @param animateurId who was missing
     * @param creneauId   the timeslot they were missing on
     */
    record SeatAbsence(String animateurId, long creneauId) {}

    /**
     * The realised of an edition.
     *
     * @param plan      the plan as it was held, read for the elapsed days only
     * @param absences  who was missing where; a holder replaced without an
     *                  absence recorded is a change of holder, not an absence
     * @param nature    how the realised was obtained
     */
    record Realised(PlanningEvenement plan, Set<SeatAbsence> absences, RealisedNature nature) {

        public Realised {
            absences = Set.copyOf(absences);
        }

        /** Whether {@code animateurId} was recorded missing on timeslot {@code creneauId}. */
        public boolean isAbsent(String animateurId, Long creneauId) {
            return animateurId != null
                    && creneauId != null
                    && absences.contains(new SeatAbsence(animateurId, creneauId));
        }
    }
}
