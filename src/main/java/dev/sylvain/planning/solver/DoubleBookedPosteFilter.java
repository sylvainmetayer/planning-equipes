package dev.sylvain.planning.solver;

import ai.timefold.solver.core.impl.heuristic.selector.common.decorator.SelectionFilter;
import ai.timefold.solver.core.impl.score.director.ScoreDirector;
import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Restricts an entity selector to the seats whose animateur also holds another
 * seat at the same time — what {@code pasDeChevauchementHoraire} counts.
 *
 * <p>The counterpart of {@link UnassignedPosteFilter} once every seat is
 * filled. The feasibility phase routinely ends up there with one or two
 * double bookings left, and uniform change and swap draw one of those two
 * seats out of 4 000 about once in two thousand moves: on a slow host the
 * phase spent its whole budget at -2hard. Aimed at them, a change hands the
 * seat to someone free at that hour. The swap draws both sides among these
 * seats too (Timefold copies the entity selector): it trades between two
 * animateurs who are each double-booked, and does nothing on a single one.</p>
 *
 * <p>Memoised like {@link HoleNeighbourPosteFilter}: refreshed once every
 * {@code postes.size()} calls, which under a step-cached selector is one scan
 * per step. A pinned seat is never offered, but still counts as the other
 * half of a double booking.</p>
 */
public final class DoubleBookedPosteFilter implements SelectionFilter<PlanningEvenement, PosteAffectation> {

    private PlanningEvenement memoisedSolution;
    private Set<PosteAffectation> doubleBooked = Set.of();
    private int callsSinceScan;
    private int scanInterval = 1;

    @Override
    public boolean accept(ScoreDirector<PlanningEvenement> scoreDirector, PosteAffectation poste) {
        if (poste.getAnimateur() == null || poste.isVerrouille()) {
            return false;
        }
        return doubleBooked(scoreDirector.getWorkingSolution()).contains(poste);
    }

    private Set<PosteAffectation> doubleBooked(PlanningEvenement solution) {
        if (solution != memoisedSolution || ++callsSinceScan >= scanInterval) {
            List<PosteAffectation> postes = solution.getPostes() == null ? List.of() : solution.getPostes();
            memoisedSolution = solution;
            callsSinceScan = 0;
            scanInterval = Math.max(1, postes.size());
            doubleBooked = scan(postes);
        }
        return doubleBooked;
    }

    static Set<PosteAffectation> scan(List<PosteAffectation> postes) {
        Map<Animateur, List<PosteAffectation>> byAnimateur = new HashMap<>();
        for (PosteAffectation poste : postes) {
            // The seats pasDeChevauchementHoraire reads (horaireConnu).
            if (poste.getAnimateur() != null
                    && poste.getCreneau() != null
                    && poste.getCreneau().getDate() != null
                    && poste.getCreneau().getHeureDebut() != null) {
                byAnimateur
                        .computeIfAbsent(poste.getAnimateur(), animateur -> new ArrayList<>())
                        .add(poste);
            }
        }
        Set<PosteAffectation> found = Collections.newSetFromMap(new IdentityHashMap<>());
        for (List<PosteAffectation> held : byAnimateur.values()) {
            if (held.size() < 2) {
                continue;
            }
            held.sort(Comparator.comparing(DoubleBookedPosteFilter::start));
            // Sorted by start, a seat can only overlap the ones after it until
            // one starts at or past its end.
            for (int i = 0; i < held.size(); i++) {
                PosteAffectation first = held.get(i);
                LocalDateTime firstStart = start(first);
                LocalDateTime firstEnd = firstStart.plusMinutes(first.getDureeEffectiveMinutes());
                for (int j = i + 1; j < held.size(); j++) {
                    PosteAffectation second = held.get(j);
                    LocalDateTime secondStart = start(second);
                    if (!secondStart.isBefore(firstEnd)) {
                        break;
                    }
                    if (firstStart.isBefore(secondStart.plusMinutes(second.getDureeEffectiveMinutes()))) {
                        found.add(first);
                        found.add(second);
                    }
                }
            }
        }
        return found;
    }

    /** The seat's own hours, as the constraint reads them: a narrowed seat is not its whole timeslot. */
    private static LocalDateTime start(PosteAffectation poste) {
        return LocalDateTime.of(poste.getCreneau().getDate(), poste.heureDebutEffectif());
    }
}
