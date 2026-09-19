package dev.sylvain.planning.solver.constraints;

import ai.timefold.solver.core.api.score.HardMediumSoftScore;
import ai.timefold.solver.core.api.score.stream.Constraint;
import ai.timefold.solver.core.api.score.stream.ConstraintCollectors;
import ai.timefold.solver.core.api.score.stream.ConstraintFactory;
import ai.timefold.solver.core.api.score.stream.Joiners;
import ai.timefold.solver.core.api.score.stream.bi.BiConstraintStream;
import ai.timefold.solver.core.api.score.stream.uni.UniConstraintStream;
import dev.sylvain.planning.domain.AffectationPubliee;
import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.Emplacement;
import dev.sylvain.planning.domain.NiveauEffort;
import dev.sylvain.planning.domain.ParametresLegaux;
import dev.sylvain.planning.domain.ParametresQualite;
import dev.sylvain.planning.domain.PauseSurPoste;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Organisational quality rules: referent coverage on complex stands, balanced
 * workload, avoiding a majority of minors on a single slot, and capping
 * consecutive worked days. Medium — strongly penalised but non-blocking — with
 * two exceptions that are hard and say why in their own javadoc:
 * {@code pauseSurPosteSansRelais}, and the default-off
 * {@code maxJoursConsecutifsTravaillesDur}.
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
            eviterFermeturePuisOuverture(constraintFactory),
            appreciationIncompatible(constraintFactory),
            souhaitsIncompatibles(constraintFactory),
            limiterTypologiesDistinctesParAnimateur(constraintFactory),
            maxJoursConsecutifsTravailles(constraintFactory),
            maxJoursConsecutifsTravaillesDur(constraintFactory),
            pauseSurPosteSansRelais(constraintFactory),
            stabiliteDuPlanPublie(constraintFactory)
        };
    }

    /**
     * Stability of the published plan: every seat of a line (stand × créneau)
     * the published plan had, whose holder is not the one that plan named,
     * costs one medium point — one point per person who would have to be told
     * « votre emploi du temps a changé ». Measured on a real edition, a
     * re-solve started from a good plan reshuffled dozens of people for a
     * marginal equity gain, because nothing in the score said that moving a
     * person already informed has a cost. This is that cost, dosed against
     * the other medium rules.
     *
     * <p>Only a line the published plan <em>had</em> is judged: a stand or a
     * créneau created since is free, there is nobody to keep there. A
     * published seat left <em>empty</em> costs the same point as one given
     * to somebody else — the holder was told they worked there, and the hole
     * is theirs. It is also what keeps the solver from luring a published
     * holder onto a new line for free and leaving behind a hole nobody can
     * fill without paying: measured (ADR 0025), counting the hole moves the
     * holes onto the new lines, where filling them costs nothing. Silent
     * while nothing has been published: the list of facts is then empty and
     * {@code ifExists} never matches.</p>
     */
    private Constraint stabiliteDuPlanPublie(ConstraintFactory constraintFactory) {
        return ConstraintToggleSupport.actif(
                        constraintFactory.forEachIncludingUnassigned(PosteAffectation.class), "stabiliteDuPlanPublie")
                // A past seat cannot be given back to whoever was told
                // (ADR 0044): what it holds is what was worked.
                .filter(poste -> PastSeats.reproachable(poste)
                        && poste.getStand() != null
                        && poste.getCreneau() != null
                        && poste.getCreneau().getDate() != null)
                .ifExists(
                        AffectationPubliee.class,
                        Joiners.equal(QualiteConstraints::vacationKey, AffectationPubliee::key))
                .ifNotExists(
                        AffectationPubliee.class,
                        Joiners.equal(QualiteConstraints::vacationKey, AffectationPubliee::key),
                        Joiners.equal(QualiteConstraints::holderIdOrNobody, AffectationPubliee::animateurId))
                .penalize(HardMediumSoftScore.ONE_MEDIUM)
                .asConstraint("stabiliteDuPlanPublie");
    }

    /**
     * The line this seat is on, named the way the publication named it: day,
     * hours and stand, never the créneau id (issue #578).
     */
    private static String vacationKey(PosteAffectation poste) {
        Creneau creneau = poste.getCreneau();
        return AffectationPubliee.key(
                poste.getStand().getId(), creneau.getDate(), creneau.getHeureDebut(), creneau.getHeureFin());
    }

    /** The holder's id, or a value no published fact carries: an empty seat matches no holder. */
    private static String holderIdOrNobody(PosteAffectation poste) {
        return poste.getAnimateur() == null ? "" : poste.getAnimateur().getId();
    }

    private Constraint standComplexeAvecReferent(ConstraintFactory constraintFactory) {
        return ConstraintToggleSupport.actif(
                        constraintFactory.forEach(PosteAffectation.class), "standComplexeAvecReferent")
                // The line is charged only while one of its staffed seats is
                // still ahead of now (ADR 0044), folded next to the count.
                .groupBy(
                        PosteAffectation::getStand,
                        PosteAffectation::getCreneau,
                        PastSeats.withAhead(ConstraintCollectors.sum(poste -> poste.getAnimateur() != null
                                        && poste.getAnimateur().isReferentFor(poste.getStand())
                                ? 1
                                : 0)))
                .filter((stand, creneau, referents) -> referents.ahead() > 0 && referents.value() == 0)
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
                // The past seats weigh in the balance; it is charged only while
                // a seat is still ahead of now (ADR 0044) — folded into the
                // group, since a filter on this one global tuple would re-scan
                // every seat at every move.
                .groupBy(PastSeats.withAhead(ConstraintCollectors.loadBalance(PosteAffectation::getAnimateur)))
                .filter(charge -> charge.ahead() > 0)
                .penalize(
                        HardMediumSoftScore.ONE_MEDIUM,
                        charge -> PastSeats.scaledUnfairness(charge.value(), UNFAIRNESS_SCALE))
                .asConstraint("equilibrerCharge");
    }

    private Constraint repartitionMineursParCreneau(ConstraintFactory constraintFactory) {
        return ConstraintToggleSupport.actif(
                        constraintFactory.forEach(PosteAffectation.class), "repartitionMineursParCreneau")
                .filter(poste -> poste.getAnimateur() != null && poste.getCreneau() != null)
                // The line is charged only while one of its seats is still
                // ahead of now (ADR 0044), folded next to the two counts.
                .groupBy(
                        PosteAffectation::getStand,
                        PosteAffectation::getCreneau,
                        ConstraintCollectors.compose(
                                ConstraintCollectors.sum(poste -> poste.getAnimateur()
                                                .isMineurOn(poste.getCreneau().getDate())
                                        ? 1
                                        : 0),
                                ConstraintCollectors.sum(poste -> poste.getAnimateur()
                                                .isMajeurOn(poste.getCreneau().getDate())
                                        ? 1
                                        : 0),
                                PastSeats.ahead(),
                                Ages::new))
                .filter((stand, creneau, ages) -> ages.ahead() > 0 && ages.mineurs() > ages.majeurs())
                .penalize(HardMediumSoftScore.ONE_MEDIUM, (stand, creneau, ages) ->
                        (int) (ages.mineurs() - ages.majeurs()))
                .asConstraint("repartitionMineursParCreneau");
    }

    private Constraint experienceRequisePourStandsPremium(ConstraintFactory constraintFactory) {
        return ConstraintToggleSupport.actif(
                        constraintFactory.forEach(PosteAffectation.class), "experienceRequisePourStandsPremium")
                .filter(poste -> poste.getStand().isPremium()
                        && poste.getAnimateur() != null
                        && PastSeats.reproachable(poste)
                        && poste.getAnimateur().isDebutantFor(poste.getStand()))
                .penalize(HardMediumSoftScore.ONE_MEDIUM)
                .asConstraint("experienceRequisePourStandsPremium");
    }

    /**
     * On a premium stand, prefer keeping the same (already-vetted) animateurs
     * instead of rotating people through it: penalises how many <b>distinct</b>
     * animateurs the stand sees, beyond the one crew it needs at a time.
     *
     * <p>"Beyond one crew" is the largest number of seats the stand holds on
     * any one créneau — counted from the seats themselves rather than by
     * replaying the opening geometry: they <em>are</em> what poste generation
     * created, so the allowance follows a window's own effectif for free, and
     * the rule stays a field read on the solver's hot path. A stand needing
     * two people at once, held by the same two all event, is perfect
     * continuity and scores zero.
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
        return ConstraintToggleSupport.actif(
                        constraintFactory.forEach(PosteAffectation.class), "eviterRoulementStandsPremium")
                .filter(poste -> poste.getStand().isPremium())
                // The faces already seen count; the stand is charged only
                // while one of its seats is still ahead of now (ADR 0044) —
                // folded into the group, a premium stand holding hundreds of
                // seats a filter would re-scan at every move.
                .groupBy(
                        PosteAffectation::getStand,
                        PastSeats.withAhead(ConstraintCollectors.countDistinct(PosteAffectation::getAnimateur)))
                .filter((stand, visages) -> visages.ahead() > 0)
                .map((stand, visages) -> stand, (stand, visages) -> visages.value())
                .join(crewByStand(constraintFactory), Joiners.equal((stand, têtes) -> stand, Equipage::stand))
                .filter((stand, animateursDistincts, equipage) -> animateursDistincts > equipage.sieges())
                .penalize(
                        HardMediumSoftScore.ONE_MEDIUM,
                        (stand, animateursDistincts, equipage) -> animateursDistincts - equipage.sieges())
                .asConstraint("eviterRoulementStandsPremium");
    }

    /**
     * Seats a premium stand holds on its busiest créneau — its crew: the seats
     * of one (stand, créneau) counted, then the largest over the stand's
     * créneaux. One seat exists per person to staff, so counting them is the
     * same answer as reading the windows, without walking them at every move.
     */
    private static UniConstraintStream<Equipage> crewByStand(ConstraintFactory constraintFactory) {
        return constraintFactory
                .forEach(PosteAffectation.class)
                .filter(poste -> poste.getStand().isPremium())
                .groupBy(PosteAffectation::getStand, poste -> poste.getCreneau().getId(), ConstraintCollectors.count())
                .map((stand, creneauId, sieges) -> new Equipage(stand, sieges.intValue()))
                .groupBy(Equipage::stand, ConstraintCollectors.max(Equipage::sieges))
                .map((stand, sieges) -> new Equipage(stand, sieges));
    }

    /** A premium stand and the seats of its busiest créneau. */
    private record Equipage(Stand stand, int sieges) {}

    /** Minors and adults on one line, and how many of its seats are still ahead. */
    private record Ages(long mineurs, long majeurs, long ahead) {}

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
        return ConstraintToggleSupport.actif(
                        constraintFactory.forEach(PosteAffectation.class), "eviterChangementEmplacementEloigne")
                .filter(poste -> poste.getStand() != null
                        && poste.getStand().getEmplacement() != null
                        && poste.getCreneau() != null
                        && poste.getCreneau().getHeureFin() != null)
                .join(
                        PosteAffectation.class,
                        Joiners.equal(PosteAffectation::getAnimateur),
                        Joiners.equal(poste -> poste.getCreneau().getJour()),
                        Joiners.equal(
                                poste -> poste.getCreneau().getHeureFin(),
                                poste -> poste.getCreneau().getHeureDebut()))
                .filter((precedent, suivant) -> PastSeats.reproachable(precedent, suivant)
                        && !precedent.getStand().equals(suivant.getStand())
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
        return ConstraintToggleSupport.actif(
                        constraintFactory.forEach(PosteAffectation.class), "limiterEmplacementsParJour")
                .filter(poste -> poste.getAnimateur() != null
                        && poste.getStand() != null
                        && poste.getStand().getEmplacement() != null
                        && poste.getCreneau() != null)
                // The zones already crossed count; the day is charged only
                // while one of its seats is still ahead of now (ADR 0044),
                // folded next to the set.
                .groupBy(
                        PosteAffectation::getAnimateur,
                        poste -> poste.getCreneau().getJour(),
                        PastSeats.withAhead(ConstraintCollectors.toSet(
                                poste -> poste.getStand().getEmplacement())))
                .join(ParametresQualite.class)
                .filter((animateur, jour, emplacements, parametres) -> emplacements.ahead() > 0
                        && emplacements.value().size() > parametres.maxEmplacementsDistinctsParJour())
                .penalize(
                        HardMediumSoftScore.ONE_MEDIUM,
                        (animateur, jour, emplacements, parametres) ->
                                emplacements.value().size() - parametres.maxEmplacementsDistinctsParJour())
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
        return ConstraintToggleSupport.actif(
                        constraintFactory.forEach(PosteAffectation.class), "eviterEnchainementStandsEpuisants")
                .filter(poste -> poste.getStand() != null
                        && poste.getStand().getNiveauEffort() == NiveauEffort.EPUISANT
                        && poste.getCreneau() != null
                        && poste.getCreneau().getHeureFin() != null)
                .join(
                        PosteAffectation.class,
                        Joiners.equal(PosteAffectation::getAnimateur),
                        Joiners.equal(poste -> poste.getCreneau().getJour()),
                        Joiners.equal(
                                poste -> poste.getCreneau().getHeureFin(),
                                poste -> poste.getCreneau().getHeureDebut()))
                .filter((precedent, suivant) -> PastSeats.reproachable(precedent, suivant)
                        && suivant.getStand() != null
                        && suivant.getStand().getNiveauEffort() == NiveauEffort.EPUISANT)
                .penalize(HardMediumSoftScore.ONE_MEDIUM)
                .asConstraint("eviterEnchainementStandsEpuisants");
    }

    /**
     * Closing one night and opening the next morning (issue #78). When the day's
     * work ends late — at or after {@link ParametresQualite#heureServiceTardif()}
     * — and the next day's work starts early — at or before
     * {@link ParametresQualite#heureServiceMatinal()} — every minute of rest
     * missing from {@link ParametresQualite#reposSouhaiteApresServiceTardifMinutes()}
     * is penalised.
     *
     * <p><b>Not a second legal floor.</b> The minutes below the daily rest the
     * Code du travail owes this animateur are
     * {@link LegalConstraints#reposQuotidienMinimal}'s, hard, and are
     * deliberately <b>not</b> counted again here: the rest entering the
     * subtraction is {@code max(gap, legal floor)}, so this rule only ever
     * prices the stretch between what the law demands and what the organiser
     * would prefer. Two consequences worth stating. A plan already illegal is
     * not penalised twice for the same minutes; and where the legal floor is
     * already at or above the wished rest — a minor owed 12 h, an under-16 owed
     * 14 h — this rule is silent, because there is nothing left for it to ask.
     * That is the whole reason it is MEDIUM and lives under « Qualité
     * d'organisation »: it expresses a preference, and the law is elsewhere,
     * held hard.</p>
     *
     * <p><b>One night, one penalty</b>, which is why this joins {@link Journee}
     * tuples rather than postes. A night's rest is a single quantity — the last
     * end of day J to the first start of day J+1 — and a pairwise join bills it
     * once per (late poste, early poste) couple: an animateur restarting at
     * 08:00 and again at 10:00 paid twice for one night, and dropping the 10:00
     * seat halved the penalty without giving them a minute more sleep. The
     * gradient the solver descends has to be the deficit itself, so the day is
     * aggregated first: {@code max(fin)} on one side, {@code min(début)} on the
     * other, and the pair of days joined on the adjacency key. A split closing
     * is covered by the same move — only the last vacation of the evening
     * decides whether the day closed late.</p>
     *
     * <p>Both ends are compared as <b>instants anchored on the day's own
     * date</b>, not as clock times: a closing shift running 20:00 → 00:30 ends
     * at 00:30 on the <i>next</i> calendar day, and reading its
     * {@code LocalTime} alone would score it earlier than the morning it
     * actually pushed into. The rule exists for exactly those shifts, so
     * reading them wrong would have made it inert on its own subject.</p>
     *
     * <p>Adjacency is {@code jour + 1}, the key of
     * {@link LegalConstraints#reposQuotidienMinimal}: {@code Creneau.assignerJours}
     * numbers days from the earliest date, so consecutive numbers are
     * consecutive dates even across a day nobody opened.</p>
     *
     * <p>Tension worth knowing, and left to the weights rather than resolved in
     * code: on a stand that opens and closes every day,
     * {@link #eviterRoulementStandsPremium} pushes towards keeping the same
     * heads and this rule pushes towards rotating them. Both are MEDIUM and
     * both are {@code dosable()}, so an edition arbitrates between them through
     * {@code ponderation_contrainte} — see {@code docs/contraintes.md}.</p>
     */
    private Constraint eviterFermeturePuisOuverture(ConstraintFactory constraintFactory) {
        return ConstraintToggleSupport.actif(
                        journees(constraintFactory)
                                .join(
                                        journees(constraintFactory),
                                        Joiners.equal(Journee::animateur),
                                        Joiners.equal(veille -> veille.jour() + 1, Journee::jour)),
                        "eviterFermeturePuisOuverture")
                .join(ParametresQualite.class)
                // The evening already worked counts against the morning still
                // ahead (ADR 0044); two past days are history.
                .filter((veille, lendemain, parametres) -> parametres.penaliseFermeturePuisOuverture()
                        && (veille.reproachable() || lendemain.reproachable())
                        && fermetureTardive(veille, parametres)
                        && ouvertureMatinale(lendemain, parametres)
                        && reposManquant(veille, lendemain, parametres) > 0)
                .penalize(HardMediumSoftScore.ONE_MEDIUM, QualiteConstraints::reposManquant)
                .asConstraint("eviterFermeturePuisOuverture");
    }

    /**
     * One tuple per (animateur, day worked): when that day's work starts, and
     * when it ends. Both are instants, so a vacation running past midnight ends
     * on the following date — see {@link LegalConstraints#fin}.
     */
    private static UniConstraintStream<Journee> journees(ConstraintFactory constraintFactory) {
        return constraintFactory
                .forEach(PosteAffectation.class)
                .filter(poste -> poste.getAnimateur() != null && LegalConstraints.horaireConnu(poste))
                .groupBy(
                        PosteAffectation::getAnimateur,
                        poste -> poste.getCreneau().getJour(),
                        ConstraintCollectors.compose(
                                ConstraintCollectors.min(LegalConstraints::debut),
                                ConstraintCollectors.max(LegalConstraints::fin),
                                PastSeats.ahead(),
                                Bornes::new))
                .map((animateur, jour, bornes) ->
                        new Journee(animateur, jour, bornes.debut(), bornes.fin(), bornes.ahead() > 0));
    }

    /** The two ends of a day's work, and how many of its seats are still ahead of now. */
    private record Bornes(LocalDateTime debut, LocalDateTime fin, long ahead) {}

    /**
     * An animateur's working day, folded to its two ends.
     *
     * @param debut        first instant worked. Its date is the day's own: every
     *                     créneau sharing a {@code jour} shares a date, and a vacation
     *                     always starts on that date even when it ends after midnight
     * @param reproachable whether one of the day's seats is still ahead of
     *                     now (ADR 0044) — a night between two days entirely
     *                     worked is history
     */
    private record Journee(
            Animateur animateur, int jour, LocalDateTime debut, LocalDateTime fin, boolean reproachable) {

        LocalDate date() {
            return debut.toLocalDate();
        }
    }

    /** True when the day's work ends at or after the late hour of that day. */
    private static boolean fermetureTardive(Journee journee, ParametresQualite parametres) {
        return !journee.fin().isBefore(LocalDateTime.of(journee.date(), parametres.heureServiceTardif()));
    }

    /** True when the day's work starts at or before the early hour of that day. */
    private static boolean ouvertureMatinale(Journee journee, ParametresQualite parametres) {
        return !journee.debut().isAfter(LocalDateTime.of(journee.date(), parametres.heureServiceMatinal()));
    }

    /**
     * Minutes missing to the wished rest, counted from the legal floor upwards:
     * {@code souhaite - max(gap, plancher legal)}, never negative. See the
     * constraint's javadoc for why the minutes under the floor belong to
     * {@code reposQuotidienMinimal} alone.
     */
    private static int reposManquant(Journee veille, Journee lendemain, ParametresQualite parametres) {
        long gap = Duration.between(veille.fin(), lendemain.debut()).toMinutes();
        int plancherLegal = LegalConstraints.reposQuotidienMinimal(veille.animateur(), veille.date());
        long reposCompte = Math.max(gap, plancherLegal);
        return (int) Math.max(0, parametres.reposSouhaiteApresServiceTardifMinutes() - reposCompte);
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
        return ConstraintToggleSupport.actif(
                        constraintFactory.forEach(PosteAffectation.class), "appreciationIncompatible")
                .filter(poste -> poste.getAnimateur() != null
                        && PastSeats.reproachable(poste)
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
        return ConstraintToggleSupport.actif(constraintFactory.forEach(PosteAffectation.class), "souhaitsIncompatibles")
                .filter(poste -> poste.getAnimateur() != null
                        && PastSeats.reproachable(poste)
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
     * An animateur spread across too many distinct typologies of jeu over the
     * whole planning is penalised, proportionally to how far past
     * {@link ParametresQualite#typologiesDistinctesMax()} they are — the same
     * gradient logic as {@link #equilibrerCharge}.
     *
     * <p><b>The scope is the edition, not the day.</b> The {@code groupBy} below
     * carries the animateur and nothing else — no date key, unlike
     * {@code maxJoursConsecutifsTravailles} or {@code pauseSurPosteSansRelais}
     * — so two typologies held on one afternoon and two held a week apart cost
     * exactly the same. That is the rule the organisers asked for: what is
     * being limited is how many different games one person has to learn, and
     * learning them on separate days makes it no easier.</p>
     */
    private Constraint limiterTypologiesDistinctesParAnimateur(ConstraintFactory constraintFactory) {
        return ConstraintToggleSupport.actif(
                        constraintFactory.forEach(PosteAffectation.class), "limiterTypologiesDistinctesParAnimateur")
                // A ninja is versatile by definition: spreading them across many
                // typologies is what they are there for, so the cap doesn't apply.
                .filter(poste -> poste.getAnimateur() != null
                        && poste.getStand() != null
                        && !poste.getAnimateur().isNinja())
                .flatten(QualiteConstraints::likedTypologiesOfPoste)
                // The games already learnt count; the animateur is charged only
                // while one of their seats is still ahead of now (ADR 0044),
                // folded next to the set.
                .groupBy(
                        (poste, typologie) -> poste.getAnimateur(),
                        PastSeats.withAhead(ConstraintCollectors.toSet((poste, typologie) -> typologie)))
                .join(ParametresQualite.class)
                .filter((animateur, typologies, parametres) ->
                        typologies.ahead() > 0 && typologies.value().size() > parametres.typologiesDistinctesMax())
                .penalize(
                        HardMediumSoftScore.ONE_MEDIUM,
                        (animateur, typologies, parametres) ->
                                typologies.value().size() - parametres.typologiesDistinctesMax())
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
    /**
     * A break declared taken on the post needs somebody to hold the post.
     *
     * <p>Under {@code pauseSurPoste} the six-hour rule of art. L3121-16
     * ({@code travailContinuMax*}) goes quiet: the twenty minutes are taken by
     * relay, a colleague on the same stand covering while the person steps
     * out. Nothing checked that the colleague exists. On the 2026 edition, a
     * plan at zero hard carried 18 breaks due on single-seat stands with nobody
     * else there — seven-hour stretches made of a meal-relief seat followed by
     * a full afternoon alone. The Pauses screen and the Problèmes page showed
     * them after the fact; the solver never avoided them.</p>
     *
     * <p>One point per break due with no relay at its latest start: the seat
     * held then, on that stand, has no other animateur covering the whole
     * break. The breaks come from {@link PauseSurPoste}, which the Pauses
     * screen reads too, so the two never disagree on what is due. Quiet when
     * the break is not declared on the post: the legal rule then requires a
     * real hole, and judges it.</p>
     *
     * <p><b>Hard, not dosed.</b> It was a medium rule, and the cheapest answer
     * to it — give the relief seat to somebody else — is one the score used to
     * find often enough. But a relay that does not exist is not a comfort lost:
     * without somebody to hold the stand, the person cannot leave it, so the
     * break is still travail effectif (art. L3121-1, L3121-2) and the twenty
     * minutes of art. L3121-16 — thirty, and 4 h 30, for a minor under art.
     * L3162-3, which is d'ordre public — are simply not given. Under
     * {@code pauseSurPoste} this rule is the only thing left checking them:
     * {@code travailContinuMax*} goes quiet, and {@code dailyCap} /
     * {@code weeklyCap} deduct the break from the caps. Dosing it would mean
     * pricing the deduction of a break nobody took. See
     * {@code docs/contraintes.md}, « La pause sur le poste demande un
     * relais ».</p>
     */
    private Constraint pauseSurPosteSansRelais(ConstraintFactory constraintFactory) {
        return ConstraintToggleSupport.actif(
                        constraintFactory.forEach(PosteAffectation.class), "pauseSurPosteSansRelais")
                .filter(poste -> poste.getAnimateur() != null
                        && poste.getCreneau() != null
                        && poste.getCreneau().getDate() != null
                        && poste.getStand() != null
                        && poste.heureDebutEffectif() != null)
                .groupBy(
                        PosteAffectation::getAnimateur,
                        poste -> poste.getCreneau().getDate(),
                        ConstraintCollectors.toList())
                .join(ParametresLegaux.class)
                // A day entirely worked owes nobody a relay any more (ADR 0044),
                // and computing its breaks would be work for nothing.
                .filter((animateur, date, postes, parametres) ->
                        parametres.isPauseSurPoste() && PastSeats.reproachable(postes))
                .map((animateur, date, postes, parametres) -> PauseSurPoste.dues(postes, parametres))
                .flattenLast(dues -> dues)
                // And the break itself must still be ahead. The day-level guard
                // above is not enough: a day that already worked its morning and
                // still holds an evening seat would have its morning's missing
                // relay charged, on a stretch whose every seat is pinned — an
                // écart dur no move can repair, so no re-solve started mid-event
                // could ever reach zero again. The seat a relay would have to
                // cover is the one that decides.
                .filter(due -> PastSeats.reproachable(due.tenu()))
                .ifNotExists(
                        PosteAffectation.class,
                        Joiners.equal(
                                due -> due.stand().getId(),
                                poste -> poste.getStand() == null
                                        ? null
                                        : poste.getStand().getId()),
                        Joiners.filtering(PauseSurPoste::relayableBy))
                .penalize(HardMediumSoftScore.ONE_HARD)
                .asConstraint("pauseSurPosteSansRelais");
    }

    private Constraint maxJoursConsecutifsTravailles(ConstraintFactory constraintFactory) {
        return sequencesTropLongues(ConstraintToggleSupport.actif(
                        constraintFactory.forEach(PosteAffectation.class), "maxJoursConsecutifsTravailles"))
                .penalize(
                        HardMediumSoftScore.ONE_MEDIUM,
                        (animateur, jours) -> longestReproachableRun(jours) - JOURS_CONSECUTIFS_TRAVAILLES_MAX)
                .asConstraint("maxJoursConsecutifsTravailles");
    }

    /**
     * The same six-day ceiling, held <b>hard</b> — off unless an edition asks
     * for it.
     *
     * <p>Two rules rather than a weight, because a weight never changes a
     * level: {@code SolverConfiguration.constraintWeightOverrides} maps onto
     * the level the catalogue declares, and {@code ConstraintToggle} carries
     * only {@code actif}. So « the six days, but blocking » can only be a
     * second constraint, switched on from the Contraintes screen, from
     * {@code activer_contrainte}, or from a scenario's
     * {@code contraintes.activees}.</p>
     *
     * <p>Why it is not in « Légal (temps de travail) »: no article of the Code
     * du travail founds a rolling six-day count. L3132-1 is read over the civil
     * week (L3121-35), and the Cour de cassation has held that the weekly rest
     * need not fall at the latest after six consecutive days (Cass. soc.
     * 13 nov. 2025, n° 24-10.733; same reading CJUE C-306/16). Filing it as a
     * legal rule would make the disabling confirmation claim a plan breaks the
     * law when it does not. It is the organiser's policy, held at whatever
     * level the organiser chose — see {@code docs/contraintes.md}, « Le niveau
     * de la règle des jours d'affilée ».</p>
     *
     * <p>Both forms may be on at once: the hard one then blocks past six days
     * and the medium one keeps costing on top, which is a legitimate way of
     * saying « never more than six, and prefer fewer ». The comparison bench
     * ran them the other way round — one at a time.</p>
     */
    private Constraint maxJoursConsecutifsTravaillesDur(ConstraintFactory constraintFactory) {
        return sequencesTropLongues(ConstraintToggleSupport.actif(
                        constraintFactory.forEach(PosteAffectation.class), "maxJoursConsecutifsTravaillesDur"))
                .penalize(
                        HardMediumSoftScore.ONE_HARD,
                        (animateur, jours) -> longestReproachableRun(jours) - JOURS_CONSECUTIFS_TRAVAILLES_MAX)
                .asConstraint("maxJoursConsecutifsTravaillesDur");
    }

    /**
     * Animateurs whose longest run of consecutive worked days exceeds
     * {@link #JOURS_CONSECUTIFS_TRAVAILLES_MAX}, with the run's length to hand
     * — the body {@link #maxJoursConsecutifsTravailles} and
     * {@link #maxJoursConsecutifsTravaillesDur} share, so the two forms can
     * never disagree on what « six days in a row » counts. The toggle stays at
     * each caller, with its own name spelled out, as every other rule of this
     * package does: absent, the hard one is off (it is in
     * {@code ConstraintCatalog.DESACTIVEES_PAR_DEFAUT}) and the medium one on.
     */
    private static BiConstraintStream<Animateur, Map<Integer, Boolean>> sequencesTropLongues(
            UniConstraintStream<PosteAffectation> postes) {
        return postes.filter(poste -> poste.getAnimateur() != null && poste.getCreneau() != null)
                // Each worked day, and whether it still holds a seat ahead of
                // now: a run of days entirely worked is history, a run that
                // reaches into tomorrow is charged with its past days counted
                // (ADR 0044).
                .groupBy(
                        PosteAffectation::getAnimateur,
                        ConstraintCollectors.toMap(
                                poste -> poste.getCreneau().getJour(), PastSeats::reproachable, Boolean::logicalOr))
                .filter((animateur, jours) -> longestReproachableRun(jours) > JOURS_CONSECUTIFS_TRAVAILLES_MAX);
    }

    /**
     * Longest run of consecutive day numbers among the keys, counting only the
     * runs in which at least one day is still ahead of now — the map's value.
     */
    private static int longestReproachableRun(Map<Integer, Boolean> jours) {
        List<Integer> tries = jours.keySet().stream().sorted().toList();
        int longest = 0;
        int courante = 0;
        boolean reproachable = false;
        int precedent = Integer.MIN_VALUE;
        for (int jour : tries) {
            if (jour != precedent + 1) {
                courante = 0;
                reproachable = false;
            }
            courante++;
            reproachable |= jours.get(jour);
            if (reproachable) {
                longest = Math.max(longest, courante);
            }
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
