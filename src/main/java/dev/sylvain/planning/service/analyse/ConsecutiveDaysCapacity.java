package dev.sylvain.planning.service.analyse;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.PastHorizon;
import dev.sylvain.planning.domain.Stand;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
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
 * for everybody. A day of the frozen past (ADR 0044) is history: the grid
 * reading counts the people the plan actually had there rather than its
 * floor — an empty past seat is never charged, so the floor would overstate
 * a demand the solver no longer meets, and nobody can be taken off a day
 * already worked — and a window ending in the past is not judged at all, the
 * rule never charging a run that is over.</p>
 */
final class ConsecutiveDaysCapacity {

    /** A margin under this share of the demand is reported as tight. */
    static final double TIGHT_MARGIN_RATIO = 0.10;

    private final int cap;
    private final List<Animateur> animateurs;
    private final List<Creneau> creneaux;
    private final Map<LocalDate, Integer> floors;
    private final Set<LocalDate> frozen;

    private ConsecutiveDaysCapacity(
            int cap,
            List<Animateur> animateurs,
            List<Creneau> creneaux,
            Map<LocalDate, Integer> floors,
            Set<LocalDate> frozen) {
        this.cap = cap;
        this.animateurs = animateurs;
        this.creneaux = creneaux;
        this.floors = floors;
        this.frozen = frozen;
    }

    /**
     * The grid read once — its floors and its frozen days — for both readings,
     * {@link #grid} and {@link #employed}: each costs an opening profile per
     * stand and timeslot, and the analysis runs on every load of three screens.
     */
    static ConsecutiveDaysCapacity of(
            int cap, List<Animateur> animateurs, List<Stand> stands, List<Creneau> creneaux, PastHorizon horizon) {
        return new ConsecutiveDaysCapacity(
                cap,
                animateurs,
                creneaux,
                floors(stands, creneaux),
                FeasibilityAnalyzer.frozenDays(creneaux, stands, horizon));
    }

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

    /** Whether some day of the grid is in the frozen past — the one case {@link #grid} needs the plan for. */
    boolean hasFrozenDays() {
        return !frozen.isEmpty();
    }

    /**
     * The worst window of the grid, or {@code null} when every window keeps a
     * comfortable margin — or when there is nothing to judge (no cap, no
     * timeslot, an event shorter than a window).
     *
     * @param employedByDay the people the plan in place employs each day,
     *        read for the frozen days only; {@code null} or a day missing
     *        counts nobody there, which keeps the condition necessary — it can
     *        miss a proof, never invent one
     */
    Window grid(Map<LocalDate, ? extends Collection<String>> employedByDay) {
        Map<LocalDate, Integer> demand = new HashMap<>(floors);
        for (LocalDate jour : frozen) {
            Collection<String> people = employedByDay == null ? null : employedByDay.get(jour);
            demand.put(jour, people == null ? 0 : people.size());
        }
        return worst(demand);
    }

    /**
     * The worst window of a plan in place whose days employ more people than
     * its rest days allow, or {@code null} when none does. A past day counts
     * whoever the plan actually had there.
     *
     * @param employedByDay the people each day of the plan employs, by date
     */
    Window employed(Map<LocalDate, ? extends Collection<String>> employedByDay) {
        if (employedByDay == null || employedByDay.isEmpty()) {
            return null;
        }
        Map<LocalDate, Integer> employed = new HashMap<>();
        employedByDay.forEach((date, people) -> employed.put(date, people == null ? 0 : people.size()));
        Window window = worst(employed);
        return window == null || window.margin() >= 0 ? null : window;
    }

    /**
     * Scans every window of {@code cap + 1} days and keeps the one with the
     * smallest margin, counting how many others are short or tight too. Returns
     * {@code null} when the worst margin is comfortable.
     *
     * @param demandByDay what each day uses — its floor, or what a plan employs
     */
    private Window worst(Map<LocalDate, Integer> demandByDay) {
        if (cap <= 0) {
            return null;
        }
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
            // The days somebody is needed on bound what an animateur can
            // offer: a day nobody is needed is a rest day for all.
            needed[i] = floors.getOrDefault(date, 0) > 0;
        }
        List<boolean[]> availability = new ArrayList<>();
        for (Animateur animateur : animateurs) {
            boolean[] available = new boolean[days];
            for (int i = 0; i < days; i++) {
                available[i] = needed[i] && !animateur.isIndisponibleOn(first.plusDays(i));
            }
            availability.add(available);
        }

        Window worst = null;
        int tight = 0;
        for (int start = 0; start + cap < days; start++) {
            int end = start + cap;
            if (isPast(first.plusDays(end))) {
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

    /**
     * Whether a window ending on {@code date} is over: the day is frozen, or
     * it holds no seat and every frozen day comes after it — a rest day
     * between two worked days already behind us.
     */
    private boolean isPast(LocalDate date) {
        return frozen.contains(date)
                || (!floors.containsKey(date) && frozen.stream().anyMatch(day -> day.isAfter(date)));
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
        Map<LocalDate, Integer> result = new HashMap<>();
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
            result.put(date, peak);
        });
        return result;
    }
}
