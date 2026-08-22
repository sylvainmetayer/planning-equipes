package dev.sylvain.planning.service;

import dev.sylvain.planning.domain.ParametresDecoupage;
import dev.sylvain.planning.domain.ParametresLegaux;
import dev.sylvain.planning.domain.ParametresSolveur;

/**
 * What the three admin-configurable parameter sets accept, as pure functions.
 *
 * <p>Kept apart from {@link ParametresService} because these rules — the legal
 * ceilings above all — deserve to be tested for what they are, without a
 * database in the way. The tests used to build a repository-less service and
 * read a {@code NullPointerException} as proof that validation had passed;
 * here, "the value is accepted" is simply a call that returns.</p>
 */
final class ParametresValidator {

    private ParametresValidator() {
    }

    /**
     * Refuses anything above the ordre public ceilings.
     *
     * <p>Art. <b>L3121-20</b> (48 h/week) is a disposition d'ordre public: no
     * agreement and no configuration may exceed it, short of an exceptional
     * administrative authorisation the application knows nothing about. Art.
     * <b>L3162-1</b> caps young workers at 35 h. A default value that is
     * correct protects nothing if the entry screen does not; before this
     * check, an administrator could store 100 h/week and the solver would
     * happily report a "valid" plan (hard score zero) that is plainly
     * illegal.</p>
     *
     * <p>A <i>lower</i> value stays free: it is more protective than the law.</p>
     */
    static void checkParametresLegaux(ParametresLegaux parametres) {
        checkCap(parametres.getDureeHebdomadaireMaxMinutes(),
                ParametresLegaux.DUREE_HEBDOMADAIRE_MAX_MINUTES_PAR_DEFAUT,
                "dureeHebdomadaireMaxMinutes",
                "la durée hebdomadaire maximale des majeurs ne peut pas dépasser 48 h "
                        + "(Code du travail art. L3121-20, disposition d'ordre public)");
        checkCap(parametres.getDureeHebdomadaireMaxMineurMinutes(),
                ParametresLegaux.DUREE_HEBDOMADAIRE_MAX_MINEUR_MINUTES_PAR_DEFAUT,
                "dureeHebdomadaireMaxMineurMinutes",
                "la durée hebdomadaire maximale des mineurs ne peut pas dépasser 35 h "
                        + "(Code du travail art. L3162-1)");
        if (parametres.getPauseMinimaleEntreVacationsMinutes() < 0) {
            throw new BusinessError.Invalid("pauseMinimaleEntreVacationsMinutes must not be negative");
        }
        if (parametres.getReposQuotidienMinimalMinutes() < 0) {
            throw new BusinessError.Invalid("reposQuotidienMinimalMinutes must not be negative");
        }
    }

    static void checkDecoupage(ParametresDecoupage parametres) {
        if (parametres.getDureeVacationMinMinutes() <= 0 || parametres.getDureeVacationMaxMinutes() <= 0
                || parametres.getDureeVacationCibleMinutes() <= 0) {
            throw new BusinessError.Invalid("vacation durations must be positive");
        }
        if (parametres.getDureeVacationMinMinutes() > parametres.getDureeVacationMaxMinutes()) {
            throw new BusinessError.Invalid(
                    "dureeVacationMinMinutes cannot be greater than dureeVacationMaxMinutes");
        }
        if (parametres.getDureeChevauchementMinutes() < 0 || parametres.getDureePauseRepasMinutes() < 0) {
            throw new BusinessError.Invalid("overlap and meal-break durations must not be negative");
        }
    }

    static void checkParametresSolveur(ParametresSolveur parametres) {
        if (parametres.dureeResolutionSecondes() <= 0) {
            throw new BusinessError.Invalid("dureeResolutionSecondes must be positive");
        }
    }

    private static void checkCap(int valeurMinutes, int plafondMinutes, String champ, String message) {
        if (valeurMinutes <= 0) {
            throw new BusinessError.Invalid(champ + " must be positive");
        }
        if (valeurMinutes > plafondMinutes) {
            throw new BusinessError.Invalid(message);
        }
    }
}
