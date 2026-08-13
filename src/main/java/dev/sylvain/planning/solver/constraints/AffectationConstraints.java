package dev.sylvain.planning.solver.constraints;

import java.time.LocalDateTime;

import ai.timefold.solver.core.api.score.buildin.hardmediumsoft.HardMediumSoftScore;
import ai.timefold.solver.core.api.score.stream.Constraint;
import ai.timefold.solver.core.api.score.stream.ConstraintFactory;
import ai.timefold.solver.core.api.score.stream.Joiners;
import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.PosteAffectation;

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
                pasDeChevauchementHoraire(constraintFactory)
        };
    }

    private Constraint posteDoitEtrePourvu(ConstraintFactory constraintFactory) {
        // forEach() excludes entities with a null planning variable value, so this
        // constraint (which specifically targets unassigned postes) must use
        // forEachIncludingUnassigned() to actually see them.
        return ConstraintToggleSupport.actif(
                constraintFactory.forEachIncludingUnassigned(PosteAffectation.class), "posteDoitEtrePourvu")
                .filter(poste -> poste.getAnimateur() == null)
                .penalize(HardMediumSoftScore.ONE_HARD)
                .asConstraint("posteDoitEtrePourvu");
    }

    private Constraint animateurDisponible(ConstraintFactory constraintFactory) {
        return ConstraintToggleSupport.actif(constraintFactory.forEach(PosteAffectation.class), "animateurDisponible")
                .filter(poste -> {
                    Animateur animateur = poste.getAnimateur();
                    return animateur != null
                            && poste.getCreneau() != null
                            && poste.getCreneau().getDate() != null
                            && animateur.estIndisponibleLe(poste.getCreneau().getDate());
                })
                .penalize(HardMediumSoftScore.ONE_HARD)
                .asConstraint("animateurDisponible");
    }

    /**
     * An animateur never holds two postes whose créneaux overlap in time.
     *
     * <p>Not a rule of law, but the <b>technical prerequisite of every legal
     * duration rule</b>: the former {@code pasDeDoubleAffectationSurMemeCreneau}
     * compared créneau <i>identity</i>, so two distinct but overlapping
     * créneaux (10:00-14:00 and 12:00-16:00 — the normal case as soon as
     * overlapping vacations, several {@code GroupeCreneau}, or hand-typed hours
     * exist) could both be assigned to the same person. The daily and weekly
     * caps summed their minutes correctly, but the plan was physically
     * unworkable and every rest rule added afterwards would have inherited the
     * same blind spot. With découpage automatique this is no longer
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
        return ConstraintToggleSupport.actif(constraintFactory.forEach(PosteAffectation.class),
                "pasDeChevauchementHoraire")
                .filter(AffectationConstraints::creneauHoraireConnu)
                .join(PosteAffectation.class,
                        Joiners.equal(PosteAffectation::getAnimateur),
                        Joiners.lessThan(PosteAffectation::getId),
                        Joiners.overlapping(AffectationConstraints::debutCreneau, AffectationConstraints::finCreneau))
                .filter((posteA, posteB) -> creneauHoraireConnu(posteB))
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
