package dev.sylvain.planning.solver;

import ai.timefold.solver.core.impl.heuristic.selector.common.decorator.SelectionFilter;
import ai.timefold.solver.core.impl.score.director.ScoreDirector;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Seats worth ruining together with an unfilled one: the unfilled seats
 * themselves, and every seat whose créneau overlaps one of theirs in time.
 *
 * <p>Filling a hole at a saturated hour takes a chain — A leaves a seat for
 * the hole, B free at that hour takes A's seat — whose every link is
 * hard-neutral and, once the published plan has a price (ADR 0025), costs
 * medium: the local search sees the intermediate state and refuses it. The
 * ruin-and-recreate move selector of the feasibility phase evaluates the
 * chain as one move, provided the seats it ruins are the ones the chain runs
 * through: those live at the hole's hour, which is what this filter narrows
 * the ruined set to.</p>
 *
 * <p><b>The hours of the holes are memoised.</b> Scanning the whole plan on
 * every call is what made this filter pathological: the feasibility phase ends
 * on <em>feasibility</em>, not on "no hole left", and most hard rules are
 * broken by over-assignment — so "every seat filled, still infeasible" is an
 * ordinary state, and the one where a filter that never accepts anything makes
 * Timefold try {@code entityCount * 10} times before giving up. Measured on
 * 3 503 seats: 132 ms per attempted move, 80 % of a 60 s budget spent proving
 * there was nothing to ruin. The memo makes the empty case a constant-time
 * refusal.</p>
 *
 * <p>It is refreshed at most once every {@code postes.size()} calls, so the
 * amortised cost is one scan per full sweep. Between two refreshes the answer
 * may lag the solution by a few moves: harmless by construction — the ruin
 * move is valid whatever it ruins, this filter only says where it is worth
 * looking.</p>
 */
public final class HoleNeighbourPosteFilter implements SelectionFilter<PlanningEvenement, PosteAffectation> {

    /** Hole hours by day, and the solution they were read from. */
    private PlanningEvenement solutionMemoisee;

    private Map<LocalDate, List<long[]>> trousParJour = Map.of();
    private int appelsDepuisLeScan;
    private int intervalleDeScan = 1;

    @Override
    public boolean accept(ScoreDirector<PlanningEvenement> scoreDirector, PosteAffectation poste) {
        // A pinned seat cannot be ruined, whatever it holds: a lock, or a past
        // seat (ADR 0044) — including a past hole, which no move can fill.
        if (poste.getCreneau() == null || poste.isVerrouille()) {
            return false;
        }
        // An empty renfort is not a hole (issue #505): ruining its neighbours
        // to fill it would spend the ruin-and-recreate budget on a seat nobody
        // is missing on.
        if (poste.getAnimateur() == null) {
            return !poste.isOptionnel();
        }
        Map<LocalDate, List<long[]>> trous = trous(scoreDirector.getWorkingSolution());
        if (trous.isEmpty()) {
            return false;
        }
        LocalDate date = poste.getCreneau().getDate();
        if (date == null) {
            return false;
        }
        long[] borne = bornes(poste.getCreneau());
        for (List<long[]> duJour : voisinages(trous, date)) {
            for (long[] trou : duJour) {
                if (borne[0] < trou[1] && trou[0] < borne[1]) {
                    return true;
                }
            }
        }
        return false;
    }

    /** The day itself and its neighbours: a créneau ending after midnight lands on the next one. */
    private static List<List<long[]>> voisinages(Map<LocalDate, List<long[]>> trous, LocalDate date) {
        List<List<long[]>> voisinages = new ArrayList<>(3);
        for (LocalDate jour : List.of(date.minusDays(1), date, date.plusDays(1))) {
            List<long[]> duJour = trous.get(jour);
            if (duJour != null) {
                voisinages.add(duJour);
            }
        }
        return voisinages;
    }

    private Map<LocalDate, List<long[]>> trous(PlanningEvenement solution) {
        if (solution != solutionMemoisee || ++appelsDepuisLeScan >= intervalleDeScan) {
            List<PosteAffectation> postes = solution.getPostes() == null ? List.of() : solution.getPostes();
            solutionMemoisee = solution;
            appelsDepuisLeScan = 0;
            intervalleDeScan = Math.max(1, postes.size());
            trousParJour = scanner(postes);
        }
        return trousParJour;
    }

    private static Map<LocalDate, List<long[]>> scanner(List<PosteAffectation> postes) {
        Map<LocalDate, List<long[]>> parJour = new HashMap<>();
        for (PosteAffectation poste : postes) {
            // A pinned hole is not a hole worth looking at: the past ones
            // (ADR 0044) would otherwise keep the memo from ever being empty,
            // and aim the ruin at hours where every neighbour is pinned too.
            // An empty renfort (issue #505) is not a hole either, and it is
            // the one that would keep the memo full for the whole solve: a
            // stand declaring a capacity it cannot staff has empty renforts
            // on every hour it opens.
            if (poste.getAnimateur() != null
                    || poste.isVerrouille()
                    || poste.isOptionnel()
                    || poste.getCreneau() == null
                    || poste.getCreneau().getDate() == null) {
                continue;
            }
            parJour.computeIfAbsent(poste.getCreneau().getDate(), jour -> new ArrayList<>())
                    .add(bornes(poste.getCreneau()));
        }
        return parJour;
    }

    /** Start and end of the slot in minutes since its own day's midnight; the end may run past it. */
    private static long[] bornes(Creneau creneau) {
        LocalTime debut = creneau.getHeureDebut();
        long depart = debut == null ? 0 : debut.toSecondOfDay() / 60L;
        LocalDate date = creneau.getDate();
        long jour = date == null ? 0 : date.toEpochDay() * 24 * 60;
        return new long[] {jour + depart, jour + depart + creneau.getDureeMinutes()};
    }

    /** Two slots share a minute — the same day or across midnight. */
    static boolean overlap(Creneau a, Creneau b) {
        long[] premier = bornes(a);
        long[] second = bornes(b);
        return premier[0] < second[1] && second[0] < premier[1];
    }
}
