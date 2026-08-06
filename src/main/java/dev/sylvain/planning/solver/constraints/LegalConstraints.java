package dev.sylvain.planning.solver.constraints;

import java.time.LocalTime;

import ai.timefold.solver.core.api.score.buildin.hardmediumsoft.HardMediumSoftScore;
import ai.timefold.solver.core.api.score.stream.Constraint;
import ai.timefold.solver.core.api.score.stream.ConstraintCollectors;
import ai.timefold.solver.core.api.score.stream.ConstraintFactory;
import ai.timefold.solver.core.api.score.stream.Joiners;
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
        return ConstraintToggleSupport.actif(constraintFactory.forEach(PosteAffectation.class), "standReserveAuxMajeurs")
                .filter(poste -> poste.getAnimateur() != null
                        && poste.getStand().isReserveMajeurs()
                        && poste.getAnimateur().estMineurLe(poste.getCreneau().getDate()))
                .penalize(HardMediumSoftScore.ONE_HARD)
                .asConstraint("standReserveAuxMajeurs");
    }

    private Constraint mineurNecessiteEncadrementMajeur(ConstraintFactory constraintFactory) {
        return ConstraintToggleSupport.actif(constraintFactory.forEach(PosteAffectation.class),
                "mineurNecessiteEncadrementMajeur")
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
        return ConstraintToggleSupport.actif(constraintFactory.forEach(PosteAffectation.class),
                "travailDeNuitInterditPourMineur")
                .filter(poste -> poste.getAnimateur() != null
                        && poste.getCreneau() != null
                        && poste.getCreneau().chevaucheNuit()
                        && poste.getAnimateur().estMineurLe(poste.getCreneau().getDate()))
                .penalize(HardMediumSoftScore.ONE_HARD)
                .asConstraint("travailDeNuitInterditPourMineur");
    }

    private Constraint dureeQuotidienneMaxMineur(ConstraintFactory constraintFactory) {
        return ConstraintToggleSupport.actif(constraintFactory.forEach(PosteAffectation.class),
                "dureeQuotidienneMaxMineur")
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

    /**
     * A minor working a night slot must get at least ~12h of rest, so a night
     * slot on day J forbids an early (before noon) slot on day J+1.
     *
     * <p>The night slot is selected first and the next day is reached through an
     * indexed joiner, rather than pairing every poste an animateur holds and
     * testing the pair. The previous formulation built ~13 500 pair tuples on
     * {@code scenario-complet.yaml} (150 animateurs × ~14 postes each) and
     * discarded virtually all of them; here the left side is restricted to the
     * handful of night slots held by minors before any pairing happens. Each
     * qualifying (night, next-morning) pair still yields exactly one match,
     * since the day joiner only ever generates it in that order.</p>
     */
    private Constraint reposQuotidienMineur(ConstraintFactory constraintFactory) {
        return ConstraintToggleSupport.actif(constraintFactory.forEach(PosteAffectation.class), "reposQuotidienMineur")
                .filter(poste -> poste.getCreneau() != null
                        && poste.getCreneau().chevaucheNuit()
                        && poste.getAnimateur().estMineurLe(poste.getCreneau().getDate()))
                .join(PosteAffectation.class,
                        Joiners.equal(PosteAffectation::getAnimateur),
                        Joiners.equal(soir -> soir.getCreneau().getJour() + 1,
                                lendemain -> lendemain.getCreneau().getJour()))
                .filter((soir, lendemain) -> lendemain.getCreneau().getHeureDebut() != null
                        && lendemain.getCreneau().getHeureDebut().isBefore(LocalTime.NOON))
                .penalize(HardMediumSoftScore.ONE_HARD)
                .asConstraint("reposQuotidienMineur");
    }

    private Constraint dureeHebdomadaireMax(ConstraintFactory constraintFactory) {
        return ConstraintToggleSupport.actif(constraintFactory.forEach(PosteAffectation.class), "dureeHebdomadaireMax")
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
}
