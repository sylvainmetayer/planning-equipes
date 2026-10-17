package dev.sylvain.planning.service.export;

import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * What a set of seats amounts to — its distinct stands and timeslots, the
 * figures {@link AnimateurPlanningView} heads both individual layouts with.
 */
final class PosteStatistics {

    private PosteStatistics() {}

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

    static int distinctCreneauCount(List<PosteAffectation> postes) {
        return (int) postes.stream()
                .filter(poste -> poste.getCreneau() != null)
                .map(poste -> poste.getCreneau().getId())
                .distinct()
                .count();
    }
}
