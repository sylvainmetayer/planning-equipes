package dev.sylvain.planning.service.analyse;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

/**
 * The least number of people who can cover a sequence of days, each day
 * needing a given number of distinct people, when nobody works more than six
 * days in an ISO week (art. L3132-1, {@code PlafondsLegauxMajeurs.JOURS_TRAVAILLES_MAX_PAR_SEMAINE}) nor — when the edition holds that rule
 * hard — more than {@code joursConsecutifsMax} days in a row.
 *
 * <p>The answer is <b>exact</b> for that relaxation, not a bound on a bound.
 * A person's calendar is described by their rest days. Those rest days must
 * hit every complete ISO week of the event, and leave no run of worked days
 * longer than the consecutive cap. Seen from left to right, the rest days of
 * one person are therefore a path {@code S → r1 → r2 → … → T} in which every
 * hop is allowed: the days it skips form no complete week and are no more than
 * the cap. Day {@code d} can host at most {@code N − besoin(d)} rest days, or
 * fewer than {@code besoin(d)} people would work that day. {@code N} people fit
 * if and only if {@code N} such paths fit in those capacities together, which
 * is a maximum flow with node capacities: integral, so a flow of {@code N} is
 * {@code N} actual calendars and not a fractional promise.</p>
 *
 * <p>That is what the week-by-week division « jours-personne ÷ 6 » cannot see:
 * a rest taken early in a week may leave a run of seven days straddling the
 * next one, and an event ending with two busy days after a busy week forbids
 * resting on the first days of that week. A full week needing six people a
 * day, then two days needing seven: the division says seven, and with six days
 * in a row at most it takes eight — {@code StaffingAnalyzerTest} pins it.</p>
 *
 * <p>Pure and static, like the other bounds of {@link StaffingAnalyzer}: no
 * dependency on a solver library, a few hundred nodes for the longest editions
 * the scenarios describe.</p>
 */
final class DaySequenceFloor {

    private DaySequenceFloor() {}

    /**
     * The least {@code N} at or above {@code lowerBound} for which the days can
     * be covered, {@code 0} when no day needs anybody.
     *
     * @param besoinParJour        distinct people each day needs; a calendar
     *                             day between two of them that is absent needs
     *                             nobody, and is a rest day anybody may take
     * @param joursConsecutifsMax  the rolling cap on days worked in a row, or
     *                             {@code null} when the edition does not hold
     *                             one hard — only the weekly six days then apply
     * @param lowerBound           a floor already proved by the caller, where
     *                             the search starts
     */
    static int floor(Map<LocalDate, Integer> besoinParJour, Integer joursConsecutifsMax, int lowerBound) {
        NavigableMap<LocalDate, Integer> besoins = new TreeMap<>(besoinParJour);
        besoins.values().removeIf(besoin -> besoin == null || besoin <= 0);
        if (besoins.isEmpty()) {
            return 0;
        }
        int[] need = needs(besoins);
        int maxNeed = Arrays.stream(need).max().orElse(0);
        int low = Math.max(lowerBound, maxNeed);
        Graph shape = Graph.of(need, besoins.firstKey(), joursConsecutifsMax);
        if (shape.feasible(need, low)) {
            return low;
        }
        // Doubling, then bisection: feasibility is monotone in N, since a
        // larger team has one more calendar to place and one more rest slot on
        // every day to place it in.
        int high = Math.max(low + 1, low * 2);
        while (!shape.feasible(need, high)) {
            low = high;
            high = high * 2;
        }
        while (high - low > 1) {
            int middle = (low + high) >>> 1;
            if (shape.feasible(need, middle)) {
                high = middle;
            } else {
                low = middle;
            }
        }
        return high;
    }

    /** One entry per calendar day from the first day needing somebody to the last, zero in between. */
    private static int[] needs(NavigableMap<LocalDate, Integer> besoins) {
        LocalDate first = besoins.firstKey();
        int days = (int) ChronoUnit.DAYS.between(first, besoins.lastKey()) + 1;
        int[] need = new int[days];
        besoins.forEach((date, besoin) -> need[(int) ChronoUnit.DAYS.between(first, date)] = besoin);
        return need;
    }

    /**
     * The allowed hops between two rest positions, which depend on the
     * calendar only. Position {@code -1} is the source (everybody is rested
     * before the event), {@code days} the sink.
     */
    private record Graph(int days, List<int[]> hops) {

        static Graph of(int[] need, LocalDate first, Integer joursConsecutifsMax) {
            int days = need.length;
            // Mondays of the ISO weeks lying entirely inside the event: the
            // only weeks whose rest must fall on an event day. A week cut by
            // the event's start or end has days off the event, rest for all.
            boolean[] fullWeekStartsAt = new boolean[days];
            for (int day = 0; day + 6 < days; day++) {
                fullWeekStartsAt[day] =
                        DayOfWeek.MONDAY.equals(first.plusDays(day).getDayOfWeek());
            }
            int maxRun =
                    joursConsecutifsMax == null || joursConsecutifsMax <= 0 ? Integer.MAX_VALUE : joursConsecutifsMax;
            List<int[]> hops = new ArrayList<>();
            for (int from = -1; from < days; from++) {
                for (int to = from + 1; to <= days; to++) {
                    int worked = to - from - 1;
                    if (worked > maxRun || containsFullWeek(fullWeekStartsAt, from, to)) {
                        // Every later "to" skips at least the same days.
                        break;
                    }
                    hops.add(new int[] {from, to});
                }
            }
            return new Graph(days, hops);
        }

        /** Whether some complete ISO week lies strictly between the two rest positions. */
        private static boolean containsFullWeek(boolean[] fullWeekStartsAt, int from, int to) {
            // A week of seven days lies inside (from, to) when its Sunday, six
            // days after its Monday, comes before "to".
            for (int monday = from + 1; monday + 6 < to; monday++) {
                if (fullWeekStartsAt[monday]) {
                    return true;
                }
            }
            return false;
        }

        /**
         * Whether {@code n} calendars fit. Node {@code d} is split into an
         * entry {@code 2d+2} and an exit {@code 2d+3} joined by the day's rest
         * capacity; the source is {@code 0}, the sink {@code 1}.
         */
        boolean feasible(int[] need, int n) {
            FlowNetwork network = new FlowNetwork(2 * days + 2);
            for (int day = 0; day < days; day++) {
                int capacity = n - need[day];
                if (capacity < 0) {
                    return false;
                }
                network.add(entry(day), exit(day), capacity);
            }
            for (int[] hop : hops) {
                int from = hop[0] < 0 ? 0 : exit(hop[0]);
                int to = hop[1] >= days ? 1 : entry(hop[1]);
                network.add(from, to, n);
            }
            return network.maxFlow(0, 1, n) >= n;
        }

        private static int entry(int day) {
            return 2 * day + 2;
        }

        private static int exit(int day) {
            return 2 * day + 3;
        }
    }

    /** Dinic's maximum flow, stopped once {@code limit} units are through. */
    private static final class FlowNetwork {

        private final List<List<int[]>> adjacency = new ArrayList<>();
        // An edge is {to, capacity, index of the reverse edge in the adjacency of "to"}.
        private int[] level;
        private int[] cursor;

        FlowNetwork(int nodes) {
            for (int node = 0; node < nodes; node++) {
                adjacency.add(new ArrayList<>());
            }
        }

        void add(int from, int to, int capacity) {
            adjacency.get(from).add(new int[] {to, capacity, adjacency.get(to).size()});
            adjacency.get(to).add(new int[] {from, 0, adjacency.get(from).size() - 1});
        }

        int maxFlow(int source, int sink, int limit) {
            int flow = 0;
            while (flow < limit && levels(source, sink)) {
                cursor = new int[adjacency.size()];
                int pushed;
                while (flow < limit && (pushed = push(source, sink, limit - flow)) > 0) {
                    flow += pushed;
                }
            }
            return flow;
        }

        private boolean levels(int source, int sink) {
            level = new int[adjacency.size()];
            Arrays.fill(level, -1);
            level[source] = 0;
            ArrayDeque<Integer> queue = new ArrayDeque<>();
            queue.add(source);
            while (!queue.isEmpty()) {
                int node = queue.poll();
                for (int[] edge : adjacency.get(node)) {
                    if (edge[1] > 0 && level[edge[0]] < 0) {
                        level[edge[0]] = level[node] + 1;
                        queue.add(edge[0]);
                    }
                }
            }
            return level[sink] >= 0;
        }

        private int push(int node, int sink, int amount) {
            if (node == sink) {
                return amount;
            }
            List<int[]> edges = adjacency.get(node);
            for (; cursor[node] < edges.size(); cursor[node]++) {
                int[] edge = edges.get(cursor[node]);
                if (edge[1] > 0 && level[edge[0]] == level[node] + 1) {
                    int pushed = push(edge[0], sink, Math.min(amount, edge[1]));
                    if (pushed > 0) {
                        edge[1] -= pushed;
                        adjacency.get(edge[0]).get(edge[2])[1] += pushed;
                        return pushed;
                    }
                }
            }
            return 0;
        }
    }
}
