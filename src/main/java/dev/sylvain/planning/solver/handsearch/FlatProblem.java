package dev.sylvain.planning.solver.handsearch;

import ai.timefold.solver.core.api.score.HardMediumSoftScore;
import dev.sylvain.planning.domain.AffectationPubliee;
import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.ConstraintToggle;
import dev.sylvain.planning.domain.ContrainteAdHoc;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.Emplacement;
import dev.sylvain.planning.domain.FenetreRepas;
import dev.sylvain.planning.domain.JoursFeries;
import dev.sylvain.planning.domain.MinutesOfDay;
import dev.sylvain.planning.domain.NiveauEffort;
import dev.sylvain.planning.domain.ParametresLegaux;
import dev.sylvain.planning.domain.ParametresQualite;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.QuotaTypologie;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.domain.TypeContrainteAdHoc;
import dev.sylvain.planning.domain.TypeVerrouillage;
import dev.sylvain.planning.domain.VerrouillagePlanning;
import dev.sylvain.planning.solver.ConstraintCatalog;
import dev.sylvain.planning.solver.WalkingTime;
import dev.sylvain.planning.solver.constraints.ExclusionEligibilite;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * A prepared problem laid flat for {@link IncrementalScore}: one index per
 * seat, animateur, stand, line (stand × timeslot) and timeslot, and everything
 * a rule reads from the domain objects turned into arrays once, so that the
 * search never touches an object (ADR 0087).
 *
 * <p>Built once per solve and never changed after its constructor returns:
 * the fields are not {@code final} only because that constructor fills them in
 * several passes. A seat is <em>fixed</em> when it is pinned or past — the
 * search reads its holder and never moves it.</p>
 *
 * <p>Some shapes of problem are refused here rather than scored wrongly: an
 * animateur appreciated for more than 64 game categories (the set of those
 * they hold is a {@code long}), a seat without a stand or without both its
 * hours — the streams have a branch for it that no generated seat takes, and
 * writing it here would touch every rule that reads a time —, a seat held by somebody the problem does not list,
 * and a fact carried twice — two states of one constraint, two ceilings of one
 * game category, two sets of parameters — which the streams would charge
 * twice and no table can hold. See {@link HandSearchException}.</p>
 */
final class FlatProblem {

    /**
     * One medium point on the number medium and soft share. The hard level is
     * counted apart, in a {@code long} of its own: a plan handed in with
     * hundreds of seats on somebody no longer allowed there — 10 000 points
     * each — would not fit beside the other two.
     */
    static final long MEDIUM = 1_000_000_000L;

    static final int FORFAIT = ExclusionEligibilite.FORFAIT;

    /**
     * The size past which the problem is refused: the engine keeps what every
     * seat costs every animateur, some fifteen bytes and a microsecond a couple
     * — tens of megabytes and under a second for an edition, most of a
     * gigabyte and two minutes for forty million couples. The same threshold
     * as the sampled construction the other search switches to (ADR 0036).
     */
    static final long MAX_SEATS_TIMES_ANIMATEURS = 15_000_000L;

    /**
     * What one unit of each rule costs: hard points for a hard rule, the
     * shared number for a medium or soft one — both 0 for a rule switched off.
     */
    final long[] hardUnit = new long[Rule.values().length];

    final long[] unit = new long[Rule.values().length];

    /** Hard points charged by {@link #cost} since a caller last reset it: the second half of every cost. */
    long hardOut;

    final long[] weightAbs = new long[Rule.values().length];

    /** The level each rule scores on: 0 hard, 1 medium, 2 soft — the edition's own when it moved the weight. */
    final int[] level = new int[Rule.values().length];

    PlanningEvenement problem;
    List<PosteAffectation> postes;
    List<Animateur> animateurs;
    ParametresLegaux legal;
    ParametresQualite qualite;
    int nS;
    int nA;
    int nDays;
    int nWeeks;
    int nDayNos;
    int weekShift;
    int nLines;
    int nStands;
    int nSlots;
    int nTypo;

    // per seat: start and end in minutes from the first day, day, day number of its timeslot, ISO week,
    // duration, line, stand, timeslot, hours of its timeslot, location, "its vacation was published"
    int[] sStart;
    int[] sEnd;
    /**
     * Minutes from the start of the seat's timeslot to its own start: what two
     * seats of one line are compared on, so that the remainder of a night
     * timeslot split after midnight still overlaps the seats of the evening.
     */
    int[] sFromSlot;

    int[] sDay;
    int[] sDayNo;
    int[] sWeek;
    int[] sDur;
    int[] sLine;
    int[] sStand;
    int[] sSlot;
    int[] cStart;
    int[] cEnd;
    int[] sEmpl;
    int[] published;
    List<Set<String>> publishedBy;
    int[] initial;
    boolean[] sFixed;
    boolean[] sPast;
    boolean[] sLocked;
    boolean[] sExhausting;
    boolean[] sDemanding;
    int[][] sTypos;
    /** Who may hold each seat without a hard point of its own; {@link #statHard} is the table of it. */
    int[][] elig;

    /** The other seats of the same stand around each seat: where a relay or a supervising adult is looked for. */
    int[][] nbr;

    int[][] standLines;
    int[][] lineSeats;
    /** A seat split on the day (ADR 0066): the seat it continues, or -1; and whether it is such a remainder. */
    int[] origin;

    boolean[] remainder;
    boolean supervision;
    /** What one seat costs one animateur whoever else is around: {@link #staticTerms}, computed once. */
    long[][] stat;

    int[][] statHard;

    private final List<List<ContrainteAdHoc>> forcedOffOf = new ArrayList<>();
    private final List<List<VerrouillagePlanning>> locksOf = new ArrayList<>();

    boolean[][] isRef;
    boolean[][] isDeb;
    /**
     * Whether each animateur is a minor, and an adult, on each day. Not each
     * other's negation: somebody whose birth date is unknown is neither, and
     * no rule written for one age bracket applies to them.
     */
    boolean[][] minorAt;

    boolean[][] adultAt;
    boolean[][] under16At;
    boolean[][] far;
    long[][] liked;
    boolean[] ninja;
    boolean[] premium;
    int[][] walk;
    int[] quota;
    boolean hasQuota;
    int ninjaTotal;
    int[] mwOpen;
    int[] mwClose;
    int[] mwDur;
    boolean[] mwLate;
    boolean[][] mwOn;
    int adultWeekly;
    int pauseMinor;
    int pauseAdult;
    int minorWeekly;
    int maxConsec;
    int maxEmpl;
    int maxTypo;
    int lateFrom;
    int earlyUntil;
    int wishedRest;
    int walkTolerance;
    boolean closingThenOpening;

    // ad hoc exceptions: forced assignments by seat and animateur, and the rules between two people
    int[][] forcedScope;
    boolean[][] fScope;
    boolean[][] fConc;
    boolean[] fExempt;
    PairTerm[][] pairTerms;
    int[][] deps;

    /**
     * A rule between two animateurs, charged to the one who owns the term:
     * kind 0 an incompatibility, 1 an affinity, 2 a grouped arrival.
     */
    static final class PairTerm {
        final int kind;
        final int other;
        final boolean[] scope;
        final int tolerance;

        PairTerm(int kind, int other, boolean[] scope, int tolerance) {
            this.kind = kind;
            this.other = other;
            this.scope = scope;
            this.tolerance = tolerance;
        }
    }

    /**
     * What n units of a rule cost on the shared number; its hard points go to
     * {@link #hardOut}, and its units to {@code breakdown} when one is being
     * filled.
     */
    long cost(Rule rule, long n, long[] breakdown) {
        if (breakdown != null) {
            breakdown[rule.ordinal()] += n;
        }
        hardOut += n * hardUnit[rule.ordinal()];
        return n * unit[rule.ordinal()];
    }

    private void weights() {
        Set<String> toggled = new HashSet<>();
        for (ConstraintToggle toggle : problem.getConstraintsDesactivees()) {
            if (!toggled.add(toggle.getNom())) {
                // The streams switch a rule off as soon as one of its toggles says so, on when one
                // says so: two of them is a state no table can hold, and nothing to guess at.
                throw HandSearchException.refused("la contrainte " + toggle.getNom() + " porte deux états");
            }
        }
        for (Rule rule : Rule.values()) {
            String name = rule.constraintName();
            ConstraintCatalog.ConstraintDefinition definition = ConstraintCatalog.PAR_NOM.get(name);
            Objects.requireNonNull(definition, name);
            int scoredOn = definition.niveau().ordinal();
            long w = 1;
            HardMediumSoftScore override = problem.getPonderationsContraintes().getConstraintWeight(name);
            if (override != null) {
                w = override.hardScore() + override.mediumScore() + override.softScore();
                scoredOn = levelOf(override);
            }
            weightAbs[rule.ordinal()] = w;
            level[rule.ordinal()] = scoredOn;
            if (ConstraintCatalog.isActive(problem.getConstraintsDesactivees(), name)) {
                hardUnit[rule.ordinal()] = scoredOn == 0 ? w : 0;
                unit[rule.ordinal()] = restUnit(scoredOn, w);
            }
        }
    }

    /** The level a weight sits on: 0 hard, 1 medium, 2 soft — the first it is not zero on. */
    private static int levelOf(HardMediumSoftScore weight) {
        if (weight.hardScore() != 0) {
            return 0;
        }
        return weight.mediumScore() == 0 ? 2 : 1;
    }

    /** What one unit of a rule weighs on the number medium and soft share; nothing for a hard rule. */
    private static long restUnit(int scoredOn, long w) {
        if (scoredOn == 0) {
            return 0;
        }
        return scoredOn == 1 ? w * MEDIUM : w;
    }

    /** Whether a rule is switched on and weighs something. */
    boolean charged(Rule rule) {
        return hardUnit[rule.ordinal()] != 0 || unit[rule.ordinal()] != 0;
    }

    static boolean scope(ContrainteAdHoc c, PosteAffectation poste) {
        boolean creneauOk = c.getCreneau() == null
                || (c.getCreneau().getId() != null
                        && c.getCreneau().getId().equals(poste.getCreneau().getId()));
        boolean standOk = c.getStand() == null
                || c.getStand().getId().equals(poste.getStand().getId());
        return creneauOk && standOk;
    }

    static boolean concerns(ContrainteAdHoc c, Animateur animateur) {
        return c.getAnimateursConcernes() != null
                && c.getAnimateursConcernes().stream()
                        .anyMatch(
                                t -> t != null && t.getId() != null && t.getId().equals(animateur.getId()));
    }

    FlatProblem(PlanningEvenement problem) {
        this.problem = problem;
        postes = problem.getPostes();
        animateurs = problem.getAnimateurs();
        legal = problem.parametresLegaux();
        // A join on a fact carried twice charges twice: one of each, or nothing is scored.
        if (problem.getParametresLegaux().size() != 1
                || problem.getParametresQualite().size() != 1) {
            throw HandSearchException.refused(
                    "le problème ne porte pas exactement un jeu de paramètres légaux et un de qualité");
        }
        qualite = problem.getParametresQualite().get(0);
        weights();
        refuseSeatsNoTableHolds();
        nS = postes.size();
        nA = animateurs.size();
        if ((long) nS * nA > MAX_SEATS_TIMES_ANIMATEURS) {
            throw HandSearchException.refused(nS + " sièges pour " + nA + " animateurs : au-delà de "
                    + MAX_SEATS_TIMES_ANIMATEURS + " couples siège × animateur, la table du moteur ne tient plus"
                    + " dans la mémoire d'une petite machine");
        }
        Map<String, Integer> byId = new HashMap<>();
        for (int a = 0; a < nA; a++) {
            byId.put(animateurs.get(a).getId(), a);
        }
        long minDay = postes.stream()
                .mapToLong(x -> x.getCreneau().getDate().toEpochDay())
                .min()
                .orElse(0);
        LocalDate first = LocalDate.ofEpochDay(minDay);
        Numbering numbering = new Numbering();
        seats(byId, minDay, numbering);
        splitSeats();
        linesAndStands(numbering);
        gameCategories(numbering.typos);
        people(first, numbering.standsInOrder);
        distances(numbering.standsInOrder, numbering.placeList);
        parameters(first);
        pairs(byId);
        forcedAssignments();
        locksAndForcedUnavailabilities();
        staticTable();
    }

    /** What the seats are numbered against while they are read: stands, lines, timeslots, places, game categories. */
    private static final class Numbering {
        final Map<Object, Integer> lines = new HashMap<>();
        final Map<Object, Integer> stands = new HashMap<>();
        final Map<Object, Integer> slots = new HashMap<>();
        final Map<Emplacement, Integer> places = new HashMap<>();
        final Map<String, Integer> typos = new HashMap<>();
        final List<Stand> standsInOrder = new ArrayList<>();
        final List<Emplacement> placeList = new ArrayList<>();
        final List<List<Integer>> linesOfStand = new ArrayList<>();
        final Map<Integer, List<Integer>> byStand = new HashMap<>();
    }

    /** The seats refused rather than scored wrongly: one without a stand or its hours, one held by a stranger. */
    private void refuseSeatsNoTableHolds() {
        Set<String> known = new HashSet<>();
        animateurs.forEach(animateur -> known.add(animateur.getId()));
        for (PosteAffectation poste : postes) {
            Creneau creneau = poste.getCreneau();
            if (poste.getStand() == null
                    || creneau == null
                    || creneau.getDate() == null
                    || creneau.getHeureDebut() == null
                    || poste.heureDebutEffectif() == null
                    || poste.heureFinEffectif() == null) {
                // Without an end a seat lasts nothing, and the streams' overlap then matches nothing.
                throw HandSearchException.refused(
                        "le siège " + poste.getId() + " n'a pas de stand ou pas d'horaire complet");
            }
            if (poste.getAnimateur() != null
                    && !known.contains(poste.getAnimateur().getId())) {
                throw HandSearchException.refused(
                        "le siège " + poste.getId() + " est tenu par un animateur absent du problème");
            }
        }
    }

    /** Everything read from one seat alone: its hours, its day and week, what it is numbered against, its holder. */
    private void seats(Map<String, Integer> byId, long minDay, Numbering numbering) {
        long firstMonday = Math.floorDiv(minDay + 3, 7);
        weekShift = Math.floorMod(minDay + 3, 7);
        sStart = new int[nS];
        sEnd = new int[nS];
        sFromSlot = new int[nS];
        sDay = new int[nS];
        sDayNo = new int[nS];
        sWeek = new int[nS];
        sDur = new int[nS];
        sLine = new int[nS];
        sStand = new int[nS];
        sSlot = new int[nS];
        cStart = new int[nS];
        cEnd = new int[nS];
        sEmpl = new int[nS];
        published = new int[nS];
        sFixed = new boolean[nS];
        sPast = new boolean[nS];
        sLocked = new boolean[nS];
        sExhausting = new boolean[nS];
        sDemanding = new boolean[nS];
        sTypos = new int[nS][];
        Map<String, Set<String>> publishedHolders = new HashMap<>();
        for (AffectationPubliee publiee : problem.getAffectationsPubliees()) {
            publishedHolders
                    .computeIfAbsent(publiee.key(), k -> new HashSet<>())
                    .add(publiee.animateurId());
        }
        initial = new int[nS];
        publishedBy = new ArrayList<>(nS);
        for (int s = 0; s < nS; s++) {
            PosteAffectation poste = postes.get(s);
            Creneau creneau = poste.getCreneau();
            Stand stand = poste.getStand();
            long day = creneau.getDate().toEpochDay();
            sDay[s] = (int) (day - minDay);
            sDayNo[s] = creneau.getJour();
            sWeek[s] = (int) (Math.floorDiv(day + 3, 7) - firstMonday);
            sDur[s] = poste.getDureeEffectiveMinutes();
            sStart[s] = sDay[s] * 1440 + poste.heureDebutEffectif().toSecondOfDay() / 60;
            sEnd[s] = sStart[s] + sDur[s];
            sFromSlot[s] = MinutesOfDay.minutesAfter(creneau.getHeureDebut(), poste.heureDebutEffectif());
            cStart[s] = creneau.getHeureDebut().toSecondOfDay() / 60;
            cEnd[s] = creneau.getHeureFin() == null ? -1 : creneau.getHeureFin().toSecondOfDay() / 60;
            number(s, stand, creneau, numbering);
            sExhausting[s] = stand.getNiveauEffort() == NiveauEffort.EPUISANT;
            sDemanding[s] = sExhausting[s] || stand.isPremium();
            sPast[s] = poste.isPasse();
            sLocked[s] = poste.isVerrouille();
            sFixed[s] = sPast[s] || sLocked[s];
            initial[s] = poste.getAnimateur() == null
                    ? -1
                    : byId.get(poste.getAnimateur().getId());
            Set<String> holders = publishedHolders.get(AffectationPubliee.key(
                    stand.getId(), creneau.getDate(), creneau.getHeureDebut(), creneau.getHeureFin()));
            publishedBy.add(sPast[s] ? null : holders);
            published[s] = sPast[s] || holders == null ? 0 : 1;
        }
    }

    /** Numbers seat s against its stand, its line, its timeslot, its place and the game categories of its stand. */
    private void number(int s, Stand stand, Creneau creneau, Numbering numbering) {
        Map<Object, Integer> stands = numbering.stands;
        Map<Object, Integer> lines = numbering.lines;
        Map<Object, Integer> slots = numbering.slots;
        Map<Emplacement, Integer> places = numbering.places;
        Map<String, Integer> typos = numbering.typos;
        sStand[s] = stands.computeIfAbsent(stand, k -> {
            numbering.standsInOrder.add(stand);
            numbering.linesOfStand.add(new ArrayList<>());
            return stands.size();
        });
        int before = lines.size();
        sLine[s] = lines.computeIfAbsent(List.of(stand, creneau), k -> lines.size());
        if (lines.size() > before) {
            numbering.linesOfStand.get(sStand[s]).add(sLine[s]);
        }
        sSlot[s] = slots.computeIfAbsent(creneau, k -> slots.size());
        Emplacement emplacement = stand.getEmplacement();
        sEmpl[s] = emplacement == null
                ? -1
                : places.computeIfAbsent(emplacement, k -> {
                    numbering.placeList.add(emplacement);
                    return places.size();
                });
        sTypos[s] = stand.getTypologiesProposees().stream()
                .mapToInt(t -> typos.computeIfAbsent(t, k -> typos.size()))
                .toArray();
        numbering.byStand.computeIfAbsent(sStand[s], k -> new ArrayList<>()).add(s);
    }

    /** A seat split on the day (ADR 0066): the origin ended where its remainder starts. */
    private void splitSeats() {
        origin = new int[nS];
        remainder = new boolean[nS];
        Map<String, Integer> seatById = new HashMap<>();
        for (int s = 0; s < nS; s++) {
            seatById.put(postes.get(s).getId(), s);
        }
        for (int s = 0; s < nS; s++) {
            String suiteDe = postes.get(s).getSuiteDe();
            remainder[s] = suiteDe != null;
            origin[s] = suiteDe == null ? -1 : seatById.getOrDefault(suiteDe, -1);
        }
    }

    /** The seats of each line, the lines of each stand, the neighbours a relay is looked for among, and the sizes. */
    private void linesAndStands(Numbering numbering) {
        nLines = numbering.lines.size();
        supervision = charged(Rule.MINEUR_NECESSITE_ENCADREMENT_MAJEUR);
        List<List<Integer>> seatsOfLine = new ArrayList<>();
        for (int l = 0; l < nLines; l++) {
            seatsOfLine.add(new ArrayList<>());
        }
        for (int s = 0; s < nS; s++) {
            seatsOfLine.get(sLine[s]).add(s);
        }
        lineSeats = new int[nLines][];
        for (int l = 0; l < nLines; l++) {
            lineSeats[l] =
                    seatsOfLine.get(l).stream().mapToInt(Integer::intValue).toArray();
        }
        nStands = numbering.stands.size();
        nSlots = numbering.slots.size();
        nbr = new int[nS][];
        // A break is owed at an instant inside the seat held, or in the gap — shorter than the
        // break — right before it, and its relay covers that instant and the break after it: the
        // relay overlaps the seat held, whatever the break lasts and whichever side of midnight.
        for (List<Integer> group : numbering.byStand.values()) {
            for (int s : group) {
                nbr[s] = group.stream()
                        .mapToInt(Integer::intValue)
                        .filter(o -> o != s && sStart[o] <= sEnd[s] && sEnd[o] >= sStart[s])
                        .toArray();
            }
        }
        standLines = new int[nStands][];
        premium = new boolean[nStands];
        for (int st = 0; st < nStands; st++) {
            standLines[st] = numbering.linesOfStand.get(st).stream()
                    .mapToInt(Integer::intValue)
                    .toArray();
            premium[st] = numbering.standsInOrder.get(st).isPremium();
        }
        nDays = Arrays.stream(sDay).max().orElse(0) + 2;
        nWeeks = Arrays.stream(sWeek).max().orElse(0) + 3;
        nDayNos = Arrays.stream(sDayNo).max().orElse(0) + 2;
    }

    /** The game categories the edition knows — offered, capped or appreciated — and the ceiling of each. */
    private void gameCategories(Map<String, Integer> typos) {
        problem.getQuotasTypologies().forEach(q -> typos.computeIfAbsent(q.getTypologie(), k -> typos.size()));
        animateurs.forEach(a -> a.getCompetences().keySet().forEach(t -> typos.computeIfAbsent(t, k -> typos.size())));
        nTypo = typos.size();
        quota = new int[nTypo];
        Arrays.fill(quota, -1);
        hasQuota = false;
        for (QuotaTypologie q : problem.getQuotasTypologies()) {
            if (quota[typos.get(q.getTypologie())] >= 0) {
                throw HandSearchException.refused("la typologie " + q.getTypologie() + " porte deux plafonds");
            }
            quota[typos.get(q.getTypologie())] = q.getMaxCreneaux();
            hasQuota = true;
        }
    }

    /** Everything read from one animateur alone: their age on each day, and what they are for each stand. */
    private void people(LocalDate first, List<Stand> standsInOrder) {
        minorAt = new boolean[nA][nDays];
        adultAt = new boolean[nA][nDays];
        under16At = new boolean[nA][nDays];
        ninja = new boolean[nA];
        isRef = new boolean[nStands][nA];
        isDeb = new boolean[nStands][nA];
        liked = new long[nStands][nA];
        ninjaTotal = 0;
        Set<String> offered = new HashSet<>();
        standsInOrder.forEach(stand -> offered.addAll(stand.getTypologiesProposees()));
        for (int a = 0; a < nA; a++) {
            Animateur animateur = animateurs.get(a);
            ninja[a] = animateur.isNinja();
            ninjaTotal += ninja[a] ? 1 : 0;
            for (int d = 0; d < nDays; d++) {
                minorAt[a][d] = animateur.isMineurOn(first.plusDays(d));
                adultAt[a][d] = animateur.isMajeurOn(first.plusDays(d));
                under16At[a][d] = animateur.isUnder16On(first.plusDays(d));
            }
            // The categories this animateur is appreciated for and a stand offers, numbered for them
            // alone: the set of those they hold is a long, and an edition may have as many as it likes.
            Map<String, Integer> own = new HashMap<>();
            animateur.getCompetences().keySet().stream()
                    .filter(offered::contains)
                    .forEach(t -> own.put(t, own.size()));
            if (own.size() > Long.SIZE) {
                throw HandSearchException.refused("l'animateur " + animateur.getId() + " est apprécié sur " + own.size()
                        + " typologies de jeu proposées par un stand, pour " + Long.SIZE + " au plus");
            }
            for (int st = 0; st < nStands; st++) {
                Stand stand = standsInOrder.get(st);
                isRef[st][a] = animateur.isReferentFor(stand);
                isDeb[st][a] = animateur.isDebutantFor(stand);
                liked[st][a] = likedMask(stand, own);
            }
        }
    }

    /** The game categories a stand offers among those an animateur numbered for themself, as bits. */
    private static long likedMask(Stand stand, Map<String, Integer> own) {
        long mask = 0;
        for (String t : stand.getTypologiesProposees()) {
            Integer bit = own.get(t);
            if (bit != null) {
                mask |= 1L << bit;
            }
        }
        return mask;
    }

    /** Which stands are far from each other, and the minutes of walk between two places. */
    private void distances(List<Stand> standsInOrder, List<Emplacement> placeList) {
        far = new boolean[nStands][nStands];
        for (int x = 0; x < nStands; x++) {
            for (int y = 0; y < nStands; y++) {
                Emplacement ex = standsInOrder.get(x).getEmplacement();
                Emplacement ey = standsInOrder.get(y).getEmplacement();
                Double metres = ex == null || ey == null ? null : ex.distanceMetresTo(ey);
                far[x][y] = !standsInOrder.get(x).equals(standsInOrder.get(y)) && metres != null && metres > 300.0;
            }
        }
        walk = new int[placeList.size()][placeList.size()];
        for (int x = 0; x < placeList.size(); x++) {
            for (int y = 0; y < placeList.size(); y++) {
                Integer minutes = WalkingTime.minutes(
                        placeList.get(x), placeList.get(y), qualite.vitesseMarcheKmH(), qualite.facteurDetour());
                walk[x][y] = minutes == null || placeList.get(x).equals(placeList.get(y)) ? -1 : minutes;
            }
        }
    }

    /** The legal and quality parameters the rules read, and the meal windows day by day. */
    private void parameters(LocalDate first) {
        pauseMinor = legal.dureePauseMinutes(true);
        pauseAdult = legal.dureePauseMinutes(false);
        adultWeekly = legal.getDureeHebdomadaireMaxMinutes();
        minorWeekly = legal.getDureeHebdomadaireMaxMineurMinutes();
        maxConsec = qualite.joursConsecutifsMax();
        maxEmpl = qualite.maxEmplacementsDistinctsParJour();
        maxTypo = qualite.typologiesDistinctesMax();
        lateFrom = qualite.heureServiceTardif().toSecondOfDay() / 60;
        earlyUntil = qualite.heureServiceMatinal().toSecondOfDay() / 60;
        wishedRest = qualite.reposSouhaiteApresServiceTardifMinutes();
        walkTolerance = qualite.toleranceTrajetMinutes();
        closingThenOpening = qualite.penaliseFermeturePuisOuverture();
        List<FenetreRepas> meals = problem.getFenetresRepas();
        mwOpen = new int[meals.size()];
        mwClose = new int[meals.size()];
        mwDur = new int[meals.size()];
        mwLate = new boolean[meals.size()];
        mwOn = new boolean[meals.size()][nDays];
        for (int m = 0; m < meals.size(); m++) {
            FenetreRepas f = meals.get(m);
            mwOpen[m] = f.debutMinutes();
            mwClose[m] = f.finMinutes();
            mwDur[m] = f.dureeMinutes();
            mwLate[m] = f.auPlusTard();
            for (int d = 0; d < nDays; d++) {
                mwOn[m][d] = f.appliesTo(first.plusDays(d));
            }
        }
    }

    /** The ad hoc rules between two people — incompatibility, affinity, grouped arrival — and who depends on whom. */
    private void pairs(Map<String, Integer> byId) {
        List<List<PairTerm>> terms = new ArrayList<>();
        List<Set<Integer>> dependents = new ArrayList<>();
        for (int a = 0; a < nA; a++) {
            terms.add(new ArrayList<>());
            dependents.add(new HashSet<>());
        }
        for (ContrainteAdHoc c : problem.getContraintesAdHoc()) {
            TypeContrainteAdHoc type = c.getType();
            if (type == TypeContrainteAdHoc.INCOMPATIBILITE || type == TypeContrainteAdHoc.AFFINITE) {
                twoPeople(c, byId, terms, dependents);
            } else if (type == TypeContrainteAdHoc.ARRIVEE_GROUPEE && c.getAnimateursConcernes() != null) {
                groupedArrival(c, byId, terms, dependents);
            }
        }
        pairTerms = new PairTerm[nA][];
        deps = new int[nA][];
        for (int a = 0; a < nA; a++) {
            pairTerms[a] = terms.get(a).toArray(new PairTerm[0]);
            deps[a] = dependents.get(a).stream().mapToInt(Integer::intValue).toArray();
        }
    }

    /** An incompatibility or an affinity between its first two people, when the problem knows both. */
    private void twoPeople(
            ContrainteAdHoc c, Map<String, Integer> byId, List<List<PairTerm>> terms, List<Set<Integer>> dependents) {
        List<Animateur> members = c.getAnimateursConcernes();
        if (members == null || members.size() < 2 || members.get(0) == null || members.get(1) == null) {
            return;
        }
        String firstId = members.get(0).getId();
        String secondId = members.get(1).getId();
        if (firstId == null || secondId == null || firstId.equals(secondId)) {
            return;
        }
        Integer x = byId.get(firstId);
        Integer y = byId.get(secondId);
        if (x == null || y == null) {
            return; // nobody of that id holds a seat: the join is empty
        }
        boolean[] inScope = new boolean[nS];
        for (int s = 0; s < nS; s++) {
            inScope[s] = scope(c, postes.get(s));
        }
        terms.get(x).add(new PairTerm(c.getType() == TypeContrainteAdHoc.INCOMPATIBILITE ? 0 : 1, y, inScope, 0));
        dependents.get(y).add(x);
    }

    /** A grouped arrival: one term for every two of its people, owned by the first the problem knows. */
    private void groupedArrival(
            ContrainteAdHoc c, Map<String, Integer> byId, List<List<PairTerm>> terms, List<Set<Integer>> dependents) {
        List<String> ids = c.getAnimateursConcernes().stream()
                .filter(Objects::nonNull)
                .map(Animateur::getId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        for (int i = 0; i < ids.size(); i++) {
            for (int j = i + 1; j < ids.size(); j++) {
                arriveTogether(byId.get(ids.get(i)), byId.get(ids.get(j)), terms, dependents);
            }
        }
    }

    /** Two people of a grouped arrival, either of whom the problem may not know: nothing when it knows neither. */
    private void arriveTogether(Integer x, Integer y, List<List<PairTerm>> terms, List<Set<Integer>> dependents) {
        if (x == null && y == null) {
            return;
        }
        int owner = x != null ? x : y;
        int other = x != null && y != null ? y : -1;
        terms.get(owner).add(new PairTerm(2, other, null, qualite.toleranceArriveeGroupeeMinutes()));
        if (other >= 0) {
            dependents.get(other).add(owner);
        }
    }

    /** The forced assignments: the seats each covers, the people each names, and those the past already settled. */
    private void forcedAssignments() {
        List<ContrainteAdHoc> forced = problem.getContraintesAdHoc().stream()
                .filter(c -> c.getType() == TypeContrainteAdHoc.AFFECTATION_FORCEE)
                .toList();
        int nF = forced.size();
        fScope = new boolean[nF][nS];
        fConc = new boolean[nF][nA];
        fExempt = new boolean[nF];
        List<List<Integer>> forcedOfSeat = new ArrayList<>();
        for (int s = 0; s < nS; s++) {
            forcedOfSeat.add(new ArrayList<>());
        }
        for (int f = 0; f < nF; f++) {
            ContrainteAdHoc c = forced.get(f);
            boolean anyPast = false;
            boolean anyAhead = false;
            for (int s = 0; s < nS; s++) {
                fScope[f][s] = scope(c, postes.get(s));
                if (fScope[f][s]) {
                    forcedOfSeat.get(s).add(f);
                    anyPast |= sPast[s];
                    anyAhead |= !sPast[s];
                }
            }
            fExempt[f] = anyPast && !anyAhead;
            for (int a = 0; a < nA; a++) {
                fConc[f][a] = concerns(c, animateurs.get(a));
            }
        }
        forcedScope = new int[nS][];
        for (int s = 0; s < nS; s++) {
            forcedScope[s] =
                    forcedOfSeat.get(s).stream().mapToInt(Integer::intValue).toArray();
        }
    }

    /** The forced unavailabilities and the locks that name each animateur: read for every seat of the table. */
    private void locksAndForcedUnavailabilities() {
        for (Animateur animateur : animateurs) {
            forcedOffOf.add(problem.getContraintesAdHoc().stream()
                    .filter(adHoc ->
                            adHoc.getType() == TypeContrainteAdHoc.INDISPONIBILITE_FORCEE && concerns(adHoc, animateur))
                    .toList());
            locksOf.add(problem.getVerrouillages().stream()
                    .filter(lock -> animateur.getId().equals(lock.getAnimateurId()))
                    .toList());
        }
    }

    /** What one seat costs one animateur, whoever else is around, and who may hold each seat at no hard point. */
    private void staticTable() {
        stat = new long[nS][nA];
        statHard = new int[nS][nA];
        elig = new int[nS][];
        int[] buffer = new int[nA];
        for (int s = 0; s < nS; s++) {
            int n = 0;
            for (int a = 0; a < nA; a++) {
                hardOut = 0;
                stat[s][a] = staticTerms(s, a, null);
                statHard[s][a] = Math.toIntExact(hardOut);
                if (hardOut == 0) {
                    buffer[n++] = a;
                }
            }
            elig[s] = Arrays.copyOf(buffer, n);
        }
    }

    /** The rules that read one seat and its holder and nothing else. */
    long staticTerms(int s, int a, long[] breakdown) {
        PosteAffectation poste = postes.get(s);
        Animateur animateur = animateurs.get(a);
        Stand stand = poste.getStand();
        Creneau creneau = poste.getCreneau();
        LocalDate date = creneau.getDate();
        long c = 0;
        // forEachIncludingUnassigned: a published vacation held by somebody who was not on it
        if (published[s] == 1 && !publishedBy.get(s).contains(animateur.getId())) {
            c += cost(Rule.STABILITE_DU_PLAN_PUBLIE, 1, breakdown);
        }
        if (sPast[s]) {
            return c;
        }
        if (animateur.isIndisponibleOn(date)) {
            c += cost(Rule.ANIMATEUR_DISPONIBLE, FORFAIT, breakdown);
        }
        if (animateur.isMineurOn(date)) {
            c += minorTerms(poste, animateur, breakdown);
        }
        if (stand.isPremium() && animateur.isDebutantFor(stand)) {
            c += cost(Rule.EXPERIENCE_REQUISE_POUR_STANDS_PREMIUM, 1, breakdown);
        }
        if (!animateur.hasCompetenceFor(stand)) {
            c += cost(Rule.APPRECIATION_INCOMPATIBLE, 1, breakdown);
        }
        if (!animateur.hasSouhaitFor(stand)) {
            c += cost(Rule.SOUHAITS_INCOMPATIBLES, 1, breakdown);
        }
        for (ContrainteAdHoc adHoc : forcedOffOf.get(a)) {
            if (scope(adHoc, poste)) {
                c += cost(Rule.INDISPONIBILITE_FORCEE, 1, breakdown);
            }
        }
        if (!sLocked[s]) {
            c += lockTerms(a, creneau, breakdown);
        }
        return c;
    }

    /** What the law forbids a minor on this seat: a stand for adults, the night, a public holiday. */
    private long minorTerms(PosteAffectation poste, Animateur animateur, long[] breakdown) {
        Creneau creneau = poste.getCreneau();
        LocalDate date = creneau.getDate();
        long c = 0;
        if (poste.getStand().isReserveMajeurs()) {
            c += cost(Rule.STAND_RESERVE_AUX_MAJEURS, FORFAIT, breakdown);
        }
        if (creneau.chevaucheNuit(
                animateur.isUnder16On(date) ? Creneau.DEBUT_NUIT_MOINS_DE_16_ANS : Creneau.DEBUT_NUIT_16_A_18_ANS)) {
            c += cost(Rule.TRAVAIL_DE_NUIT_INTERDIT_POUR_MINEUR, FORFAIT, breakdown);
        }
        if (JoursFeries.isFerieInFrance(date)) {
            c += cost(Rule.TRAVAIL_INTERDIT_JOUR_FERIE_MINEUR, FORFAIT, breakdown);
        }
        return c;
    }

    /** A seat that is not itself locked, taken by somebody a lock freezes: for the event, or on this timeslot. */
    private long lockTerms(int a, Creneau creneau, long[] breakdown) {
        long c = 0;
        for (VerrouillagePlanning lock : locksOf.get(a)) {
            if (lock.getType() == TypeVerrouillage.ANIMATEUR) {
                c += cost(Rule.ANIMATEUR_VERROUILLE_FIGE, 1, breakdown);
            } else if (lock.getType() == TypeVerrouillage.ANIMATEUR_CRENEAU
                    && lock.getCreneauId() != null
                    && lock.getCreneauId().equals(creneau.getId())) {
                c += cost(Rule.ANIMATEUR_VERROUILLE_CRENEAU_FIGE, 1, breakdown);
            }
        }
        return c;
    }
}
