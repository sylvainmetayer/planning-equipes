package dev.sylvain.planning.solver.constraints;

import ai.timefold.solver.core.api.score.stream.ConstraintCollectors;
import ai.timefold.solver.core.api.score.stream.ConstraintFactory;
import ai.timefold.solver.core.api.score.stream.Joiners;
import ai.timefold.solver.core.api.score.stream.bi.BiConstraintStream;
import ai.timefold.solver.core.api.score.stream.quad.QuadConstraintStream;
import ai.timefold.solver.core.api.score.stream.tri.TriConstraintStream;
import ai.timefold.solver.core.api.score.stream.uni.UniConstraintStream;
import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.FenetreRepas;
import dev.sylvain.planning.domain.ParametresLegaux;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * The streams every rule reads the seats through, built <b>once</b> per
 * constraint provider and handed to every family.
 *
 * <p>Bavet, the constraint stream engine, builds one node per stream object
 * and shares a node between the constraints that read the same object. It
 * does not merge two streams that merely look alike: two {@code groupBy} calls
 * with identical mappers are two group nodes, each maintaining its own list of
 * every animateur's day and recomputing it at every move. Written rule by rule,
 * the provider carried eleven such groups of the same seats by animateur and
 * day, and forty-odd copies of the head {@code forEach(PosteAffectation)}
 * filter — the profile of a real edition showed the group nodes and the
 * propagation queues ahead of every rule's own arithmetic.</p>
 *
 * <p>So the head of every rule is one of the streams below, memoised here and
 * reused: the engine then maintains one list per (animateur, day), one per
 * animateur, one per (stand, timeslot), whatever the number of rules reading
 * them. The toggle of a rule ({@link ConstraintToggleSupport#actif}) is laid
 * at the <b>tail</b> of its stream for the same reason: at the head it was a
 * node of its own on every seat, and it made the head unshareable.</p>
 *
 * <p>Each family's {@code define(factory)} builds a {@code SeatStreams} of its
 * own when called alone — the per-constraint unit tests do that — and
 * {@code PlanningConstraintProvider} hands one instance to all of them, which
 * is where the sharing across families happens.</p>
 *
 * <p><b>What the sharing changed, and what it did not.</b> On a plan whose
 * seats all carry a stand, a dated timeslot and its hours — every plan the
 * application builds — the rules count exactly what they counted, and a solve
 * follows the same trajectory move for move (measured, see
 * {@code docs/developpement.md}). Two readings moved on purpose:</p>
 * <ul>
 *   <li>{@code favoriserMixiteDesNiveaux} reads the line through
 *       {@link #stillHeld()}, as {@code standComplexeAvecReferent} and
 *       {@code repartitionMineursParCreneau} already did: the origin of a seat
 *       split on the day (ADR 0066) is no longer counted as present for the
 *       rest of the timeslot. A referent who left at 09:20 is no referent for
 *       the afternoon.</li>
 *   <li>A seat missing its stand, its date or its start hour — data the
 *       application never builds — is left out of every rule reading
 *       {@link #timed()}, where a few rules used to keep it with fewer
 *       guards (the meal break read the effective start, the run of days
 *       needed a timeslot only). Such a seat has no place in time to measure,
 *       and was already left out by the legal caps.</li>
 * </ul>
 */
public final class SeatStreams {

    private final ConstraintFactory factory;

    private UniConstraintStream<PosteAffectation> held;
    private UniConstraintStream<PosteAffectation> timed;
    private UniConstraintStream<PosteAffectation> stillHeld;
    private TriConstraintStream<Animateur, LocalDate, List<PosteAffectation>> days;
    private QuadConstraintStream<Animateur, LocalDate, List<PosteAffectation>, ParametresLegaux> daysWithLegal;
    private QuadConstraintStream<Animateur, LocalDate, List<PosteAffectation>, FenetreRepas> daysWithMeals;
    private BiConstraintStream<Animateur, List<PosteAffectation>> perAnimateur;
    private TriConstraintStream<Stand, Creneau, List<PosteAffectation>> lines;
    private final Map<String, Object> derived = new HashMap<>();

    public SeatStreams(ConstraintFactory factory) {
        this.factory = factory;
    }

    ConstraintFactory factory() {
        return factory;
    }

    /** Every seat somebody holds — Timefold's {@code forEach} leaves the empty ones out. */
    UniConstraintStream<PosteAffectation> held() {
        if (held == null) {
            held = factory.forEach(PosteAffectation.class);
        }
        return held;
    }

    /**
     * The held seats whose place in time is known: a stand, a dated timeslot,
     * a start hour — what every rule measuring hours, days or weeks requires
     * before it can read a seat at all.
     */
    UniConstraintStream<PosteAffectation> timed() {
        if (timed == null) {
            timed = held().filter(SeatStreams::timed);
        }
        return timed;
    }

    static boolean timed(PosteAffectation poste) {
        return poste.getStand() != null && LegalConstraints.horaireConnu(poste);
    }

    /**
     * The held seats still held on the rest of their timeslot: every seat but
     * the origin of a seat split on the day (ADR 0066), which ended where its
     * remainder starts. A rule counting who is present on a stand × timeslot
     * would otherwise count the person who left at 09:20 as there until noon.
     * Every seat of a plan nobody split passes.
     */
    UniConstraintStream<PosteAffectation> stillHeld() {
        if (stillHeld == null) {
            stillHeld = held().ifNotExists(
                            PosteAffectation.class,
                            Joiners.equal(PosteAffectation::getId, PosteAffectation::getSuiteDe));
        }
        return stillHeld;
    }

    /** One tuple per animateur and date worked: the timed seats they hold that day. */
    TriConstraintStream<Animateur, LocalDate, List<PosteAffectation>> days() {
        if (days == null) {
            days = timed().groupBy(
                            PosteAffectation::getAnimateur,
                            poste -> poste.getCreneau().getDate(),
                            ConstraintCollectors.toList());
        }
        return days;
    }

    /** {@link #days()} with the edition's legal parameters alongside — the one fact every legal cap reads. */
    QuadConstraintStream<Animateur, LocalDate, List<PosteAffectation>, ParametresLegaux> daysWithLegal() {
        if (daysWithLegal == null) {
            daysWithLegal = days().join(ParametresLegaux.class);
        }
        return daysWithLegal;
    }

    /** {@link #days()} paired with each meal window that applies to the date. */
    QuadConstraintStream<Animateur, LocalDate, List<PosteAffectation>, FenetreRepas> daysWithMeals() {
        if (daysWithMeals == null) {
            daysWithMeals = days().join(
                            FenetreRepas.class,
                            Joiners.filtering((animateur, date, postes, fenetre) -> fenetre.appliesTo(date)));
        }
        return daysWithMeals;
    }

    /** One tuple per animateur: every timed seat they hold over the whole event. */
    BiConstraintStream<Animateur, List<PosteAffectation>> perAnimateur() {
        if (perAnimateur == null) {
            perAnimateur = timed().groupBy(PosteAffectation::getAnimateur, ConstraintCollectors.toList());
        }
        return perAnimateur;
    }

    /**
     * One tuple per stand and timeslot: the seats still held there
     * ({@link #stillHeld()}) — the line a rule about who is present on a stand
     * at a time reads.
     */
    TriConstraintStream<Stand, Creneau, List<PosteAffectation>> lines() {
        if (lines == null) {
            lines = stillHeld()
                    .filter(poste -> poste.getCreneau() != null)
                    .groupBy(PosteAffectation::getStand, PosteAffectation::getCreneau, ConstraintCollectors.toList());
        }
        return lines;
    }

    /**
     * A stream derived from the ones above by a family, built once under
     * {@code key} and handed to every rule that asks for it — the same sharing
     * as the streams of this class, for a shape only one family knows (the
     * day folded to its two ends, {@code QualiteConstraints.journees}, read by
     * four rules of two families).
     */
    @SuppressWarnings("unchecked")
    <T> T derived(String key, Supplier<T> build) {
        return (T) derived.computeIfAbsent(key, k -> build.get());
    }

    /** The day number of a day's seats — one per date, see {@code Creneau.assignerJours}. */
    static int jour(List<PosteAffectation> jour) {
        return jour.get(0).getCreneau().getJour();
    }
}
