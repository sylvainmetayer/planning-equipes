package dev.sylvain.planning.solver.handsearch;

import static dev.sylvain.planning.solver.handsearch.FlatProblem.FORFAIT;
import static dev.sylvain.planning.solver.handsearch.FlatProblem.MEDIUM;

import dev.sylvain.planning.domain.PlafondsLegauxMajeurs;
import dev.sylvain.planning.domain.PlafondsLegauxMineurs;
import dev.sylvain.planning.solver.WalkingTime;
import dev.sylvain.planning.solver.handsearch.FlatProblem.PairTerm;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.Arrays;

/**
 * The score of a plan, kept up to date move by move (ADR 0087): the same 47
 * rules as {@code PlanningConstraintProvider}, written a second time so that a
 * move costs the re-reading of the one or two animateurs it touches instead of
 * a pass through a constraint network.
 *
 * <p>A rule takes one of four shapes here, and that is all there is to know to
 * add one:</p>
 * <ul>
 *   <li>one seat and its holder, nothing else — {@link FlatProblem#staticTerms},
 *       read from a table;</li>
 *   <li>the sorted seats of one animateur — {@link #eval};</li>
 *   <li>a count shared by a line, a stand, a timeslot or the whole plan —
 *       {@link #lineCost}, {@link #standCost}, {@link #slotCost},
 *       {@link #forcedCost}, {@link #balance}, kept by {@link #set};</li>
 *   <li>one animateur's cost reading somebody else's seats — a relayed break,
 *       the adult a minor needs, an ad hoc pair: {@link #set} then re-reads
 *       whoever depends on the seat that moved.</li>
 * </ul>
 *
 * <p>A cost is two {@code long}s: the hard points, and one number for medium
 * above soft ({@link FlatProblem#MEDIUM}). Every method that returns a cost
 * returns the second and adds the first to {@link #hardOut}, which its caller
 * reads; {@link #levels} is the exact score, level by level, and
 * {@link #checkRange} refuses a plan whose medium and soft no longer fit side
 * by side. Nothing here is trusted on its own word: the solver
 * that drives this class has Timefold score the final plan again.</p>
 *
 * <p>Not thread-safe: one instance per solve.</p>
 */
final class IncrementalScore {

    private static final int[] NONE = {};

    private static final MathContext SIX_DIGITS = new MathContext(6, RoundingMode.HALF_EVEN);

    /** The soft and medium points beyond which their shared number stops ordering plans exactly. */
    static final long SOFT_LIMIT = MEDIUM / 2;

    static final long MEDIUM_LIMIT = Long.MAX_VALUE / MEDIUM / 4;

    final FlatProblem flat;
    private final long[] unit;
    private final long[] hardUnit;

    // the plan: who holds each seat, and each animateur's seats sorted by start
    final int[] assign;
    final int[][] seatsOf;
    final int[] countOf;
    final long[] costOf;
    final long[] hardOf;
    int unassignedMovable;

    /** Seats that had a holder when the problem was handed in and are not with them now. */
    int away;

    /** Empty seats still ahead, pinned ones included. */
    int emptyAhead;

    /** Hard points an empty seat weighs for the search beyond the one the score counts. */
    long emptyExtra;

    /** Set while a candidate is only being weighed: the others it would touch are not re-read. */
    boolean alone;

    // shared counts: per line, per premium stand, per timeslot, per forced assignment, and the two balances
    private long shared;
    private long sharedHard;
    private long globalHard;
    private long sumSq;
    private long held;
    private long aheadHeld;
    private long dSumSq;
    private long dHeld;
    private long dAhead;
    private int active;
    private int dActive;
    private final int[] dCount;
    private final int[] lHeld;
    private final int[] lAhead;
    private final int[] lRef;
    private final int[] lDeb;
    private final int[] lMin;
    private final int[] lMaj;
    private final int[] stDistinct;
    private final int[] stAhead;
    private final int[] crOcc;
    private final int[] crAhead;
    private final int[][] standCount;
    private final int[][] ninjaCount;
    private final int[] fSat;
    private final int[] succHeld;
    /** Breaks each animateur owes: somebody owing none cannot gain or lose a relay. */
    private final int[] dues;

    // the move under way: who it touched and what they cost before, and how to take it back
    private int[] aff = new int[64];
    private long[] affOld = new long[64];
    private long[] affOldHard = new long[64];
    private int[] affDues = new int[64];
    private int nAff;
    private final int[] uS = new int[8];
    private final int[] uA = new int[8];
    private int nU;
    private long globalBefore;
    private long globalHardBefore;

    /** Hard points charged since a caller last reset it: the first half of every cost computed here. */
    private long hardOut;

    /** The hard points the last {@link #finish} changed, beside the delta it returned. */
    long deltaHard;

    private boolean tracking;

    // what eval() writes while it reads one animateur
    private final int[] dFrom;
    private final int[] dTo;
    private final int[] dStart;
    private final int[] dEnd;
    private final boolean[] dLive;
    private final int[] wMaj;
    private final int[] wMin;
    private final int[] wDays;
    private final int[] wMinorMask;
    private final boolean[] wAheadMaj;
    private final boolean[] wAheadMin;
    private final boolean[] wAhead;
    private final boolean[] wSeatAhead;
    /** The stretches of time eval() found its animateur taken on: {@code occS} and {@code occE} up to here. */
    private int occ;

    private int[] occS = new int[64];
    private int[] occE = new int[64];
    private final int[] tCnt;
    private final int[] tAhead;
    private final int[] dayStartOf;
    private final int[] dayEndOf;
    private final int[] otherStart;
    private final int[] otherEnd;
    private final boolean[] dayAhead;
    private final boolean[] otherAhead;
    private final boolean[] dayHas;
    private final boolean[] otherHas;

    /** Filled by a pass under {@link #explain}: units of each rule, before its weight. */
    private final long[] acc = new long[Rule.values().length];

    private boolean explain;

    IncrementalScore(FlatProblem flat) {
        this.flat = flat;
        this.unit = flat.unit;
        this.hardUnit = flat.hardUnit;
        int nS = flat.nS;
        int nA = flat.nA;
        assign = new int[nS];
        Arrays.fill(assign, -1);
        seatsOf = new int[nA][16];
        countOf = new int[nA];
        dCount = new int[nA];
        costOf = new long[nA];
        hardOf = new long[nA];
        dues = new int[nA];
        lHeld = new int[flat.nLines];
        lAhead = new int[flat.nLines];
        lRef = new int[flat.nLines];
        lDeb = new int[flat.nLines];
        lMin = new int[flat.nLines];
        lMaj = new int[flat.nLines];
        stDistinct = new int[flat.nStands];
        stAhead = new int[flat.nStands];
        standCount = new int[flat.nStands][nA];
        crOcc = new int[flat.nSlots];
        crAhead = new int[flat.nSlots];
        ninjaCount = new int[flat.nSlots][flat.ninjaTotal == 0 ? 0 : nA];
        fSat = new int[flat.fExempt.length];
        succHeld = new int[nS];
        dFrom = new int[flat.nDays];
        dTo = new int[flat.nDays];
        dStart = new int[flat.nDays];
        dEnd = new int[flat.nDays];
        dLive = new boolean[flat.nDays];
        wMaj = new int[flat.nWeeks];
        wMin = new int[flat.nWeeks];
        wDays = new int[flat.nWeeks];
        wMinorMask = new int[flat.nWeeks];
        wAheadMaj = new boolean[flat.nWeeks];
        wAheadMin = new boolean[flat.nWeeks];
        wAhead = new boolean[flat.nWeeks];
        wSeatAhead = new boolean[flat.nWeeks];
        tCnt = new int[flat.nTypo];
        tAhead = new int[flat.nTypo];
        dayStartOf = new int[flat.nDayNos];
        dayEndOf = new int[flat.nDayNos];
        otherStart = new int[flat.nDayNos];
        otherEnd = new int[flat.nDayNos];
        dayAhead = new boolean[flat.nDayNos];
        otherAhead = new boolean[flat.nDayNos];
        dayHas = new boolean[flat.nDayNos];
        otherHas = new boolean[flat.nDayNos];
        for (int s = 0; s < nS; s++) {
            unassignedMovable += flat.sFixed[s] ? 0 : 1;
            emptyAhead += flat.sPast[s] ? 0 : 1;
        }
        for (int s = 0; s < nS; s++) {
            if (flat.initial[s] >= 0) {
                away++; // counted away until set() below hands the seat back
                set(s, flat.initial[s]);
            }
        }
        load(assign.clone());
    }

    private long cost(Rule rule, int n) {
        if (explain) {
            acc[rule.ordinal()] += n;
        }
        hardOut += n * hardUnit[rule.ordinal()];
        return n * unit[rule.ordinal()];
    }

    /**
     * What each rule takes from the score of the working plan, in points of its
     * own level and under its weight — positive for a penalty, 0 for a rule
     * switched off: the number a Timefold score analysis gives for the same
     * constraint, sign apart.
     */
    long[] breakdown() {
        Arrays.fill(acc, 0);
        explain = true;
        try {
            recompute();
        } finally {
            explain = false;
        }
        long[] points = new long[acc.length];
        for (int r = 0; r < acc.length; r++) {
            points[r] = unit[r] == 0 && hardUnit[r] == 0 ? 0 : acc[r] * flat.weightAbs[r];
        }
        return points;
    }

    /** The exact score of the working plan: hard, medium and soft points lost, each on its own. */
    long[] levels() {
        long[] points = breakdown();
        long[] levels = new long[3];
        for (int r = 0; r < points.length; r++) {
            levels[flat.level[r]] += points[r];
        }
        return levels;
    }

    /**
     * Refuses a plan whose medium and soft no longer fit side by side in the
     * number they share: beyond that, a medium point could weigh less than
     * soft ones and the search would rank plans wrong without a word. The
     * hard level has a number of its own and no such limit.
     */
    static void checkRange(long[] levels) {
        if (Math.abs(levels[1]) >= MEDIUM_LIMIT || Math.abs(levels[2]) >= SOFT_LIMIT) {
            throw HandSearchException.refused("le score " + -levels[0] + "hard/" + -levels[1] + "medium/"
                    + -levels[2] + "soft sort de ce que le moteur sait ordonner (" + SOFT_LIMIT
                    + " points soft)");
        }
    }

    /** Whether the line is charged for having no referent: staffed, still ahead, and nobody on it who is one. */
    boolean lacksReferent(int l) {
        return lAhead[l] > 0 && lRef[l] == 0;
    }

    private long lineCost(int l) {
        long c = 0;
        if (lAhead[l] > 0) {
            if (lRef[l] == 0) {
                c += cost(Rule.STAND_COMPLEXE_AVEC_REFERENT, 1);
            } else if (lDeb[l] == 0) {
                c += cost(Rule.FAVORISER_MIXITE_DES_NIVEAUX, 1);
            }
            if (lMin[l] > lMaj[l]) {
                c += cost(Rule.REPARTITION_MINEURS_PAR_CRENEAU, lMin[l] - lMaj[l]);
            }
        }
        return c;
    }

    private long standCost(int st) {
        if (!flat.premium[st] || stAhead[st] == 0) {
            return 0;
        }
        int crew = 0;
        for (int l : flat.standLines[st]) {
            crew = Math.max(crew, lHeld[l]);
        }
        // No crew at all — every held seat the remainder of a split one — and the streams' join on
        // the crew finds nothing to compare the faces to.
        return crew > 0 && stDistinct[st] > crew
                ? cost(Rule.EVITER_ROULEMENT_STANDS_PREMIUM, stDistinct[st] - crew)
                : 0;
    }

    private long slotCost(int cr) {
        return crAhead[cr] > 0 && flat.ninjaTotal - crOcc[cr] < 1
                ? cost(Rule.PRESERVER_BUFFER_POLYVALENTS, 1 - (flat.ninjaTotal - crOcc[cr]))
                : 0;
    }

    private long forcedCost(int f) {
        return fSat[f] == 0 && !flat.fExempt[f] ? cost(Rule.AFFECTATION_FORCEE, 1) : 0;
    }

    private long local(int s) {
        long c = lineCost(flat.sLine[s]) + standCost(flat.sStand[s]) + slotCost(flat.sSlot[s]);
        for (int f : flat.forcedScope[s]) {
            c += forcedCost(f);
        }
        return c;
    }

    private long balance(Rule rule, long squares, long seats, int people, long ahead) {
        if (ahead == 0 || people == 0) {
            return 0;
        }
        // Timefold rounds the deviation to six significant digits before the constraint scales it:
        // near a half the two roundings disagree with a plain double, so that case takes their path.
        double squared = Math.max(0, squares - (double) seats * seats / people);
        double scaled = Math.sqrt(squared) * 10;
        boolean nearHalf = Math.abs(scaled - Math.floor(scaled) - 0.5) < 0.06 || scaled >= 100_000;
        return cost(
                rule,
                nearHalf
                        ? BigDecimal.valueOf(squared)
                                .sqrt(SIX_DIGITS)
                                .multiply(BigDecimal.TEN)
                                .setScale(0, RoundingMode.HALF_UP)
                                .intValueExact()
                        : Math.toIntExact(Math.round(scaled)));
    }

    /** The shared part of the cost; its hard points are left in {@link #globalHard}. */
    private long global() {
        hardOut = 0;
        long rest = shared
                + balance(Rule.EQUILIBRER_CHARGE, sumSq, held, active, aheadHeld)
                + balance(Rule.EQUILIBRER_CRENEAUX_PENIBLES, dSumSq, dHeld, dActive, dAhead);
        globalHard = sharedHard + hardOut;
        return rest;
    }

    /** Adds or takes away what the counts around seat s cost, on both numbers. */
    private void countLocal(int s, int sign) {
        hardOut = 0;
        shared += sign * local(s);
        sharedHard += sign * hardOut;
    }

    /** Re-reads one animateur into {@link #costOf} and {@link #hardOf}. */
    private void read(int a) {
        hardOut = 0;
        costOf[a] = eval(a);
        hardOf[a] = hardOut;
    }

    /** The rules kept as shared counts, read again from those counts. */
    private long sharedCost() {
        long c = 0;
        for (int l = 0; l < flat.nLines; l++) {
            c += lineCost(l);
        }
        for (int st = 0; st < flat.nStands; st++) {
            c += standCost(st);
        }
        for (int cr = 0; cr < flat.nSlots; cr++) {
            c += slotCost(cr);
        }
        for (int f = 0; f < fSat.length; f++) {
            c += forcedCost(f);
        }
        for (int s = 0; s < flat.nS; s++) {
            if (assign[s] < 0) {
                c += emptyCost(s);
            }
        }
        return c;
    }

    /**
     * Every rule read again from the counts and the seats, no cost taken from
     * a cache; its hard points are left in {@link #hardOut}. The counts
     * themselves are the ones {@link #set} kept: a search checks those by
     * building a second instance from the plan alone.
     */
    long recompute() {
        hardOut = 0;
        long c = balance(Rule.EQUILIBRER_CHARGE, sumSq, held, active, aheadHeld)
                + balance(Rule.EQUILIBRER_CRENEAUX_PENIBLES, dSumSq, dHeld, dActive, dAhead)
                + sharedCost();
        for (int a = 0; a < flat.nA; a++) {
            c += eval(a);
        }
        return c;
    }

    /** Replaces the working plan — fixed seats apart — and re-reads everything; returns the total. */
    long load(int[] plan) {
        tracking = false;
        for (int s = 0; s < flat.nS; s++) {
            if (!flat.sFixed[s] && assign[s] != plan[s]) {
                set(s, plan[s]);
            }
        }
        hardOut = 0;
        shared = sharedCost();
        sharedHard = hardOut;
        for (int a = 0; a < flat.nA; a++) {
            read(a);
        }
        return total();
    }

    /** The medium and soft cost of the working plan, from what each move kept up to date. */
    long total() {
        long total = global();
        for (int a = 0; a < flat.nA; a++) {
            total += costOf[a];
        }
        return total;
    }

    /** Its hard points, likewise. */
    long totalHard() {
        global();
        long total = globalHard;
        for (int a = 0; a < flat.nA; a++) {
            total += hardOf[a];
        }
        return total;
    }

    /** The hard points the last {@link #recompute} read. */
    long recomputedHard() {
        return hardOut;
    }

    /** Opens a move: every {@link #move} until {@link #finish} belongs to it. */
    void begin() {
        nAff = 0;
        nU = 0;
        globalBefore = global();
        globalHardBefore = globalHard;
        tracking = true;
    }

    private void touchOne(int a) {
        for (int i = 0; i < nAff; i++) {
            if (aff[i] == a) {
                return;
            }
        }
        if (nAff == aff.length) {
            aff = Arrays.copyOf(aff, nAff * 2);
            affOld = Arrays.copyOf(affOld, nAff * 2);
            affOldHard = Arrays.copyOf(affOldHard, nAff * 2);
            affDues = Arrays.copyOf(affDues, nAff * 2);
        }
        aff[nAff] = a;
        affDues[nAff] = dues[a];
        affOldHard[nAff] = hardOf[a];
        affOld[nAff++] = costOf[a];
    }

    private void touch(int a) {
        if (a < 0 || !tracking) {
            return;
        }
        touchOne(a);
        for (int d : flat.deps[a]) {
            touchOne(d); // their ad hoc pair reads this animateur's seats
        }
    }

    /** Hands seat s to {@code to} (-1 empties it) as part of the move under way. */
    void move(int s, int to) {
        uS[nU] = s;
        uA[nU++] = assign[s];
        set(s, to);
    }

    /**
     * Re-reads everybody the move touched and returns what it changed of the
     * medium and soft total; what it changed of the hard points is left in
     * {@link #deltaHard}.
     */
    long finish() {
        tracking = false;
        long delta = global() - globalBefore;
        long hard = globalHard - globalHardBefore;
        for (int i = 0; i < nAff; i++) {
            int a = aff[i];
            delta -= costOf[a];
            hard -= hardOf[a];
            read(a);
            delta += costOf[a];
            hard += hardOf[a];
        }
        deltaHard = hard;
        return delta;
    }

    /** Takes the last finished move back, costs included. */
    void undo() {
        while (nU > 0) {
            nU--;
            set(uS[nU], uA[nU]);
        }
        for (int i = 0; i < nAff; i++) {
            costOf[aff[i]] = affOld[i];
            hardOf[aff[i]] = affOldHard[i];
            dues[aff[i]] = affDues[i];
        }
    }

    private void set(int s, int to) {
        int from = assign[s];
        if (from == to) {
            return;
        }
        int initial = flat.initial[s];
        if (initial >= 0) {
            away += (to != initial ? 1 : 0) - (from != initial ? 1 : 0);
        }
        touch(from);
        touch(to);
        touchAround(s, from, to);
        countLocal(s, -1);
        if (from >= 0) {
            release(s, from);
        } else {
            countEmptied(s, -1);
        }
        if (to >= 0) {
            hold(s, to);
        } else {
            countEmptied(s, 1);
        }
        assign[s] = to;
        countLocal(s, 1);
    }

    /** Marks for re-reading the others a change of holder on seat s reaches: a break relayed, a minor supervised. */
    private void touchAround(int s, int from, int to) {
        if (alone) {
            return;
        }
        // a relay exists or not: only a seat that fills or empties changes it for the others
        for (int nb : from >= 0 && to >= 0 ? NONE : flat.nbr[s]) {
            if (assign[nb] >= 0 && dues[assign[nb]] > 0) {
                touch(assign[nb]);
            }
        }
        if (flat.supervision) {
            // the minors of the line may gain or lose the adult who supervises them
            int day = flat.sDay[s];
            for (int mate : flat.lineSeats[flat.sLine[s]]) {
                if (assign[mate] >= 0 && flat.minorAt[assign[mate]][day]) {
                    touch(assign[mate]);
                }
            }
        }
    }

    /** Seat s gains (sign 1) or loses (-1) the state of a seat nobody holds. */
    private void countEmptied(int s, int sign) {
        countEmpty(s, sign);
        emptyAhead += flat.sPast[s] ? 0 : sign;
        unassignedMovable += flat.sFixed[s] ? 0 : sign;
    }

    /** Takes seat s away from {@code from} in every shared count; {@code assign[s]} still names them. */
    private void release(int s, int from) {
        unweigh(s, from);
        remove(from, s);
        lHeld[flat.sLine[s]] -= flat.remainder[s] ? 0 : 1;
        if (succHeld[s] == 0) {
            present(s, from, -1);
        }
        int origin = flat.origin[s];
        if (origin >= 0 && --succHeld[origin] == 0 && assign[origin] >= 0) {
            present(origin, assign[origin], 1);
        }
        releaseScarce(s, from);
    }

    /** The counts only some seats and some people enter: premium stands, polyvalent animateurs, forced assignments. */
    private void releaseScarce(int s, int from) {
        int stand = flat.sStand[s];
        int cr = flat.sSlot[s];
        int ahead = flat.sPast[s] ? 0 : 1;
        if (flat.premium[stand]) {
            stAhead[stand] -= ahead;
            stDistinct[stand] -= --standCount[stand][from] == 0 ? 1 : 0;
        }
        if (flat.ninja[from]) {
            crAhead[cr] -= ahead;
            crOcc[cr] -= --ninjaCount[cr][from] == 0 ? 1 : 0;
        }
        for (int f : flat.forcedScope[s]) {
            fSat[f] -= flat.fConc[f][from] ? 1 : 0;
        }
    }

    /** Gives seat s to {@code to} in every shared count; {@code assign[s]} does not name them yet. */
    private void hold(int s, int to) {
        weigh(s, to);
        add(to, s);
        lHeld[flat.sLine[s]] += flat.remainder[s] ? 0 : 1;
        int origin = flat.origin[s];
        if (origin >= 0 && succHeld[origin]++ == 0 && assign[origin] >= 0) {
            present(origin, assign[origin], -1);
        }
        if (succHeld[s] == 0) {
            present(s, to, 1);
        }
        holdScarce(s, to);
    }

    private void holdScarce(int s, int to) {
        int stand = flat.sStand[s];
        int cr = flat.sSlot[s];
        int ahead = flat.sPast[s] ? 0 : 1;
        if (flat.premium[stand]) {
            stAhead[stand] += ahead;
            stDistinct[stand] += standCount[stand][to]++ == 0 ? 1 : 0;
        }
        if (flat.ninja[to]) {
            crAhead[cr] += ahead;
            crOcc[cr] += ninjaCount[cr][to]++ == 0 ? 1 : 0;
        }
        for (int f : flat.forcedScope[s]) {
            fSat[f] += flat.fConc[f][to] ? 1 : 0;
        }
    }

    /** One seat fewer for {@code from} in the two balances — every seat, and the demanding ones. */
    private void unweigh(int s, int from) {
        int ahead = flat.sPast[s] ? 0 : 1;
        int c = countOf[from];
        sumSq += (long) (c - 1) * (c - 1) - (long) c * c;
        active -= c == 1 ? 1 : 0;
        held--;
        aheadHeld -= ahead;
        if (flat.sDemanding[s]) {
            int d = dCount[from]--;
            dSumSq += (long) (d - 1) * (d - 1) - (long) d * d;
            dActive -= d == 1 ? 1 : 0;
            dHeld--;
            dAhead -= ahead;
        }
    }

    /** One seat more for {@code to} in the two balances. */
    private void weigh(int s, int to) {
        int ahead = flat.sPast[s] ? 0 : 1;
        int c = countOf[to];
        sumSq += (long) (c + 1) * (c + 1) - (long) c * c;
        active += c == 0 ? 1 : 0;
        held++;
        aheadHeld += ahead;
        if (flat.sDemanding[s]) {
            int d = dCount[to]++;
            dSumSq += (long) (d + 1) * (d + 1) - (long) d * d;
            dActive += d == 0 ? 1 : 0;
            dHeld++;
            dAhead += ahead;
        }
    }

    /** Adds or takes away what seat s costs while nobody holds it. */
    private void countEmpty(int s, int sign) {
        hardOut = 0;
        shared += sign * emptyCost(s);
        sharedHard += sign * hardOut;
    }

    private long emptyCost(int s) {
        if (!explain && !flat.sPast[s]) {
            hardOut += emptyExtra;
        }
        return (flat.sPast[s] ? 0 : cost(Rule.POSTE_DOIT_ETRE_POURVU, 1))
                + cost(Rule.STABILITE_DU_PLAN_PUBLIE, flat.published[s]);
    }

    private void present(int s, int a, int sign) {
        int line = flat.sLine[s];
        int stand = flat.sStand[s];
        lAhead[line] += flat.sPast[s] ? 0 : sign;
        lRef[line] += flat.isRef[stand][a] ? sign : 0;
        lDeb[line] += flat.isDeb[stand][a] ? sign : 0;
        if (flat.minorAt[a][flat.sDay[s]]) {
            lMin[line] += sign;
        } else if (flat.adultAt[a][flat.sDay[s]]) {
            lMaj[line] += sign;
        }
    }

    private void add(int a, int s) {
        int[] list = seatsOf[a];
        int n = countOf[a];
        if (n == list.length) {
            seatsOf[a] = list = Arrays.copyOf(list, n * 2);
        }
        int i = n;
        // ties in seat order: the order a fresh score director reads two simultaneous seats in
        while (i > 0
                && (flat.sStart[list[i - 1]] > flat.sStart[s]
                        || (flat.sStart[list[i - 1]] == flat.sStart[s] && list[i - 1] > s))) {
            list[i] = list[i - 1];
            i--;
        }
        list[i] = s;
        countOf[a] = n + 1;
    }

    private void remove(int a, int s) {
        int[] list = seatsOf[a];
        int n = countOf[a];
        int i = 0;
        while (list[i] != s) {
            i++;
        }
        System.arraycopy(list, i + 1, list, i, n - i - 1);
        countOf[a] = n - 1;
    }

    /** Whether somebody else on the stand of seat s covers [from, to]. */
    private boolean relayed(int s, int a, int from, int to) {
        for (int nb : flat.nbr[s]) {
            int other = assign[nb];
            if (other >= 0 && other != a && flat.sStart[nb] <= from && flat.sEnd[nb] >= to) {
                return true;
            }
        }
        return false;
    }

    /** Start, end and « something of it is still ahead » for each day an animateur works, by day number. */
    private void dayBounds(int a, boolean[] has, int[] start, int[] end, boolean[] live) {
        Arrays.fill(has, false);
        if (a < 0) {
            return;
        }
        int[] list = seatsOf[a];
        for (int i = 0; i < countOf[a]; i++) {
            int s = list[i];
            int j = flat.sDayNo[s];
            if (!has[j]) {
                has[j] = true;
                start[j] = flat.sStart[s];
                end[j] = flat.sEnd[s];
                live[j] = !flat.sPast[s];
            } else {
                start[j] = Math.min(start[j], flat.sStart[s]);
                end[j] = Math.max(end[j], flat.sEnd[s]);
                live[j] |= !flat.sPast[s];
            }
        }
    }

    /**
     * What animateur a costs on their own seats: the ad hoc rules they own,
     * then their seats read once, a day at a time, and what the days added up
     * for each week and for the event.
     *
     * <p>One method for everything read day by day, on purpose: cut into a
     * method a day, one for its seats and one for each family of rules, every
     * step was a call the JIT left out of line and every running value a
     * field instead of a register — the search lost about one move in
     * twenty. Only what is read once the days are over has its own
     * methods.</p>
     */
    @SuppressWarnings({"java:S3776", "java:S138", "java:S6541"})
    private long eval(int a) {
        int[] list = seatsOf[a];
        int n = countOf[a];
        long cost = 0;
        for (PairTerm term : flat.pairTerms[a]) {
            cost += pair(a, term);
        }
        if (n == 0) {
            dues[a] = 0;
            return cost;
        }
        int owed = 0;
        int firstWeek = flat.sWeek[list[0]], lastWeek = flat.sWeek[list[n - 1]];
        for (int w = firstWeek; w <= lastWeek + 1; w++) {
            wMaj[w] = wMin[w] = wDays[w] = wMinorMask[w] = 0;
            wAheadMaj[w] = wAheadMin[w] = wAhead[w] = wSeatAhead[w] = false;
        }
        occ = 0;
        long likedMask = 0;
        boolean likedAhead = false;
        int run = 0, longest = 0;
        boolean runRepro = false;
        int k = -1; // index of the day being read
        int i = 0;
        while (i < n) {
            int d = flat.sDay[list[i]];
            k++;
            int from = i;
            boolean minor = flat.minorAt[a][d];
            // Neither with an unknown birth date: no rule of an age bracket applies then.
            boolean aged = minor || flat.adultAt[a][d];
            int cap = minor
                    ? PlafondsLegauxMineurs.TRAVAIL_CONTINU_MAX_MINUTES
                    : PlafondsLegauxMajeurs.TRAVAIL_CONTINU_MAX_MINUTES;
            int pause = minor ? flat.pauseMinor : flat.pauseAdult;
            // first pass over the day: is anything of it still ahead
            boolean live = false;
            int to = i;
            while (to < n && flat.sDay[list[to]] == d) {
                live |= !flat.sPast[list[to]];
                to++;
            }
            int dur = 0, breaks = 0;
            int seqStart = flat.sStart[list[i]], seqEnd = seqStart, seqFrom = i;
            int dayStart = seqStart, dayEnd = seqStart;
            int base = d * 1440;
            for (; i <= to; i++) {
                boolean endOfDay = i == to;
                if (endOfDay || flat.sStart[list[i]] - seqEnd >= pause) {
                    int minutes = seqEnd - seqStart;
                    int pauses = minutes <= cap ? 0 : Math.ceilDiv(minutes - cap, cap + pause);
                    breaks += pauses * pause;
                    owed += aged ? pauses : 0;
                    for (int q = 1; aged && q <= pauses; q++) {
                        int limit = seqStart + q * cap + (q - 1) * pause;
                        int tenu = -1, after = -1, before = -1;
                        for (int j = seqFrom; j < i; j++) {
                            int s = list[j];
                            if (flat.sStart[s] <= limit && flat.sEnd[s] > limit) {
                                tenu = s;
                                break;
                            }
                            if (flat.sStart[s] <= limit) {
                                before = s;
                            } else if (after < 0) {
                                after = s;
                            }
                        }
                        if (tenu < 0) {
                            tenu = after >= 0 ? after : before;
                        }
                        if (live && !flat.sPast[tenu] && !relayed(tenu, a, limit, limit + pause)) {
                            cost += minor
                                    ? cost(Rule.TRAVAIL_CONTINU_MAX_MINEUR, FORFAIT + 1)
                                    : cost(Rule.TRAVAIL_CONTINU_MAX_MAJEUR, 1);
                        }
                    }
                    if (endOfDay) {
                        break;
                    }
                    seqStart = flat.sStart[list[i]];
                    seqEnd = seqStart;
                    seqFrom = i;
                }
                int s = list[i];
                dur += flat.sDur[s];
                if (explain) {
                    flat.hardOut = 0;
                    cost += flat.staticTerms(s, a, acc);
                    hardOut += flat.hardOut;
                } else {
                    cost += flat.stat[s][a];
                    hardOut += flat.statHard[s][a];
                }
                if (minor && flat.supervision && !flat.sPast[s]) {
                    boolean supervised = false;
                    for (int mate : flat.lineSeats[flat.sLine[s]]) {
                        int other = assign[mate];
                        // PosteAffectation.overlaps: measured from the timeslot's start, across midnight
                        supervised |= other >= 0
                                && flat.adultAt[other][d]
                                && flat.sFromSlot[mate] < flat.sFromSlot[s] + flat.sDur[s]
                                && flat.sFromSlot[s] < flat.sFromSlot[mate] + flat.sDur[mate];
                    }
                    if (!supervised) {
                        cost += cost(Rule.MINEUR_NECESSITE_ENCADREMENT_MAJEUR, FORFAIT);
                    }
                }
                seqEnd = Math.max(seqEnd, flat.sEnd[s]);
                dayEnd = Math.max(dayEnd, flat.sEnd[s]);
                for (int j = i + 1; j < n && flat.sStart[list[j]] < flat.sEnd[s]; j++) {
                    if (!(flat.sPast[s] && flat.sPast[list[j]])) {
                        cost += cost(Rule.PAS_DE_CHEVAUCHEMENT_HORAIRE, 1);
                    }
                }
                if (occ > 0 && flat.sStart[s] <= occE[occ - 1]) {
                    occE[occ - 1] = Math.max(occE[occ - 1], flat.sEnd[s]);
                } else {
                    if (occ == occS.length) {
                        occS = Arrays.copyOf(occS, occ * 2);
                        occE = Arrays.copyOf(occE, occ * 2);
                    }
                    occS[occ] = flat.sStart[s];
                    occE[occ++] = flat.sEnd[s];
                }
                if (!flat.sPast[s]) {
                    wSeatAhead[flat.sWeek[s]] = true;
                }
                long mask = flat.liked[flat.sStand[s]][a];
                likedMask |= mask;
                likedAhead |= mask != 0 && !flat.sPast[s];
                if (flat.hasQuota) {
                    for (int t : flat.sTypos[s]) {
                        tCnt[t]++;
                        tAhead[t] += flat.sPast[s] ? 0 : 1;
                    }
                }
            }
            dFrom[k] = from;
            dTo[k] = to;
            dStart[k] = dayStart;
            dEnd[k] = dayEnd;
            dLive[k] = live;
            int effective = dur - breaks;
            int dailyCap = minor
                    ? PlafondsLegauxMineurs.dureeQuotidienneMaxMinutes(flat.under16At[a][d])
                    : PlafondsLegauxMajeurs.DUREE_QUOTIDIENNE_MAX_MINUTES;
            if (aged && live && effective > dailyCap) {
                cost += minor
                        ? cost(Rule.DUREE_QUOTIDIENNE_MAX_MINEUR, FORFAIT + effective - dailyCap)
                        : cost(Rule.DUREE_QUOTIDIENNE_MAX_MAJEUR, effective - dailyCap);
            }
            int w = flat.sWeek[list[from]];
            if (minor) {
                wMin[w] += effective;
                wAheadMin[w] |= live;
                wMinorMask[w] |= 1 << Math.floorMod(d + flat.weekShift, 7);
            } else if (aged) {
                wMaj[w] += effective;
                wAheadMaj[w] |= live;
            }
            wDays[w]++;
            wAhead[w] |= live;

            // the day before
            boolean follows = k > 0 && flat.sDayNo[list[dFrom[k - 1]]] + 1 == flat.sDayNo[list[from]];
            if (follows) {
                int veille = flat.sDay[list[dFrom[k - 1]]];
                int rest = flat.under16At[a][veille] ? 14 * 60 : flat.minorAt[a][veille] ? 12 * 60 : 11 * 60;
                for (int x = dFrom[k - 1]; x < dTo[k - 1]; x++) {
                    for (int y = from; y < to; y++) {
                        int gap = flat.sStart[list[y]] - flat.sEnd[list[x]];
                        if (gap < rest && !(flat.sPast[list[x]] && flat.sPast[list[y]])) {
                            cost += cost(Rule.REPOS_QUOTIDIEN_MINIMAL, rest - gap);
                        }
                    }
                }
                if (flat.closingThenOpening
                        && (live || dLive[k - 1])
                        && dEnd[k - 1] >= (dStart[k - 1] / 1440) * 1440 + flat.lateFrom
                        && dayStart <= (dayStart / 1440) * 1440 + flat.earlyUntil) {
                    int gap = dayStart - dEnd[k - 1];
                    int missing = flat.wishedRest - Math.max(gap, rest);
                    if (missing > 0) {
                        cost += cost(Rule.EVITER_FERMETURE_PUIS_OUVERTURE, missing);
                    }
                }
            } else {
                run = 0;
                runRepro = false;
            }
            run++;
            runRepro |= live;
            if (runRepro) {
                longest = Math.max(longest, run);
            }

            // meal breaks (CoupureRepas.of)
            for (int m = 0; live && m < flat.mwOpen.length; m++) {
                if (!flat.mwOn[m][d]) {
                    continue;
                }
                int open = flat.mwOpen[m], close = flat.mwClose[m], need = flat.mwDur[m];
                boolean beforeIt = false, afterIt = false;
                for (int x = from; x < to; x++) {
                    beforeIt |= flat.sStart[list[x]] - base < open;
                    afterIt |= flat.sEnd[list[x]] - base > close;
                }
                if (!beforeIt || !afterIt) {
                    continue;
                }
                int cursor = open, biggest = 0, firstGap = -1, lastGap = -1;
                for (int x = from; x <= to; x++) {
                    int lo, hi;
                    if (x == to) {
                        lo = close;
                        hi = close;
                    } else {
                        lo = Math.max(flat.sStart[list[x]] - base, open);
                        hi = Math.min(flat.sEnd[list[x]] - base, close);
                        if (hi <= lo) {
                            continue;
                        }
                    }
                    int gap = lo - cursor;
                    if (gap > 0) {
                        biggest = Math.max(biggest, gap);
                        if (gap >= need) {
                            if (firstGap < 0) {
                                firstGap = cursor;
                            }
                            lastGap = lo - need;
                        }
                    }
                    cursor = Math.max(cursor, hi);
                }
                if (biggest < need) {
                    cost += cost(Rule.COUPURE_REPAS_OBLIGATOIRE, need - biggest);
                } else {
                    int off = flat.mwLate[m] ? Math.max(0, close - need - lastGap) : firstGap - open;
                    if (off > 0) {
                        cost += cost(Rule.COUPURE_REPAS_PLACEMENT_PREFERE, off);
                    }
                }
            }

            // places of the day, and what follows what
            if (to - from > 1 || flat.maxEmpl < 1) {
                int distinct = 0, located = 0;
                for (int x = from; x < to; x++) {
                    int sx = list[x], ex = flat.sEmpl[sx];
                    if (ex >= 0) {
                        located += flat.sPast[sx] ? 0 : 1;
                        boolean seen = false;
                        for (int y = from; y < x; y++) {
                            seen |= flat.sEmpl[list[y]] == ex;
                        }
                        distinct += seen ? 0 : 1;
                    }
                    for (int y = from; y < to; y++) {
                        int sy = list[y];
                        if (x == y || (flat.sPast[sx] && flat.sPast[sy])) {
                            continue;
                        }
                        if (flat.cEnd[sx] >= 0 && flat.cEnd[sx] == flat.cStart[sy]) {
                            if (ex >= 0 && flat.far[flat.sStand[sx]][flat.sStand[sy]]) {
                                cost += cost(Rule.EVITER_CHANGEMENT_EMPLACEMENT_ELOIGNE, 1);
                            }
                            if (flat.sExhausting[sx] && flat.sExhausting[sy]) {
                                cost += cost(Rule.EVITER_ENCHAINEMENT_STANDS_EPUISANTS, 1);
                            }
                        } else if (ex >= 0
                                && flat.sEmpl[sy] >= 0
                                && flat.cEnd[sx] >= 0
                                && flat.cEnd[sy] >= 0
                                && flat.sEnd[sx] < flat.sStart[sy]
                                && flat.walk[ex][flat.sEmpl[sy]] >= 0) {
                            int missing = WalkingTime.missingMinutes(
                                    flat.walk[ex][flat.sEmpl[sy]],
                                    (long) flat.sStart[sy] - flat.sEnd[sx],
                                    flat.walkTolerance);
                            if (missing > 0) {
                                boolean between = false;
                                for (int z = from; z < to; z++) {
                                    between |= flat.sStart[list[z]] >= flat.sEnd[sx]
                                            && flat.sEnd[list[z]] <= flat.sStart[sy];
                                }
                                if (!between) {
                                    cost += cost(Rule.TRAJET_INSUFFISANT_ENTRE_POSTES, missing);
                                }
                            }
                        }
                    }
                }
                if (located > 0 && distinct > flat.maxEmpl) {
                    cost += cost(Rule.LIMITER_EMPLACEMENTS_PAR_JOUR, distinct - flat.maxEmpl);
                }
            }
        }
        dues[a] = owed;
        cost += overTheEvent(a, longest, likedMask, likedAhead);
        for (int w = firstWeek; w <= lastWeek; w++) {
            cost += week(w);
        }
        return cost;
    }

    /** The rules read over all the seats of animateur a: the run of days, the spread of game categories, their ceilings. */
    private long overTheEvent(int a, int longest, long likedMask, boolean likedAhead) {
        long cost = 0;
        if (longest > flat.maxConsec) {
            cost += cost(Rule.MAX_JOURS_CONSECUTIFS_TRAVAILLES, longest - flat.maxConsec)
                    + cost(Rule.MAX_JOURS_CONSECUTIFS_TRAVAILLES_DUR, longest - flat.maxConsec);
        }
        if (!flat.ninja[a] && likedAhead && Long.bitCount(likedMask) > flat.maxTypo) {
            cost += cost(Rule.LIMITER_TYPOLOGIES_DISTINCTES_PAR_ANIMATEUR, Long.bitCount(likedMask) - flat.maxTypo);
        }
        if (flat.hasQuota) {
            int[] list = seatsOf[a];
            for (int x = 0; x < countOf[a]; x++) {
                for (int t : flat.sTypos[list[x]]) {
                    cost += ceiling(t);
                }
            }
        }
        return cost;
    }

    /** What the seats counted on game category t cost over its ceiling; the count is given back for the next read. */
    private long ceiling(int t) {
        long cost = 0;
        if (tCnt[t] > 0) {
            if (flat.quota[t] >= 0 && tAhead[t] > 0 && tCnt[t] > flat.quota[t]) {
                cost = cost(Rule.PLAFOND_CRENEAUX_PAR_TYPOLOGIE, tCnt[t] - flat.quota[t]);
            }
            tCnt[t] = 0;
            tAhead[t] = 0;
        }
        return cost;
    }

    /** The weekly rules on week w, from what the days of eval() added up. */
    private long week(int w) {
        long cost = 0;
        if (wAheadMaj[w] && wMaj[w] > flat.adultWeekly) {
            cost += cost(Rule.DUREE_HEBDOMADAIRE_MAX, wMaj[w] - flat.adultWeekly);
        }
        if (wAheadMin[w] && wMin[w] > flat.minorWeekly) {
            cost += cost(Rule.DUREE_HEBDOMADAIRE_MAX_MINEUR, wMin[w] - flat.minorWeekly);
        }
        if (wMaj[w] >= flat.adultWeekly && wMaj[w + 1] >= flat.adultWeekly && (wAheadMaj[w] || wAheadMaj[w + 1])) {
            cost += cost(Rule.DUREE_HEBDOMADAIRE_MAX_DEUX_SEMAINES, 1);
        }
        if (wAhead[w] && wDays[w] > PlafondsLegauxMajeurs.JOURS_TRAVAILLES_MAX_PAR_SEMAINE) {
            cost += cost(
                    Rule.MAX_JOURS_TRAVAILLES_PAR_SEMAINE,
                    wDays[w] - PlafondsLegauxMajeurs.JOURS_TRAVAILLES_MAX_PAR_SEMAINE);
        }
        if (wAheadMin[w]) {
            cost += minorWeeklyRest(w);
        }
        if (wSeatAhead[w]) {
            cost += weeklyRest(w);
        }
        return cost;
    }

    /** A minor's two consecutive days off in week w, from the days the week's mask marks as worked. */
    private long minorWeeklyRest(int w) {
        int free = 0;
        int longestFree = 0;
        for (int b = 0; b < 7; b++) {
            free = (wMinorMask[w] & (1 << b)) != 0 ? 0 : free + 1;
            longestFree = Math.max(longestFree, free);
        }
        return longestFree < PlafondsLegauxMineurs.JOURS_REPOS_CONSECUTIFS_PAR_SEMAINE
                ? cost(
                        Rule.REPOS_HEBDOMADAIRE_MINEUR,
                        PlafondsLegauxMineurs.JOURS_REPOS_CONSECUTIFS_PAR_SEMAINE - longestFree)
                : 0;
    }

    /** The longest credited rest of week w (LegalConstraints.creditReposMinutes) against the weekly minimum. */
    private long weeklyRest(int w) {
        int weekStart = (w * 7 - flat.weekShift) * 1440;
        int weekEnd = weekStart + 7 * 1440;
        int best = 0;
        for (int o = 0; o <= occ; o++) {
            // the rest before the first stretch and after the last is open: it runs to the week's edge and beyond
            boolean open = o == 0 || o == occ;
            int restStart = o == 0 ? weekStart : occE[o - 1];
            int restEnd = o == occ ? weekEnd : occS[o];
            int inWeek = Math.min(restEnd, weekEnd) - Math.max(restStart, weekStart);
            if (inWeek > 0) {
                int credit = open ? inWeek + 660 : Math.min(inWeek + 660, restEnd - restStart);
                best = Math.max(best, credit);
            }
        }
        return best < PlafondsLegauxMajeurs.REPOS_HEBDOMADAIRE_MIN_MINUTES
                ? cost(Rule.REPOS_HEBDOMADAIRE_MINIMAL, PlafondsLegauxMajeurs.REPOS_HEBDOMADAIRE_MIN_MINUTES - best)
                : 0;
    }

    /** An ad hoc rule between two people, charged to the one who owns the term. */
    private long pair(int a, PairTerm term) {
        return term.kind == 2 ? groupedArrival(a, term) : sameTimeslot(a, term);
    }

    /** Two people asked to arrive and leave together: the minutes their days differ by, beyond the tolerance. */
    private long groupedArrival(int a, PairTerm term) {
        long cost = 0;
        dayBounds(a, dayHas, dayStartOf, dayEndOf, dayAhead);
        dayBounds(term.other, otherHas, otherStart, otherEnd, otherAhead);
        for (int j = 0; j < dayHas.length; j++) {
            if (dayHas[j] && otherHas[j]) {
                int gap = Math.max(0, Math.abs(dayStartOf[j] - otherStart[j]) - term.tolerance)
                        + Math.max(0, Math.abs(dayEndOf[j] - otherEnd[j]) - term.tolerance);
                if (gap > 0 && (dayAhead[j] || otherAhead[j])) {
                    cost += cost(Rule.ARRIVEE_GROUPEE, gap);
                }
            } else if ((dayHas[j] && dayAhead[j]) || (otherHas[j] && otherAhead[j])) {
                cost += cost(Rule.ARRIVEE_GROUPEE, 120);
            }
        }
        return cost;
    }

    /** An incompatibility (kind 0) or an affinity: every pair of their seats on one timeslot, within the rule's scope. */
    private long sameTimeslot(int a, PairTerm term) {
        long cost = 0;
        int[] mine = seatsOf[a];
        int[] theirs = seatsOf[term.other];
        for (int x = 0; x < countOf[a]; x++) {
            int sx = mine[x];
            for (int y = 0; term.scope[sx] && y < countOf[term.other]; y++) {
                int sy = theirs[y];
                if (flat.sSlot[sx] == flat.sSlot[sy] && term.scope[sy] && !(flat.sPast[sx] && flat.sPast[sy])) {
                    cost += together(term, sx, sy);
                }
            }
        }
        return cost;
    }

    private long together(PairTerm term, int sx, int sy) {
        if (term.kind == 0) {
            return cost(Rule.INCOMPATIBILITE_AD_HOC, 1);
        }
        return flat.sStand[sx] == flat.sStand[sy] ? cost(Rule.AFFINITE_AD_HOC, -1) : 0;
    }
}
