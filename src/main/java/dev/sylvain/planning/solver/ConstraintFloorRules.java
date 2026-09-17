package dev.sylvain.planning.solver;

import dev.sylvain.planning.domain.AffectationPubliee;
import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.NiveauCompetence;
import dev.sylvain.planning.domain.PauseSurPoste;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;
import java.util.stream.Stream;

/**
 * How each non-hard constraint is read as a <b>floor</b>: a rule that matches
 * (almost) everything it evaluates is not measuring the plan, it is measuring
 * a hole in the referential — no wish declared, no referent named — and the
 * points it costs are a constant no solve will ever move. On a real edition
 * two such rules made 68 % of the medium score (see
 * {@code docs/memoire-du-projet.md}); nothing said so.
 *
 * <p>The signal is a ratio, {@code matches ÷ evaluated items}, and the
 * denominator depends on the granularity of the rule: one match per filled
 * seat for {@code souhaitsIncompatibles}, one per stand × timeslot group for
 * {@code standComplexeAvecReferent}, one per consecutive pair for
 * {@code eviterChangementEmplacementEloigne}. That table is this class, and it
 * is <b>exhaustive</b>: {@code ConstraintFloorRulesTest} fails on any
 * medium/soft constraint of {@link ConstraintCatalog} without a rule here, so
 * a new rule cannot silently be left out of the reading.</p>
 *
 * <p>Read by {@code PlanningDiagnosticService}, which computes the ratio and
 * attaches the missing data when one explains it. Nothing here decides
 * anything: a floor is reported, never switched off — see
 * {@code docs/decisions/0031-signaler-le-plancher-sans-le-decider.md}.</p>
 */
public final class ConstraintFloorRules {

    /**
     * Share of the evaluated items a rule must match before it is reported
     * as a floor. Not 100 %: on a real plan a handful of seats always escape
     * — a stand with no typologie, a seat left empty — and a rule matching
     * 98 % of them is just as constant as one matching all.
     */
    public static final double FLOOR_THRESHOLD = 0.95;

    /**
     * Items a rule must have evaluated before a floor <b>no missing data
     * explains</b> is reported. One match out of one item is 100 % and says
     * nothing at all: an edition being typed in — two stands, one timeslot —
     * would otherwise have almost every rule reported, and on the reference
     * fixture the two "consecutive pair" rules evaluate nine pairs, where nine
     * matches would be enough.
     *
     * <p>It does not gate a floor the referential explains: « nobody declared a
     * wish » is a fact about the data, as true of three seats as of three
     * thousand, and holding it back would hide the very case the feature was
     * built for.</p>
     */
    public static final int FLOOR_MIN_SAMPLE = 10;

    /**
     * Angular route of the screen where the missing data is entered. A plain
     * string on purpose: the frontend routes are not known to the server, so
     * this constant <b>must follow {@code app.routes.ts}</b> — like the links
     * of {@code ApplicationLinks}, it is the one place to change when the
     * route moves.
     */
    public static final String ROUTE_ANIMATEURS = "/animateurs";

    /**
     * What one match of a rule is counted against. Each value knows how to
     * count its items on a planning; {@link #NONE} is for the rules whose
     * match count says nothing per item — a single aggregate match
     * ({@code equilibrerCharge}), a reward, or a penalty carrying a weight
     * function — and is never a floor.
     *
     * <p>The weight function is the subtle one, and it cost a wrong reading:
     * when a rule penalises by a <em>magnitude</em> ({@code mineurs - majeurs},
     * distinct heads on a stand, days beyond the cap), its match count is the
     * number of items <b>in excess</b> and its score is that excess summed. A
     * ratio of 1.0 then means "everybody is off by an amount the solver can
     * reduce", not "a constant no solve will move" — the opposite of a floor.
     * Measured on the reference fixture, {@code eviterRoulementStandsPremium}
     * — the rule rewritten precisely to give the solver a monotonic gradient —
     * matched 45 of 45 premium stands and had its whole 1 414 medium points
     * declared unmovable. A gradient rule therefore has no per-item reading
     * here, whatever its grain.</p>
     */
    public enum Denominator {
        /** Seats with an animateur — the grain of the per-seat rules. */
        FILLED_SEATS(planning -> (int) filledSeats(planning).count()),
        /** Filled seats on a premium stand. */
        FILLED_PREMIUM_SEATS(planning -> (int) filledSeats(planning)
                .filter(poste -> poste.getStand().isPremium())
                .count()),
        /** Stand × timeslot groups holding at least one filled seat. */
        STAFFED_STAND_CRENEAU_GROUPS(planning -> (int) filledSeats(planning)
                .map(poste ->
                        poste.getStand().getId() + "|" + poste.getCreneau().getId())
                .distinct()
                .count()),
        /** Same animateur, same day, one seat ending exactly when the next starts. */
        CONSECUTIVE_PAIRS(ConstraintFloorRules::consecutivePairs),
        /** Seats on a stand × timeslot line the published plan had, empty ones included. */
        PUBLISHED_SEATS(ConstraintFloorRules::publishedSeats),
        /** Legal breaks owed on the post, when the edition declares them taken there. */
        BREAKS_DUE(ConstraintFloorRules::breaksDue),
        /** No per-item reading: a single aggregate match, or a reward. Never a floor. */
        NONE(null);

        private final ToIntFunction<PlanningEvenement> counter;

        Denominator(ToIntFunction<PlanningEvenement> counter) {
            this.counter = counter;
        }

        /** Items the rule evaluates on this planning, {@code null} for {@link #NONE}. */
        public Integer count(PlanningEvenement planning) {
            return counter == null ? null : counter.applyAsInt(planning);
        }
    }

    /**
     * The referential data whose absence makes a rule match everything, with
     * the wording the screen shows and the screen where it is entered. Only
     * the absences that <em>produce</em> a floor are listed: no wish declared
     * makes {@code souhaitsIncompatibles} match every seat, whereas no premium
     * stand makes {@code experienceRequisePourStandsPremium} match nothing —
     * inert, not a floor, and not this class's subject.
     *
     * <p>Each predicate asks the global question — « is there a referent at
     * all? » — where the rule asks a per-stand one (« a referent for
     * <em>this</em> stand »). It is deliberate, and it errs on the cautious
     * side: a referential holding referents, but only on typologies no stand
     * offers, has a real floor reported <b>without</b> its cause named rather
     * than a cause named wrongly. What is lost is the sentence and the link,
     * never the floor itself.</p>
     */
    public enum MissingData {
        SOUHAITS(
                "Aucun souhait déclaré sur les fiches animateur : la règle pénalise chaque poste pourvu.",
                ROUTE_ANIMATEURS,
                planning -> planning.getAnimateurs().stream()
                        .allMatch(animateur -> animateur.getSouhaits() == null
                                || animateur.getSouhaits().isEmpty())),
        APPRECIATIONS(
                "Aucune appréciation saisie sur les fiches animateur : la règle pénalise chaque poste pourvu.",
                ROUTE_ANIMATEURS,
                planning -> planning.getAnimateurs().stream()
                        .allMatch(animateur -> animateur.getCompetences() == null
                                || animateur.getCompetences().isEmpty())),
        REFERENTS(
                "Aucun animateur au niveau référent : la règle pénalise chaque stand sur chaque créneau.",
                ROUTE_ANIMATEURS,
                planning -> planning.getAnimateurs().stream().noneMatch(ConstraintFloorRules::hasReferentLevel)),
        NIVEAUX_COMPETENCE(
                "Aucune appréciation au-dessus du niveau débutant : la règle pénalise chaque poste premium pourvu.",
                ROUTE_ANIMATEURS,
                planning -> planning.getAnimateurs().stream().noneMatch(ConstraintFloorRules::hasLevelAboveBeginner));

        private final String libelle;
        private final String lien;
        private final Predicate<PlanningEvenement> absent;

        MissingData(String libelle, String lien, Predicate<PlanningEvenement> absent) {
            this.libelle = libelle;
            this.lien = lien;
            this.absent = absent;
        }

        /** The sentence naming the missing data, in the user's language. */
        public String libelle() {
            return libelle;
        }

        /** Angular route of the screen where the data is entered. */
        public String lien() {
            return lien;
        }

        /** True when the referential of this planning holds none of that data. */
        public boolean absentFrom(PlanningEvenement planning) {
            return planning.getAnimateurs() != null && absent.test(planning);
        }
    }

    /**
     * @param denominator what one match is counted against
     * @param missingData the absence that explains a floor of this rule, or
     *                    {@code null} when no single piece of data does — the
     *                    floor is then reported without a cause
     */
    public record FloorRule(Denominator denominator, MissingData missingData) {}

    private static final Map<String, FloorRule> BY_NAME = Map.ofEntries(
            rule("standComplexeAvecReferent", Denominator.STAFFED_STAND_CRENEAU_GROUPS, MissingData.REFERENTS),
            rule("equilibrerCharge", Denominator.NONE, null),
            rule("stabiliteDuPlanPublie", Denominator.PUBLISHED_SEATS, null),
            rule("repartitionMineursParCreneau", Denominator.NONE, null),
            rule(
                    "experienceRequisePourStandsPremium",
                    Denominator.FILLED_PREMIUM_SEATS,
                    MissingData.NIVEAUX_COMPETENCE),
            rule("eviterRoulementStandsPremium", Denominator.NONE, null),
            rule("eviterChangementEmplacementEloigne", Denominator.CONSECUTIVE_PAIRS, null),
            rule("limiterEmplacementsParJour", Denominator.NONE, null),
            rule("eviterEnchainementStandsEpuisants", Denominator.CONSECUTIVE_PAIRS, null),
            rule("eviterFermeturePuisOuverture", Denominator.NONE, null),
            rule("appreciationIncompatible", Denominator.FILLED_SEATS, MissingData.APPRECIATIONS),
            rule("souhaitsIncompatibles", Denominator.FILLED_SEATS, MissingData.SOUHAITS),
            rule("limiterTypologiesDistinctesParAnimateur", Denominator.NONE, null),
            rule("maxJoursConsecutifsTravailles", Denominator.NONE, null),
            rule("pauseSurPosteSansRelais", Denominator.BREAKS_DUE, null),
            rule("coupureRepasPlacementPrefere", Denominator.NONE, null),
            rule("affiniteAdHoc", Denominator.NONE, null),
            rule("favoriserMixiteDesNiveaux", Denominator.STAFFED_STAND_CRENEAU_GROUPS, null),
            rule("equilibrerCreneauxPenibles", Denominator.NONE, null),
            rule("preserverBufferPolyvalents", Denominator.NONE, null));

    private ConstraintFloorRules() {}

    private static Map.Entry<String, FloorRule> rule(String name, Denominator denominator, MissingData missingData) {
        return Map.entry(name, new FloorRule(denominator, missingData));
    }

    /** The rule of a constraint by name, {@code null} for a hard one or an unknown name. */
    public static FloorRule of(String constraintName) {
        return BY_NAME.get(constraintName);
    }

    /** Every constraint name this table covers. */
    public static Set<String> names() {
        return BY_NAME.keySet();
    }

    private static Stream<PosteAffectation> filledSeats(PlanningEvenement planning) {
        return planning.getPostes().stream()
                .filter(poste ->
                        poste.getAnimateur() != null && poste.getStand() != null && poste.getCreneau() != null);
    }

    private static boolean hasReferentLevel(Animateur animateur) {
        return animateur.getCompetences() != null
                && animateur.getCompetences().containsValue(NiveauCompetence.REFERENT);
    }

    private static boolean hasLevelAboveBeginner(Animateur animateur) {
        return animateur.getCompetences() != null
                && animateur.getCompetences().values().stream().anyMatch(niveau -> niveau != NiveauCompetence.DEBUTANT);
    }

    /**
     * Pairs of seats the two "consecutive" rules join on: same animateur,
     * same day, the first ending exactly when the second starts. Counted by
     * indexing the seats on their start time rather than by comparing every
     * pair, the same way the constraints' joiners do.
     */
    private static int consecutivePairs(PlanningEvenement planning) {
        Map<String, Map<LocalTime, Integer>> startsByAnimateurDay = new HashMap<>();
        List<PosteAffectation> seats = filledSeats(planning)
                .filter(poste -> poste.getCreneau().getHeureDebut() != null
                        && poste.getCreneau().getHeureFin() != null)
                .toList();
        for (PosteAffectation poste : seats) {
            startsByAnimateurDay
                    .computeIfAbsent(animateurDay(poste), key -> new HashMap<>())
                    .merge(poste.getCreneau().getHeureDebut(), 1, Integer::sum);
        }
        int pairs = 0;
        for (PosteAffectation poste : seats) {
            pairs += startsByAnimateurDay
                    .get(animateurDay(poste))
                    .getOrDefault(poste.getCreneau().getHeureFin(), 0);
        }
        return pairs;
    }

    private static String animateurDay(PosteAffectation poste) {
        return poste.getAnimateur().getId() + "|" + poste.getCreneau().getJour();
    }

    private static int publishedSeats(PlanningEvenement planning) {
        if (planning.getAffectationsPubliees() == null
                || planning.getAffectationsPubliees().isEmpty()) {
            return 0;
        }
        // Named the way the rule names a line: day, hours, stand (issue #578).
        Set<String> publishedLines = new HashSet<>();
        planning.getAffectationsPubliees().forEach(publiee -> publishedLines.add(publiee.key()));
        return (int) planning.getPostes().stream()
                .filter(poste -> poste.getStand() != null && poste.getCreneau() != null)
                .filter(poste -> publishedLines.contains(AffectationPubliee.key(
                        poste.getStand().getId(),
                        poste.getCreneau().getDate(),
                        poste.getCreneau().getHeureDebut(),
                        poste.getCreneau().getHeureFin())))
                .count();
    }

    /**
     * The breaks {@code pauseSurPosteSansRelais} judges: what
     * {@link PauseSurPoste#dues} owes for each animateur's day, and nothing
     * when the edition does not declare the break taken on the post — the
     * rule is then silent, and so is its denominator.
     */
    private static int breaksDue(PlanningEvenement planning) {
        boolean pauseSurPoste = planning.getParametresLegaux() != null
                && planning.getParametresLegaux().stream().anyMatch(parametres -> parametres.isPauseSurPoste());
        if (!pauseSurPoste) {
            return 0;
        }
        Map<String, List<PosteAffectation>> seatsByAnimateurDate = new HashMap<>();
        filledSeats(planning)
                .filter(poste -> poste.getCreneau().getDate() != null && poste.heureDebutEffectif() != null)
                .forEach(poste -> seatsByAnimateurDate
                        .computeIfAbsent(
                                poste.getAnimateur().getId() + "|"
                                        + poste.getCreneau().getDate(),
                                key -> new ArrayList<>())
                        .add(poste));
        int due = 0;
        for (List<PosteAffectation> day : seatsByAnimateurDate.values()) {
            due += PauseSurPoste.dues(day).size();
        }
        return due;
    }
}
