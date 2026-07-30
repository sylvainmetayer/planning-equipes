package dev.sylvain.planning.solver.constraints;

import ai.timefold.solver.core.api.score.buildin.hardmediumsoft.HardMediumSoftScore;
import ai.timefold.solver.core.api.score.stream.Constraint;
import ai.timefold.solver.core.api.score.stream.ConstraintCollectors;
import ai.timefold.solver.core.api.score.stream.ConstraintFactory;
import ai.timefold.solver.core.api.score.stream.Joiners;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.ParametresLegaux;
import dev.sylvain.planning.domain.PosteAffectation;

/**
 * Legal / safety hard constraints. Most are tied to the protection of minors
 * (cahier des charges 4.1 and 4.2): majeur-only stands, mandatory adult
 * supervision, no night work, capped daily working time and minimum daily
 * rest for minors. {@link #dureeHebdomadaireMax} applies to every animateur
 * (all paid, manager or not) and caps weekly working time against the
 * admin-configurable {@link ParametresLegaux}. None of these may ever be
 * violated in a valid plan.
 */
public final class LegalConstraints {

    private static final int DUREE_QUOTIDIENNE_MAX_MINEUR_MINUTES = 8 * 60;

    public Constraint[] define(ConstraintFactory constraintFactory) {
        return new Constraint[] {
                standReserveAuxMajeurs(constraintFactory),
                mineurNecessiteEncadrementMajeur(constraintFactory),
                travailDeNuitInterditPourMineur(constraintFactory),
                dureeQuotidienneMaxMineur(constraintFactory),
                reposQuotidienMineur(constraintFactory),
                dureeHebdomadaireMax(constraintFactory)
        };
    }

    private Constraint standReserveAuxMajeurs(ConstraintFactory constraintFactory) {
        return constraintFactory.forEach(PosteAffectation.class)
                .filter(poste -> poste.getAnimateur() != null
                        && poste.getStand().isReserveMajeurs()
                        && poste.getAnimateur().estMineurLe(poste.getCreneau().getDate()))
                .penalize(HardMediumSoftScore.ONE_HARD)
                .asConstraint("standReserveAuxMajeurs");
    }

    private Constraint mineurNecessiteEncadrementMajeur(ConstraintFactory constraintFactory) {
        return constraintFactory.forEach(PosteAffectation.class)
                .filter(poste -> poste.getAnimateur() != null
                        && poste.getAnimateur().estMineurLe(poste.getCreneau().getDate()))
                .ifNotExists(PosteAffectation.class,
                        Joiners.equal(PosteAffectation::getStand),
                        Joiners.equal(PosteAffectation::getCreneau),
                        Joiners.filtering((posteMineur, autrePoste) -> autrePoste.getAnimateur() != null
                                && autrePoste.getAnimateur().estMajeurLe(autrePoste.getCreneau().getDate())))
                .penalize(HardMediumSoftScore.ONE_HARD)
                .asConstraint("mineurNecessiteEncadrementMajeur");
    }

    private Constraint travailDeNuitInterditPourMineur(ConstraintFactory constraintFactory) {
        return constraintFactory.forEach(PosteAffectation.class)
                .filter(poste -> poste.getAnimateur() != null
                        && poste.getCreneau() != null
                        && poste.getCreneau().chevaucheNuit()
                        && poste.getAnimateur().estMineurLe(poste.getCreneau().getDate()))
                .penalize(HardMediumSoftScore.ONE_HARD)
                .asConstraint("travailDeNuitInterditPourMineur");
    }

    private Constraint dureeQuotidienneMaxMineur(ConstraintFactory constraintFactory) {
        return constraintFactory.forEach(PosteAffectation.class)
                .filter(poste -> poste.getAnimateur() != null
                        && poste.getCreneau() != null
                        && poste.getAnimateur().estMineurLe(poste.getCreneau().getDate()))
                .groupBy(PosteAffectation::getAnimateur,
                        poste -> poste.getCreneau().getJour(),
                        ConstraintCollectors.sum(poste -> poste.getCreneau().getDureeMinutes()))
                .filter((animateur, jour, dureeTotale) -> dureeTotale > DUREE_QUOTIDIENNE_MAX_MINEUR_MINUTES)
                .penalize(HardMediumSoftScore.ONE_HARD,
                        (animateur, jour, dureeTotale) -> dureeTotale - DUREE_QUOTIDIENNE_MAX_MINEUR_MINUTES)
                .asConstraint("dureeQuotidienneMaxMineur");
    }

    private Constraint reposQuotidienMineur(ConstraintFactory constraintFactory) {
        // A minor working a night slot must get at least ~12h of rest, so a night
        // slot on day J forbids an early (before noon) slot on day J+1.
        return constraintFactory.forEachUniquePair(
                PosteAffectation.class,
                Joiners.equal(PosteAffectation::getAnimateur))
                .filter((posteA, posteB) -> posteA.getAnimateur() != null
                        && posteA.getCreneau() != null
                        && posteB.getCreneau() != null
                        && posteA.getAnimateur().estMineurLe(posteA.getCreneau().getDate())
                        && reposInsuffisant(posteA.getCreneau(), posteB.getCreneau()))
                .penalize(HardMediumSoftScore.ONE_HARD)
                .asConstraint("reposQuotidienMineur");
    }

    private Constraint dureeHebdomadaireMax(ConstraintFactory constraintFactory) {
        return constraintFactory.forEach(PosteAffectation.class)
                .filter(poste -> poste.getAnimateur() != null && poste.getCreneau() != null)
                .groupBy(PosteAffectation::getAnimateur,
                        poste -> poste.getCreneau().semaineIso(),
                        ConstraintCollectors.sum(poste -> poste.getCreneau().getDureeMinutes()))
                .join(ParametresLegaux.class)
                .filter((animateur, semaine, dureeTotale, parametres) ->
                        dureeTotale > parametres.getDureeHebdomadaireMaxMinutes())
                .penalize(HardMediumSoftScore.ONE_HARD,
                        (animateur, semaine, dureeTotale, parametres) ->
                                dureeTotale - parametres.getDureeHebdomadaireMaxMinutes())
                .asConstraint("dureeHebdomadaireMax");
    }

    private boolean reposInsuffisant(Creneau a, Creneau b) {
        Creneau soir = a.getJour() <= b.getJour() ? a : b;
        Creneau lendemain = a.getJour() <= b.getJour() ? b : a;
        return lendemain.getJour() == soir.getJour() + 1
                && soir.chevaucheNuit()
                && lendemain.getHeureDebut() != null
                && lendemain.getHeureDebut().isBefore(java.time.LocalTime.NOON);
    }
}
