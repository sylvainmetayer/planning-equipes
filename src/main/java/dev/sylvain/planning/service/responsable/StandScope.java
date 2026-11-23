package dev.sylvain.planning.service.responsable;

import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.service.solve.SeatSplit;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The scope of a responsable de stand, and the <b>one door</b> through which
 * this package reads the plan and the stands (issue #295).
 *
 * <p>The published plan is a snapshot read whole, so the stand predicate
 * cannot be a SQL {@code WHERE}: it is this class. Nothing else in the
 * package calls {@code getPostes()} or lists the stands —
 * {@code ResponsableProjectionStructurelleTest} fails the build otherwise —,
 * so a view added later cannot forget the filter: it has no other way in.</p>
 *
 * <p>What leaves the scope is only ever a bare window: {@link #occupations}
 * says when a team member is taken elsewhere, never where nor with whom.</p>
 */
final class StandScope {

    private final Set<String> standIds;

    StandScope(Set<String> standIds) {
        this.standIds = Set.copyOf(standIds);
    }

    boolean contains(String standId) {
        return standIds.contains(standId);
    }

    /** The stands of the scope still in the edition, out of {@code all}. */
    List<Stand> stands(Collection<Stand> all) {
        return all.stream().filter(stand -> standIds.contains(stand.getId())).toList();
    }

    /** The seats of the plan standing on a stand of the scope, held or not. */
    List<PosteAffectation> seats(PlanningEvenement plan) {
        List<PosteAffectation> postes = plan.getPostes();
        if (postes == null) {
            return List.of();
        }
        return postes.stream()
                .filter(poste -> poste.getStand() != null
                        && standIds.contains(poste.getStand().getId()))
                .toList();
    }

    /**
     * When each of {@code animateurIds} holds a seat on no stand of
     * {@code nommes} — elsewhere in the edition, or on a stand of the scope
     * shown by head count —, as bare windows. The stand, the colleagues and
     * the duration summed over a person never leave this method.
     */
    Map<String, List<LocalDateTime[]>> occupations(
            PlanningEvenement plan, Set<String> animateurIds, Set<String> nommes) {
        Map<String, List<LocalDateTime[]>> parPersonne = new LinkedHashMap<>();
        List<PosteAffectation> postes = plan.getPostes();
        if (postes == null) {
            return parPersonne;
        }
        for (PosteAffectation poste : postes) {
            if (poste.getAnimateur() == null
                    || !animateurIds.contains(poste.getAnimateur().getId())
                    || (poste.getStand() != null
                            && nommes.contains(poste.getStand().getId()))) {
                continue;
            }
            LocalDateTime[] fenetre = SeatSplit.window(poste);
            if (fenetre != null) {
                parPersonne
                        .computeIfAbsent(poste.getAnimateur().getId(), ignore -> new ArrayList<>())
                        .add(fenetre);
            }
        }
        return parPersonne;
    }
}
