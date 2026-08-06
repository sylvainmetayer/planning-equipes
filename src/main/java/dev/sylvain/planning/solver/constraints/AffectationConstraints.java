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
 * available, competent animateur, and nobody can hold two seats whose créneaux
 * overlap in time. These map to the documented assignment rules (effectif,
 * disponibilite, competence).
 */
public final class AffectationConstraints {

    public Constraint[] define(ConstraintFactory constraintFactory) {
        return new Constraint[] {
                posteDoitEtrePourvu(constraintFactory),
                animateurDisponible(constraintFactory),
                competenceCompatible(constraintFactory),
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

    private Constraint competenceCompatible(ConstraintFactory constraintFactory) {
        return ConstraintToggleSupport.actif(constraintFactory.forEach(PosteAffectation.class), "competenceCompatible")
                .filter(poste -> poste.getAnimateur() != null
                        && !poste.getAnimateur().possedeCompetencePour(poste.getStand()))
                .penalize(HardMediumSoftScore.ONE_HARD)
                .asConstraint("competenceCompatible");
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
     * same blind spot.</p>
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

    private static LocalDateTime debutCreneau(PosteAffectation poste) {
        return LocalDateTime.of(poste.getCreneau().getDate(), poste.getCreneau().getHeureDebut());
    }

    /**
     * End instant of the slot, derived from {@code getDureeMinutes()} so a
     * créneau crossing midnight (20:00 → 00:00) ends the next calendar day
     * rather than before it started.
     */
    private static LocalDateTime finCreneau(PosteAffectation poste) {
        return debutCreneau(poste).plusMinutes(poste.getCreneau().getDureeMinutes());
    }
}
