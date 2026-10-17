package dev.sylvain.planning.service.solve;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.PosteAffectation;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The walk every reading of a persisted plan shares: the n-th seat of a
 * stand × timeslot cell is paired with the n-th holder persisted in that cell
 * — the seats of a cell being interchangeable, see
 * {@link PlanningPersistenceService#loadAnimateursByStandCreneau()}. What each
 * caller then does with the pair is its own decision.
 */
final class SeatHolders {

    private SeatHolders() {}

    /** One seat, the holders persisted in its cell, and its rank among that cell's seats. */
    record Place(PosteAffectation poste, List<String> holders, int rank) {

        /** Whether the persisted plan has an entry at this place. */
        boolean held() {
            return rank < holders.size();
        }

        /** The id persisted at this place, {@code null} when there is none. */
        String holderId() {
            return held() ? holders.get(rank) : null;
        }
    }

    /** Every seat carrying a stand and a timeslot, in order, with its place; the others are skipped. */
    static List<Place> places(List<PosteAffectation> postes, Map<String, List<String>> holdersByCell) {
        Map<String, Integer> nextRank = new HashMap<>();
        List<Place> places = new ArrayList<>(postes.size());
        for (PosteAffectation poste : postes) {
            if (poste.getStand() == null || poste.getCreneau() == null) {
                continue;
            }
            String key = PlanningPersistenceService.standCreneauKey(
                    poste.getStand().getId(), poste.getCreneau().getId());
            int rank = nextRank.merge(key, 1, Integer::sum) - 1;
            places.add(new Place(poste, holdersByCell.getOrDefault(key, List.of()), rank));
        }
        return places;
    }

    /** The roster by id, the last one winning on a duplicate id. */
    static Map<String, Animateur> byId(List<Animateur> animateurs) {
        Map<String, Animateur> animateursById = new HashMap<>();
        for (Animateur animateur : animateurs) {
            animateursById.put(animateur.getId(), animateur);
        }
        return animateursById;
    }
}
