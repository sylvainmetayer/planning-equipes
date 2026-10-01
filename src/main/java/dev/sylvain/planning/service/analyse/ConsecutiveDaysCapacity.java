package dev.sylvain.planning.service.analyse;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.PastHorizon;
import dev.sylvain.planning.domain.Stand;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Whether a cap of {@code k} days in a row leaves enough person-days to staff
 * the grid — the arithmetic behind {@code maxJoursConsecutifsTravaillesDur},
 * answered in plain Java, before any solve.
 *
 * <p>A <b>rolling</b> cap of {@code k} days means everybody rests at least once
 * in <em>every</em> window of {@code k + 1} consecutive calendar days. So, for
 * any such window {@code W}:</p>
 *
 * <pre>
 * demand(W) = Σ floor(d) over the days d of W
 * supply(W) = Σ min(k, days of W on which a is available and somebody is needed) over the animateurs a
 * </pre>
 *
 * <p>{@code floor(d)} is the least number of distinct people day {@code d}
 * can be held with: its peak of simultaneous seats, read from
 * {@link Creneau#segmentsOuverts(Stand)} and {@link Creneau#siegesSegment(int)}
 * — the very methods seat generation reads, so what this check announces and
 * what the solver must fill cannot be two different numbers. Nobody holds two
 * seats at once, so a day needs at least that many people; nothing says it
 * needs more, which is what keeps the condition <em>necessary</em> only.</p>
 *
 * <p>{@code supply(W) < demand(W)} is a <b>proof</b>: no plan holds the cap,
 * whatever the solver does. The converse proves nothing — skills, hours, the
 * legal rules and a day held by more than its floor all eat into the margin —
 * hence the second reading, a margin thin enough to be worth a warning.</p>
 *
 * <p>{@link #employed} answers the same question on a plan in place, with the
 * people each day really employs instead of the floor: when even that fails,
 * no rearrangement of the rest days alone can hold the cap without changing
 * how many people each day employs.</p>
 *
 * <p>Days are counted the way the rule counts them: calendar days, a timeslot
 * belonging to the day it starts on, a day without timeslot being a rest day
 * for everybody. A window entirely in the frozen past is skipped: the rule
 * never charges a run that is over (ADR 0044).</p>
 */
final class ConsecutiveDaysCapacity {

    /** A margin under this share of the demand is reported as tight. */
    static final double TIGHT_MARGIN_RATIO = 0.10;

    private ConsecutiveDaysCapacity() {}

    /**
     * The window a check is about: its days, what it needs and what it can
     * offer, in person-days.
     *
     * @param tightWindows the other windows also short or tight — the cause is
     *                     reported once, on the worst one, and says how many
     *                     share its fate
     */
    record Window(LocalDate debut, LocalDate fin, int demand, int supply, int tightWindows) {

        int margin() {
            return supply - demand;
        }
    }

    /**
     * The worst window of the grid against a cap of {@code cap} days, or
     * {@code null} when every window keeps a comfortable margin — or when there
     * is nothing to judge (no cap, no timeslot, an event shorter than a window).
     */
    static Window grid(
            int cap, List<Animateur> animateurs, List<Stand> stands, List<Creneau> creneaux, PastHorizon horizon) {
        if (cap <= 0 || creneaux.isEmpty()) {
            return null;
        }
        Map<LocalDate, Integer> floors = floors(stands, creneaux);
        return worst(cap, floors, animateurs, creneaux, stands, horizon, floors);
    }

    /**
     * The worst window of a plan in place whose days employ more people than
     * its rest days allow, or {@code null} when none does.
     *
     * @param employedByDay the people each day of the plan employs, by date
     */
    static Window employed(
            int cap,
            List<Animateur> animateurs,
            List<Stand> stands,
            List<Creneau> creneaux,
            Map<LocalDate, ? extends Collection<String>> employedByDay,
            PastHorizon horizon) {
        if (cap <= 0 || creneaux.isEmpty() || employedByDay == null || employedByDay.isEmpty()) {
            return null;
        }
        Map<LocalDate, Integer> needed = floors(stands, creneaux);
        Map<LocalDate, Integer> employed = new HashMap<>();
        employedByDay.forEach((date, people) -> employed.put(date, people == null ? 0 : people.size()));
        Window window = worst(cap, employed, animateurs, creneaux, stands, horizon, needed);
        return window == null || window.margin() >= 0 ? null : window;
    }

    /**
     * Scans every window of {@code cap + 1} days and keeps the one with the
     * smallest margin, counting how many others are short or tight too. Returns
     * {@code null} when the worst margin is comfortable.
     *
     * @param demandByDay what each day uses — its floor, or what a plan employs
     * @param neededByDay the days somebody is needed on, which bound what an
     *                    animateur can offer: a day nobody is needed is a rest
     *                    day for all
     */
    private static Window worst(
            int cap,
            Map<LocalDate, Integer> demandByDay,
            List<Animateur> animateurs,
            List<Creneau> creneaux,
            List<Stand> stands,
            PastHorizon horizon,
            Map<LocalDate, Integer> neededByDay) {
        LocalDate first = null;
        LocalDate last = null;
        for (Creneau creneau : creneaux) {
            LocalDate date = creneau.getDate();
            if (date != null) {
                first = first == null || date.isBefore(first) ? date : first;
                last = last == null || date.isAfter(last) ? date : last;
            }
        }
        if (first == null) {
            return null;
        }
        int days = (int) ChronoUnit.DAYS.between(first, last) + 1;
        if (days <= cap) {
            // No window of cap + 1 days fits in the event: nobody can run past
            // the cap, whatever the plan.
            return null;
        }
        int[] demand = new int[days];
        boolean[] needed = new boolean[days];
        for (int i = 0; i < days; i++) {
            LocalDate date = first.plusDays(i);
            demand[i] = demandByDay.getOrDefault(date, 0);
            needed[i] = neededByDay.getOrDefault(date, 0) > 0;
        }
        List<boolean[]> availability = new ArrayList<>();
        for (Animateur animateur : animateurs) {
            boolean[] available = new boolean[days];
            for (int i = 0; i < days; i++) {
                available[i] = needed[i] && !animateur.isIndisponibleOn(first.plusDays(i));
            }
            availability.add(available);
        }
        Set<LocalDate> frozen = frozenDays(creneaux, stands, horizon);

        Window worst = null;
        int tight = 0;
        for (int start = 0; start + cap < days; start++) {
            int end = start + cap;
            if (frozen.contains(first.plusDays(end))) {
                continue;
            }
            int windowDemand = 0;
            for (int i = start; i <= end; i++) {
                windowDemand += demand[i];
            }
            int windowSupply = 0;
            for (boolean[] available : availability) {
                int count = 0;
                for (int i = start; i <= end; i++) {
                    if (available[i]) {
                        count++;
                    }
                }
                windowSupply += Math.min(cap, count);
            }
            int margin = windowSupply - windowDemand;
            if (isTight(margin, windowDemand)) {
                tight++;
            }
            if (worst == null || margin < worst.margin()) {
                worst = new Window(first.plusDays(start), first.plusDays(end), windowDemand, windowSupply, 0);
            }
        }
        if (worst == null || !isTight(worst.margin(), worst.demand())) {
            return null;
        }
        return new Window(worst.debut(), worst.fin(), worst.demand(), worst.supply(), tight - 1);
    }

    /** Short, or within {@link #TIGHT_MARGIN_RATIO} of the demand. */
    static boolean isTight(int margin, int demand) {
        return margin < 0 || margin < demand * TIGHT_MARGIN_RATIO;
    }

    /**
     * The least number of distinct people each day can be held with: its peak
     * of simultaneous seats over every open segment of every stand, swept on
     * the minutes from the day's midnight — a timeslot crossing midnight stays
     * on the day it starts on, as the rule counts it.
     */
    static Map<LocalDate, Integer> floors(List<Stand> stands, List<Creneau> creneaux) {
        Map<LocalDate, TreeMap<Integer, Integer>> deltas = new HashMap<>();
        for (Creneau creneau : creneaux) {
            if (creneau.getDate() == null || creneau.getHeureDebut() == null) {
                continue;
            }
            int origin = creneau.getHeureDebut().toSecondOfDay() / 60;
            for (Stand stand : stands) {
                for (Creneau.SegmentOuvert segment : creneau.segmentsOuverts(stand)) {
                    int seats = creneau.siegesSegment(segment.effectif());
                    if (seats <= 0 || segment.finMinutes() <= segment.debutMinutes()) {
                        continue;
                    }
                    TreeMap<Integer, Integer> day = deltas.computeIfAbsent(creneau.getDate(), d -> new TreeMap<>());
                    day.merge(origin + segment.debutMinutes(), seats, Integer::sum);
                    day.merge(origin + segment.finMinutes(), -seats, Integer::sum);
                }
            }
        }
        Map<LocalDate, Integer> floors = new HashMap<>();
        deltas.forEach((date, day) -> {
            int current = 0;
            int peak = 0;
            // A segment ending at a minute and another starting at that same
            // minute never overlap: the TreeMap sums both deltas on one key,
            // so the hand-over is read as what it is.
            for (int delta : day.values()) {
                current += delta;
                peak = Math.max(peak, current);
            }
            floors.put(date, peak);
        });
        return floors;
    }

    /** The days every timeslot of which had started at {@code horizon}; none without one. */
    private static Set<LocalDate> frozenDays(List<Creneau> creneaux, List<Stand> stands, PastHorizon horizon) {
        if (horizon == null) {
            return Set.of();
        }
        Map<LocalDate, Boolean> byDay = new HashMap<>();
        for (Creneau creneau : creneaux) {
            if (creneau.getDate() != null) {
                byDay.merge(
                        creneau.getDate(),
                        FeasibilityAnalyzer.hasStarted(creneau, stands, horizon),
                        Boolean::logicalAnd);
            }
        }
        Set<LocalDate> frozen = new HashSet<>();
        byDay.forEach((date, started) -> {
            if (started) {
                frozen.add(date);
            }
        });
        // A day without timeslot inside the event is frozen once its date is
        // behind today: nothing to start, and nothing after it to wait for.
        if (!byDay.isEmpty()) {
            LocalDate debut = byDay.keySet().stream().min(LocalDate::compareTo).orElseThrow();
            LocalDate fin = byDay.keySet().stream().max(LocalDate::compareTo).orElseThrow();
            for (LocalDate date = debut; !date.isAfter(fin); date = date.plusDays(1)) {
                if (!byDay.containsKey(date) && horizon.hasStarted(date, LocalTime.MAX)) {
                    frozen.add(date);
                }
            }
        }
        return frozen;
    }
}
