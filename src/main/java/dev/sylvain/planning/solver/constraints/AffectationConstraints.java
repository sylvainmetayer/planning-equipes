package dev.sylvain.planning.solver.constraints;

import ai.timefold.solver.core.api.score.HardMediumSoftScore;
import ai.timefold.solver.core.api.score.stream.Constraint;
import ai.timefold.solver.core.api.score.stream.ConstraintCollectors;
import ai.timefold.solver.core.api.score.stream.ConstraintFactory;
import ai.timefold.solver.core.api.score.stream.Joiners;
import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.QuotaTypologie;
import java.time.LocalDateTime;

/**
 * Core assignment hard constraints: every mandatory seat must be filled by an
 * available animateur, and nobody can hold two seats whose créneaux overlap in
 * time. These map to the documented assignment rules (effectif, disponibilite).
 *
 * <p>Competence used to be enforced here too ({@code competenceCompatible}),
 * but the business now treats it as an administrator's post-formation
 * appreciation rather than a hard qualification: an animateur without a
 * matching appreciation can still be assigned, just penalised — see
 * {@code QualiteConstraints.appreciationIncompatible}.</p>
 */
public final class AffectationConstraints {

    public Constraint[] define(ConstraintFactory constraintFactory) {
        return new Constraint[] {
            posteDoitEtrePourvu(constraintFactory),
            animateurDisponible(constraintFactory),
            pasDeChevauchementHoraire(constraintFactory),
            plafondCreneauxParTypologie(constraintFactory)
        };
    }

    /**
     * A quota: over the <b>whole edition</b>, one animateur holds no more than
     * {@code n} créneaux on a given typologie of jeu.
     *
     * <p>The case that asked for it is « les hommes jeu, 4 créneaux au
     * maximum » (issue #594). Nothing could express it:
     * {@code limiterTypologiesDistinctesParAnimateur} bounds how many
     * <i>different</i> typologies somebody covers and is a medium;
     * {@code equilibrerCharge} spreads the global load without ever looking at
     * the typologie; and {@code TypeContrainteAdHoc} knows no quota. The
     * work-around was to place forced unavailabilities by hand, one by one, on
     * a combination nobody can enumerate in advance — the cap bears on the
     * whole edition, so it depends on what the solver does everywhere else.</p>
     *
     * <p><b>A poste counts for every typologie its stand proposes</b>, not for
     * the ones its animateur happens to master: somebody holds the game they
     * are sat at whether or not their sheet mentions it. That is the opposite
     * choice from {@code limiterTypologiesDistinctesParAnimateur}, which reads
     * the intersection — it is about what a person has to learn, this is about
     * how much of a game gets served.</p>
     *
     * <p>Scope: the edition, never the day or the week — the same scope as the
     * distinct-typologies rule, and a test pins it. Only the typologies
     * carrying a {@link QuotaTypologie} are joined, so an edition that caps
     * nothing pays nothing.</p>
     */
    private Constraint plafondCreneauxParTypologie(ConstraintFactory constraintFactory) {
        return ConstraintToggleSupport.actif(
                        constraintFactory.forEach(PosteAffectation.class), "plafondCreneauxParTypologie")
                .filter(poste -> poste.getAnimateur() != null && poste.getStand() != null)
                .flatten(poste -> poste.getStand().getTypologiesProposees())
                // Counted, not reproached (ADR 0044): the past seats stay in
                // the count, and the cap is charged only while the animateur
                // still holds a seat of that typologie ahead of now — folded
                // into the group, next to the count.
                .groupBy(
                        (poste, typologie) -> poste.getAnimateur(),
                        (poste, typologie) -> typologie,
                        PastSeats.withAhead(ConstraintCollectors.countBi()))
                .join(
                        QuotaTypologie.class,
                        Joiners.equal((animateur, typologie, tenue) -> typologie, QuotaTypologie::getTypologie))
                .filter((animateur, typologie, tenue, plafond) ->
                        tenue.ahead() > 0 && tenue.value() > plafond.getMaxCreneaux())
                // Reshaped before penalising so the écart reads as a sentence:
                // who, which typologie and its cap, how many they hold.
                .map(
                        (animateur, typologie, tenue, plafond) -> animateur,
                        (animateur, typologie, tenue, plafond) -> plafond,
                        (animateur, typologie, tenue, plafond) -> tenue.value())
                .penalize(HardMediumSoftScore.ONE_HARD, (animateur, plafond, tenus) -> tenus - plafond.getMaxCreneaux())
                .asConstraint("plafondCreneauxParTypologie");
    }

    private Constraint posteDoitEtrePourvu(ConstraintFactory constraintFactory) {
        // forEach() excludes entities with a null planning variable value, so this
        // constraint (which specifically targets unassigned postes) must use
        // forEachIncludingUnassigned() to actually see them. A past hole is
        // not charged (ADR 0044): nobody can be seated yesterday. An optional
        // seat is not charged either (issue #505, ADR 0048): it was generated
        // above what the window declares, so nobody is missing on it — that is
        // the whole difference between a renfort and a seat.
        return ConstraintToggleSupport.actif(
                        constraintFactory.forEachIncludingUnassigned(PosteAffectation.class), "posteDoitEtrePourvu")
                .filter(poste -> poste.getAnimateur() == null && !poste.isOptionnel() && PastSeats.reproachable(poste))
                .penalize(HardMediumSoftScore.ONE_HARD)
                .asConstraint("posteDoitEtrePourvu");
    }

    private Constraint animateurDisponible(ConstraintFactory constraintFactory) {
        return ConstraintToggleSupport.actif(constraintFactory.forEach(PosteAffectation.class), "animateurDisponible")
                .filter(poste -> {
                    Animateur animateur = poste.getAnimateur();
                    return animateur != null
                            && PastSeats.reproachable(poste)
                            && poste.getCreneau() != null
                            && poste.getCreneau().getDate() != null
                            && animateur.isIndisponibleOn(poste.getCreneau().getDate());
                })
                .penalize(HardMediumSoftScore.ONE_HARD, poste -> ExclusionEligibilite.FORFAIT)
                .asConstraint("animateurDisponible");
    }

    /**
     * An animateur never holds two postes whose créneaux overlap in time.
     *
     * <p>Not a rule of law, but the <b>technical prerequisite of every legal
     * duration rule</b>: the former {@code pasDeDoubleAffectationSurMemeCreneau}
     * compared créneau <i>identity</i>, so two distinct but overlapping
     * créneaux (10:00-14:00 and 12:00-16:00 — the normal case as soon as
     * overlapping vacations or hand-typed hours
     * exist) could both be assigned to the same person. The daily and weekly
     * caps summed their minutes correctly, but the plan was physically
     * unworkable and every rest rule added afterwards would have inherited the
     * same blind spot. With découpage automatic this is no longer
     * hypothetical: one animateur legitimately holds several postes the same
     * day on different, non-overlapping vacations (even on different stands),
     * so only a genuine time clash may be rejected.</p>
     *
     * <p>Same-créneau double booking is just the degenerate case of an
     * overlap, so this strictly supersedes the old rule.</p>
     *
     * <p>Written as {@code forEach().filter().join(lessThan(id))} rather than
     * {@code forEachUniquePair}: it yields exactly the same "each pair once"
     * semantics while letting the null guards run <i>before</i> the joiners
     * dereference {@code creneau}. {@code Joiners.overlapping} is
     * interval-indexed, not a pairwise scan with a Java predicate.</p>
     */
    private Constraint pasDeChevauchementHoraire(ConstraintFactory constraintFactory) {
        return ConstraintToggleSupport.actif(
                        constraintFactory.forEach(PosteAffectation.class), "pasDeChevauchementHoraire")
                .filter(AffectationConstraints::creneauHoraireConnu)
                .join(
                        PosteAffectation.class,
                        Joiners.equal(PosteAffectation::getAnimateur),
                        Joiners.lessThan(PosteAffectation::getId),
                        Joiners.overlapping(AffectationConstraints::debutCreneau, AffectationConstraints::finCreneau))
                .filter((posteA, posteB) -> creneauHoraireConnu(posteB) && PastSeats.reproachable(posteA, posteB))
                .penalize(HardMediumSoftScore.ONE_HARD)
                .asConstraint("pasDeChevauchementHoraire");
    }

    private static boolean creneauHoraireConnu(PosteAffectation poste) {
        return poste.getCreneau() != null
                && poste.getCreneau().getDate() != null
                && poste.getCreneau().getHeureDebut() != null;
    }

    /**
     * Start instant of the time this poste actually covers — narrowed by
     * {@link PosteAffectation#getHeureDebutEffective()} when the stand is only
     * partially closed on this créneau, so two postes whose effective windows
     * don't actually overlap (e.g. different stands, a closure sitting between
     * them) are correctly not flagged here — see
     * {@code Creneau#segmentsOuvertsMinutes}.
     */
    private static LocalDateTime debutCreneau(PosteAffectation poste) {
        return LocalDateTime.of(poste.getCreneau().getDate(), poste.heureDebutEffectif());
    }

    /**
     * End instant of the effective window, derived from
     * {@link PosteAffectation#getDureeEffectiveMinutes()} so a window crossing
     * midnight (20:00 → 00:00) ends the next calendar day rather than before
     * it started.
     */
    private static LocalDateTime finCreneau(PosteAffectation poste) {
        return debutCreneau(poste).plusMinutes(poste.getDureeEffectiveMinutes());
    }
}
