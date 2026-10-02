package dev.sylvain.planning.service.export;

import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * What a set of seats amounts to — how many days, stands, timeslots, hours.
 *
 * <p>The individual document and the organiser's one head their first page
 * with the same figures over different sets: one the assignments of a single
 * person, the other those of the whole event. Counting in one place is what
 * guarantees that « 3 jours » means the same thing on both.</p>
 */
final class PosteStatistics {

    private PosteStatistics() {}

    static double totalHeures(List<PosteAffectation> postes) {
        int totalMinutes = 0;
        for (PosteAffectation poste : postes) {
            totalMinutes += poste.getDureeEffectiveMinutes();
        }
        return totalMinutes / 60.0;
    }

    /** Stands this animateur is assigned to, deduplicated, in first-appearance (chronological) order. */
    static List<Stand> distinctStands(List<PosteAffectation> postes) {
        List<Stand> stands = new ArrayList<>();
        Set<String> seenIds = new LinkedHashSet<>();
        for (PosteAffectation poste : postes) {
            Stand stand = poste.getStand();
            if (stand != null && seenIds.add(stand.getId())) {
                stands.add(stand);
            }
        }
        return stands;
    }

    static int distinctStandCount(List<PosteAffectation> postes) {
        return (int) postes.stream()
                .filter(poste -> poste.getStand() != null)
                .map(poste -> poste.getStand().getId())
                .distinct()
                .count();
    }

    static int distinctCreneauCount(List<PosteAffectation> postes) {
        return (int) postes.stream()
                .filter(poste -> poste.getCreneau() != null)
                .map(poste -> poste.getCreneau().getId())
                .distinct()
                .count();
    }

    static int distinctDayCount(List<PosteAffectation> postes) {
        return (int) postes.stream()
                .filter(poste -> poste.getCreneau() != null)
                .map(poste -> poste.getCreneau().getJour())
                .distinct()
                .count();
    }
}
