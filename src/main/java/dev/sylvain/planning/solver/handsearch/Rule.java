package dev.sylvain.planning.solver.handsearch;

import java.util.Locale;

/**
 * The rules {@link IncrementalScore} computes, one per constraint of
 * {@code ConstraintCatalog} and under its name: {@code POSTE_DOIT_ETRE_POURVU}
 * is {@code posteDoitEtrePourvu}.
 *
 * <p>The name is the whole link between the two engines: a constraint added
 * to the catalogue without its constant here — or a constant here that no
 * line of the score reads — fails {@code HandSearchCoverageStructuralTest},
 * and {@code HandSearchDifferentialTest} then holds the two scores equal rule
 * by rule (ADR 0087).</p>
 */
public enum Rule {
    POSTE_DOIT_ETRE_POURVU,
    ANIMATEUR_DISPONIBLE,
    PAS_DE_CHEVAUCHEMENT_HORAIRE,
    PLAFOND_CRENEAUX_PAR_TYPOLOGIE,
    INDISPONIBILITE_FORCEE,
    INCOMPATIBILITE_AD_HOC,
    AFFECTATION_FORCEE,
    AFFINITE_AD_HOC,
    ARRIVEE_GROUPEE,
    ANIMATEUR_VERROUILLE_CRENEAU_FIGE,
    STAND_RESERVE_AUX_MAJEURS,
    MINEUR_NECESSITE_ENCADREMENT_MAJEUR,
    TRAVAIL_DE_NUIT_INTERDIT_POUR_MINEUR,
    DUREE_QUOTIDIENNE_MAX,
    DUREE_HEBDOMADAIRE_MAX,
    DUREE_HEBDOMADAIRE_MAX_DEUX_SEMAINES,
    REPOS_QUOTIDIEN_MINIMAL,
    TRAVAIL_CONTINU_MAX,
    MAX_JOURS_TRAVAILLES_PAR_SEMAINE,
    REPOS_HEBDOMADAIRE_MINIMAL,
    TRAVAIL_INTERDIT_JOUR_FERIE_MINEUR,
    COUPURE_REPAS_OBLIGATOIRE,
    COUPURE_REPAS_PLACEMENT_PREFERE,
    STAND_COMPLEXE_AVEC_REFERENT,
    EQUILIBRER_CHARGE,
    REPARTITION_MINEURS_PAR_CRENEAU,
    EXPERIENCE_REQUISE_POUR_STANDS_PREMIUM,
    EVITER_ROULEMENT_STANDS_PREMIUM,
    EVITER_CHANGEMENT_EMPLACEMENT_ELOIGNE,
    TRAJET_INSUFFISANT_ENTRE_POSTES,
    LIMITER_EMPLACEMENTS_PAR_JOUR,
    EVITER_ENCHAINEMENT_STANDS_EPUISANTS,
    EVITER_FERMETURE_PUIS_OUVERTURE,
    APPRECIATION_INCOMPATIBLE,
    SOUHAITS_INCOMPATIBLES,
    LIMITER_TYPOLOGIES_DISTINCTES_PAR_ANIMATEUR,
    MAX_JOURS_CONSECUTIFS_TRAVAILLES,
    MAX_JOURS_CONSECUTIFS_TRAVAILLES_DUR,
    STABILITE_DU_PLAN_PUBLIE,
    FAVORISER_MIXITE_DES_NIVEAUX,
    EQUILIBRER_CRENEAUX_PENIBLES,
    PRESERVER_BUFFER_POLYVALENTS;

    private final String constraintName;

    Rule() {
        StringBuilder camel = new StringBuilder();
        for (String word : name().toLowerCase(Locale.ROOT).split("_")) {
            camel.append(camel.isEmpty() ? word : Character.toUpperCase(word.charAt(0)) + word.substring(1));
        }
        this.constraintName = camel.toString();
    }

    /** The name the constraint carries in the catalogue and in a Timefold score. */
    public String constraintName() {
        return constraintName;
    }
}
