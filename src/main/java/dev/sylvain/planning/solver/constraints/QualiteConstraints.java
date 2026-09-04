package dev.sylvain.planning.solver.constraints;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import ai.timefold.solver.core.api.score.HardMediumSoftScore;
import ai.timefold.solver.core.api.score.stream.Constraint;
import ai.timefold.solver.core.api.score.stream.ConstraintCollectors;
import ai.timefold.solver.core.api.score.stream.ConstraintFactory;
import ai.timefold.solver.core.api.score.stream.Joiners;
import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.Emplacement;
import dev.sylvain.planning.domain.NiveauEffort;
import dev.sylvain.planning.domain.ParametresQualite;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;

/**
 * Medium constraints: strongly penalised but non-blocking organisational
 * quality rules: referent coverage on complex stands, balanced workload,
 * avoiding a majority of minors on a single slot, and capping consecutive
 * worked days.
 */
public final class QualiteConstraints {

    /**
     * Beyond this great-circle distance between two emplacements, moving an
     * animateur from one to the other between two back-to-back slots is
     * considered a costly trek rather than a short walk across the same site.
     */
    static final double DISTANCE_ELOIGNEE_METRES = 300.0;

    public Constraint[] define(ConstraintFactory constraintFactory) {
        return new Constraint[] {
                standComplexeAvecReferent(constraintFactory),
                equilibrerCharge(constraintFactory),
                repartitionMineursParCreneau(constraintFactory),
                experienceRequisePourStandsPremium(constraintFactory),
                eviterRoulementStandsPremium(constraintFactory),
                eviterChangementEmplacementEloigne(constraintFactory),
                limiterEmplacementsParJour(constraintFactory),
                eviterEnchainementStandsEpuisants(constraintFactory),
                appreciationIncompatible(constraintFactory),
                souhaitsIncompatibles(constraintFactory),
                limiterTypologiesDistinctesParAnimateur(constraintFactory),
                maxJoursConsecutifsTravailles(constraintFactory)
        };
    }

    private Constraint standComplexeAvecReferent(ConstraintFactory constraintFactory) {
        return ConstraintToggleSupport.actif(constraintFactory.forEach(PosteAffectation.class),
                "standComplexeAvecReferent")
                .groupBy(PosteAffectation::getStand,
                        PosteAffectation::getCreneau,
                        ConstraintCollectors.sum(poste -> poste.getAnimateur() != null
                                && poste.getAnimateur().isReferentFor(poste.getStand()) ? 1 : 0))
                .filter((stand, creneau, nombreReferents) -> nombreReferents == 0)
                .penalize(HardMediumSoftScore.ONE_MEDIUM)
                .asConstraint("standComplexeAvecReferent");
    }

    /**
     * Scale applied to {@code unfairness()} before truncating to an int. The
     * measure is typically well below 1.0 for the kind of imbalance that shows
     * up in practice, and {@code HardMediumSoftScore} only carries integers:
     * without this factor, every such imbalance truncates to exactly 0 and the
     * solver gets no gradient to climb to improve fairness. x10 keeps a
     * deviation of 0.1 visible as 1 point while staying in the same order of
     * magnitude as the other medium constraints in this class, which score 1
     * point per individual violation.
     */
    private static final BigDecimal UNFAIRNESS_SCALE = BigDecimal.TEN;

    private Constraint equilibrerCharge(ConstraintFactory constraintFactory) {
        // loadBalance().unfairness() is 0 when every animateur carries the same
        // number of postes and grows with the deviation, giving a clean fairness
        // signal without the huge non-zero baseline of a sum-of-squares formula.
        return ConstraintToggleSupport.actif(constraintFactory.forEach(PosteAffectation.class), "equilibrerCharge")
                .filter(poste -> poste.getAnimateur() != null)
                .groupBy(ConstraintCollectors.loadBalance(PosteAffectation::getAnimateur))
                .penalize(HardMediumSoftScore.ONE_MEDIUM,
                        loadBalance -> loadBalance.unfairness()
                                .multiply(UNFAIRNESS_SCALE)
                                .setScale(0, RoundingMode.HALF_UP)
                                .intValue())
                .asConstraint("equilibrerCharge");
    }

    private Constraint repartitionMineursParCreneau(ConstraintFactory constraintFactory) {
        return ConstraintToggleSupport.actif(constraintFactory.forEach(PosteAffectation.class),
                "repartitionMineursParCreneau")
                .filter(poste -> poste.getAnimateur() != null && poste.getCreneau() != null)
                .groupBy(PosteAffectation::getStand,
                        PosteAffectation::getCreneau,
                        ConstraintCollectors.sum(poste -> poste.getAnimateur()
                                .isMineurOn(poste.getCreneau().getDate()) ? 1 : 0),
                        ConstraintCollectors.sum(poste -> poste.getAnimateur()
                                .isMajeurOn(poste.getCreneau().getDate()) ? 1 : 0))
                .filter((stand, creneau, mineurs, majeurs) -> mineurs > majeurs)
                .penalize(HardMediumSoftScore.ONE_MEDIUM,
                        (stand, creneau, mineurs, majeurs) -> mineurs - majeurs)
                .asConstraint("repartitionMineursParCreneau");
    }

    private Constraint experienceRequisePourStandsPremium(ConstraintFactory constraintFactory) {
        return ConstraintToggleSupport.actif(constraintFactory.forEach(PosteAffectation.class),
                "experienceRequisePourStandsPremium")
                .filter(poste -> poste.getStand().isPremium()
                        && poste.getAnimateur() != null
                        && poste.getAnimateur().isDebutantFor(poste.getStand()))
                .penalize(HardMediumSoftScore.ONE_MEDIUM)
                .asConstraint("experienceRequisePourStandsPremium");
    }

    /**
     * On a premium stand, prefer keeping the same (already-vetted) animateurs
     * instead of rotating people through it: penalises how many <b>distinct</b>
     * animateurs the stand sees, beyond the one crew it needs at a time.
     *
     * <p>"Beyond one crew" is the largest number of seats poste generation
     * creates for the stand on any of its créneaux
     * ({@link Creneau#siegesSimultanes(Stand)} — a window's own effectif, or
     * the stand's {@code effectifMin}): a stand needing two people at once,
     * held by the same two all event, is perfect continuity and scores zero.
     * Simultaneous multi-staffing was never rotation, and still isn't; and a
     * stand whose afternoon window asks for five people cannot be blamed for
     * showing five faces.</p>
     *
     * <p>This used to count <em>pairs</em> of postes on the same stand held by
     * different animateurs on different créneaux, which made it quadratic in
     * the number of postes per stand — and unusable as soon as an event flags
     * more than a handful of premium stands. Measured on the real 2026 data
     * (45 premium stands of 65, 3 502 postes): 48 567 possible pairs, a
     * penalty of 38 591 out of a 46 284 total medium score — 84 % of it — of
     * which roughly 25 000 are <b>structurally unreachable</b>, since a stand
     * open twelve days cannot legally be held by one person (48 h/week, 11 h
     * daily rest, 6 days/week). The solver therefore spent its budget sliding
     * down a slope that bottoms out far above zero, while 36 seats stayed
     * unfilled. Counting heads instead of pairs brings the same intent
     * (continuity, monotonically rewarded) back to the order of magnitude of
     * the other medium rules, and drops a quadratic join maintained at every
     * move.</p>
     */
    private Constraint eviterRoulementStandsPremium(ConstraintFactory constraintFactory) {
        return ConstraintToggleSupport.actif(constraintFactory.forEach(PosteAffectation.class),
                "eviterRoulementStandsPremium")
                .filter(poste -> poste.getStand().isPremium())
                .groupBy(PosteAffectation::getStand,
                        ConstraintCollectors.countDistinct(PosteAffectation::getAnimateur),
                        ConstraintCollectors.max(QualiteConstraints::equipage))
                .filter((stand, animateursDistincts, equipage) -> animateursDistincts > equipage)
                .penalize(HardMediumSoftScore.ONE_MEDIUM,
                        (stand, animateursDistincts, equipage) -> animateursDistincts - equipage)
                .asConstraint("eviterRoulementStandsPremium");
    }

    /**
     * Seats the stand of this poste needs staffed at the same time on this
     * poste's créneau — one crew; the largest over the stand's postes is the
     * allowance above. Never below one: a poste exists, so somebody holds it.
     */
    private static int equipage(PosteAffectation poste) {
        return Math.max(1, poste.getCreneau().siegesSimultanes(poste.getStand()));
    }

    /**
     * Same animateur, two back-to-back slots (same day, one ending exactly when
     * the other starts), different stands whose emplacements are far apart: the
     * switch costs a real trek across town, so it's penalised.
     *
     * <p>"Back-to-back" is expressed as indexed joiners (same day, previous
     * slot's {@code heureFin} equal to the next slot's {@code heureDebut})
     * instead of a predicate over every pair of postes an animateur holds. The
     * previous formulation built ~13 500 pair tuples on
     * {@code scenario-complet.yaml} (150 animateurs × ~14 postes each) purely to
     * discard almost all of them; the join now only produces genuinely
     * consecutive slots. Each qualifying pair still yields exactly one match,
     * generated in the (previous, next) order only.</p>
     */
    private Constraint eviterChangementEmplacementEloigne(ConstraintFactory constraintFactory) {
        return ConstraintToggleSupport.actif(constraintFactory.forEach(PosteAffectation.class),
                "eviterChangementEmplacementEloigne")
                .filter(poste -> poste.getStand() != null
                        && poste.getStand().getEmplacement() != null
                        && poste.getCreneau() != null
                        && poste.getCreneau().getHeureFin() != null)
                .join(PosteAffectation.class,
                        Joiners.equal(PosteAffectation::getAnimateur),
                        Joiners.equal(poste -> poste.getCreneau().getJour()),
                        Joiners.equal(poste -> poste.getCreneau().getHeureFin(),
                                poste -> poste.getCreneau().getHeureDebut()))
                .filter((precedent, suivant) -> !precedent.getStand().equals(suivant.getStand())
                        && emplacementsEloignes(precedent.getStand(), suivant.getStand()))
                .penalize(HardMediumSoftScore.ONE_MEDIUM)
                .asConstraint("eviterChangementEmplacementEloigne");
    }

    /**
     * Caps how many distinct emplacements an animateur covers in a single day
     * (issue #82). {@link #eviterChangementEmplacementEloigne} only sees one
     * pair of back-to-back slots at a time, and only when they are more than
     * {@link #DISTANCE_ELOIGNEE_METRES} apart: ten hops between two neighbouring
     * zones cost nothing there, while the animateur genuinely spends the day
     * moving.
     *
     * <p>Counted as <b>distinct zones</b>, not as transitions: A → B → A is two
     * zones, not two moves, which is both easier to explain and free of the
     * double count a transition-based rule pays on a return trip.</p>
     *
     * <p>The two rules do not mechanically stack on the same fact: this one
     * ignores the order and the distance, and only fires above the cap, so a
     * single far move between two zones (the case
     * {@code eviterChangementEmplacementEloigne} penalises) stays untouched
     * here as long as the day holds no more than {@code
     * maxEmplacementsDistinctsParJour} zones.</p>
     *
     * <p>Inert on a dataset where {@code Stand.emplacement} is not filled in
     * (see #118): a poste without an emplacement is filtered out, so nothing is
     * counted at all rather than everything counting as one big zone.</p>
     */
    private Constraint limiterEmplacementsParJour(ConstraintFactory constraintFactory) {
        return ConstraintToggleSupport.actif(constraintFactory.forEach(PosteAffectation.class),
                "limiterEmplacementsParJour")
                .filter(poste -> poste.getAnimateur() != null
                        && poste.getStand() != null
                        && poste.getStand().getEmplacement() != null
                        && poste.getCreneau() != null)
                .groupBy(PosteAffectation::getAnimateur,
                        poste -> poste.getCreneau().getJour(),
                        ConstraintCollectors.toSet(poste -> poste.getStand().getEmplacement()))
                .join(ParametresQualite.class)
                .filter((animateur, jour, emplacements, parametres) -> emplacements
                        .size() > parametres.maxEmplacementsDistinctsParJour())
                .penalize(HardMediumSoftScore.ONE_MEDIUM,
                        (animateur, jour, emplacements, parametres) -> emplacements.size()
                                - parametres.maxEmplacementsDistinctsParJour())
                .asConstraint("limiterEmplacementsParJour");
    }

    /**
     * Same animateur, two back-to-back slots (same day, one ending exactly when
     * the other starts), both on a physically exhausting stand
     * ({@link NiveauEffort#EPUISANT}, e.g. "Homme-jeu"): no rest and no easier
     * stand in between, so the enchaînement is penalised. Mirrors
     * {@link #eviterChangementEmplacementEloigne}'s join shape.
     */
    private Constraint eviterEnchainementStandsEpuisants(ConstraintFactory constraintFactory) {
        return ConstraintToggleSupport.actif(constraintFactory.forEach(PosteAffectation.class),
                "eviterEnchainementStandsEpuisants")
                .filter(poste -> poste.getStand() != null
                        && poste.getStand().getNiveauEffort() == NiveauEffort.EPUISANT
                        && poste.getCreneau() != null
                        && poste.getCreneau().getHeureFin() != null)
                .join(PosteAffectation.class,
                        Joiners.equal(PosteAffectation::getAnimateur),
                        Joiners.equal(poste -> poste.getCreneau().getJour()),
                        Joiners.equal(poste -> poste.getCreneau().getHeureFin(),
                                poste -> poste.getCreneau().getHeureDebut()))
                .filter((precedent, suivant) -> suivant.getStand() != null
                        && suivant.getStand().getNiveauEffort() == NiveauEffort.EPUISANT)
                .penalize(HardMediumSoftScore.ONE_MEDIUM)
                .asConstraint("eviterEnchainementStandsEpuisants");
    }

    /**
     * The former hard {@code competenceCompatible} rule, downgraded to
     * medium: an appreciation mismatch is now a strongly penalised quality
     * issue, not a blocking one — the administrator's appreciation is
     * privileged over the animateur's wish via a higher weight in
     * {@code application.properties} ({@code planning.constraint-weights.
     * appreciationIncompatible}), not in this literal.
     */
    private Constraint appreciationIncompatible(ConstraintFactory constraintFactory) {
        return ConstraintToggleSupport.actif(constraintFactory.forEach(PosteAffectation.class),
                "appreciationIncompatible")
                .filter(poste -> poste.getAnimateur() != null
                        && !poste.getAnimateur().hasCompetenceFor(poste.getStand()))
                .penalize(HardMediumSoftScore.ONE_MEDIUM)
                .asConstraint("appreciationIncompatible");
    }

    /**
     * Mirrors {@link #appreciationIncompatible} but on the animateur's
     * declared wishes rather than the administrator's appreciation — weighted
     * lower ({@code planning.constraint-weights.souhaitsIncompatibles}) so
     * the solver privileges the real appreciation when the two disagree.
     */
    private Constraint souhaitsIncompatibles(ConstraintFactory constraintFactory) {
        return ConstraintToggleSupport.actif(constraintFactory.forEach(PosteAffectation.class),
                "souhaitsIncompatibles")
                .filter(poste -> poste.getAnimateur() != null
                        && !poste.getAnimateur().hasSouhaitFor(poste.getStand()))
                .penalize(HardMediumSoftScore.ONE_MEDIUM)
                .asConstraint("souhaitsIncompatibles");
    }

    /** Typologies the animateur is appreciated for that this poste's stand actually offers. */
    private static Set<String> likedTypologiesOfPoste(PosteAffectation poste) {
        Animateur animateur = poste.getAnimateur();
        Stand stand = poste.getStand();
        Set<String> intersection = new HashSet<>(stand.getTypologiesProposees());
        intersection.retainAll(animateur.getCompetences().keySet());
        return intersection;
    }

    /**
     * Below this count of distinct typologies mastered across an animateur's
     * whole planning, no penalty applies — the business considers 1-2
     * typologies the ideal case (5 is the cited bad example).
     */
    private static final int TYPOLOGIES_DISTINCTES_SANS_PENALITE = 2;

    /**
     * An animateur spread across too many distinct typologies of jeu over the
     * whole planning is penalised, proportionally to how far past the
     * threshold they are — the same gradient logic as {@link #equilibrerCharge}.
     */
    private Constraint limiterTypologiesDistinctesParAnimateur(ConstraintFactory constraintFactory) {
        return ConstraintToggleSupport.actif(constraintFactory.forEach(PosteAffectation.class),
                "limiterTypologiesDistinctesParAnimateur")
                // A ninja is versatile by definition: spreading them across many
                // typologies is what they are there for, so the cap doesn't apply.
                .filter(poste -> poste.getAnimateur() != null && poste.getStand() != null
                        && !poste.getAnimateur().isNinja())
                .flatten(QualiteConstraints::likedTypologiesOfPoste)
                .groupBy((poste, typologie) -> poste.getAnimateur(),
                        ConstraintCollectors.toSet((poste, typologie) -> typologie))
                .filter((animateur, typologies) -> typologies.size() > TYPOLOGIES_DISTINCTES_SANS_PENALITE)
                .penalize(HardMediumSoftScore.ONE_MEDIUM,
                        (animateur, typologies) -> typologies.size() - TYPOLOGIES_DISTINCTES_SANS_PENALITE)
                .asConstraint("limiterTypologiesDistinctesParAnimateur");
    }

    /**
     * Above this many consecutive worked days, at least one rest day is due —
     * fewer is fine, more is not. Not a Code du travail article: the six-day
     * ISO-week ceiling ({@code maxJoursTravaillesParSemaine}) already lets a
     * run straddle a week boundary (e.g. Thu-Fri-Sat-Sun-Mon-Tue-Wed = 6 days
     * in each of two different ISO weeks, but 7 in a row). This constraint
     * catches that straddling run directly, uncoupled from the week grid, but
     * is kept as an organisational-quality medium rather than a legal hard
     * rule since the underlying legal basis for a rolling (non-weekly) count
     * is not established.
     */
    private static final int JOURS_CONSECUTIFS_TRAVAILLES_MAX = 6;

    /**
     * No animateur works more than {@link #JOURS_CONSECUTIFS_TRAVAILLES_MAX}
     * calendar days in a row.
     *
     * <p>Grouped on {@code getJour()} rather than the calendar date: adjacent
     * calendar days always get adjacent {@code jour} numbers (see
     * {@code Creneau.assignerJours}), so a longest-run scan over the sorted
     * set of distinct worked {@code jour} values is exactly a longest run of
     * consecutive calendar days, without any date arithmetic.</p>
     */
    private Constraint maxJoursConsecutifsTravailles(ConstraintFactory constraintFactory) {
        return ConstraintToggleSupport.actif(constraintFactory.forEach(PosteAffectation.class),
                "maxJoursConsecutifsTravailles")
                .filter(poste -> poste.getAnimateur() != null && poste.getCreneau() != null)
                .groupBy(PosteAffectation::getAnimateur,
                        ConstraintCollectors.toSet(poste -> poste.getCreneau().getJour()))
                .filter((animateur, jours) -> longestConsecutiveSequence(jours)
                        > JOURS_CONSECUTIFS_TRAVAILLES_MAX)
                .penalize(HardMediumSoftScore.ONE_MEDIUM,
                        (animateur, jours) -> longestConsecutiveSequence(jours)
                                - JOURS_CONSECUTIFS_TRAVAILLES_MAX)
                .asConstraint("maxJoursConsecutifsTravailles");
    }

    /** Longest run of consecutive integers inside the set. */
    private static int longestConsecutiveSequence(Set<Integer> jours) {
        List<Integer> tries = jours.stream().sorted().toList();
        int longest = 0;
        int courante = 0;
        int precedent = Integer.MIN_VALUE;
        for (int jour : tries) {
            courante = jour == precedent + 1 ? courante + 1 : 1;
            longest = Math.max(longest, courante);
            precedent = jour;
        }
        return longest;
    }

    private boolean emplacementsEloignes(Stand standA, Stand standB) {
        Emplacement emplacementA = standA.getEmplacement();
        Emplacement emplacementB = standB.getEmplacement();
        if (emplacementA == null || emplacementB == null) {
            return false;
        }
        Double distanceMetres = emplacementA.distanceMetresTo(emplacementB);
        return distanceMetres != null && distanceMetres > DISTANCE_ELOIGNEE_METRES;
    }
}
