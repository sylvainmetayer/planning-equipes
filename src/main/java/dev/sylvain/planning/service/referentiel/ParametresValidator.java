package dev.sylvain.planning.service.referentiel;

import dev.sylvain.planning.domain.ParametresLegaux;
import dev.sylvain.planning.domain.ParametresNotifications;
import dev.sylvain.planning.domain.ParametresQualite;
import dev.sylvain.planning.domain.ParametresSolveur;
import dev.sylvain.planning.domain.PlafondsLegauxMajeurs;
import dev.sylvain.planning.service.BusinessError;

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

    /** Highest weight an edition may give one constraint — see {@link #checkConstraintWeight}. */
    static final int CONSTRAINT_WEIGHT_MAX = 100;

    private ParametresValidator() {}

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
        checkCap(
                parametres.getDureeHebdomadaireMaxMinutes(),
                ParametresLegaux.DUREE_HEBDOMADAIRE_MAX_MINUTES_PAR_DEFAUT,
                "dureeHebdomadaireMaxMinutes",
                "la durée hebdomadaire maximale des majeurs ne peut pas dépasser 48 h "
                        + "(Code du travail art. L3121-20, disposition d'ordre public)");
        checkCap(
                parametres.getDureeHebdomadaireMaxMineurMinutes(),
                ParametresLegaux.DUREE_HEBDOMADAIRE_MAX_MINEUR_MINUTES_PAR_DEFAUT,
                "dureeHebdomadaireMaxMineurMinutes",
                "la durée hebdomadaire maximale des mineurs ne peut pas dépasser 35 h "
                        + "(Code du travail art. L3162-1)");
        if (parametres.getReposQuotidienMinimalMinutes() < 0) {
            throw new BusinessError.Invalid("reposQuotidienMinimalMinutes must not be negative");
        }
        // Strictly positive, not merely non-negative: at zero every créneau of
        // the grid would be « too long », and the warning would say nothing.
        if (parametres.getDureeVacationMaxMinutes() <= 0) {
            throw new BusinessError.Invalid("dureeVacationMaxMinutes must be positive");
        }
        if (parametres.getCoupureRepasMinutes() < 0) {
            throw new BusinessError.Invalid("coupureRepasMinutes must not be negative");
        }
        // A floor, not a ceiling — the mirror image of the two weekly caps
        // above. Giving more rest than the Code owes is the organiser's to
        // decide; giving less is not (issue #592).
        // One duration for the edition, floored at the adult's twenty minutes.
        // A minor's thirty are floored higher still, by the minor's reading of
        // ParametresLegaux#dureePauseMinutes rather than here: refusing
        // an edition that grants twenty-five would force it to give thirty to
        // everybody, which is not what art. L3162-3 asks for (ADR 0048).
        checkPlancher(
                parametres.getDureePauseMinutes(),
                PlafondsLegauxMajeurs.PAUSE_MINIMALE_MINUTES,
                "la durée de pause ne peut pas être inférieure à 20 minutes "
                        + "(Code du travail art. L3121-16, disposition d'ordre public)");
        checkFenetre(parametres.getCoupureRepasMidiDebut(), parametres.getCoupureRepasMidiFin(), "coupureRepasMidi");
        checkFenetre(parametres.getCoupureRepasSoirDebut(), parametres.getCoupureRepasSoirFin(), "coupureRepasSoir");
        // Required, not defaulted: an evening that starts « never » would empty
        // a column of the Équité screen without a word, and the reader would
        // take the zeros for a fair plan.
        if (parametres.getHeureDebutSoiree() == null) {
            throw new BusinessError.Invalid("heureDebutSoiree is required (HH:MM)");
        }
    }

    /** Refuses a value below a floor of ordre public, with the article that sets it. */
    private static void checkPlancher(int valeur, int plancher, String message) {
        if (valeur < plancher) {
            throw new BusinessError.Invalid(message);
        }
    }

    /**
     * A window is a pair or nothing: one bound without the other is a typo, not
     * a choice. An empty or reversed window is accepted — {@code FenetreRepas}
     * drops it, which is how an event without an evening service says so.
     */
    private static void checkFenetre(java.time.LocalTime debut, java.time.LocalTime fin, String champ) {
        if ((debut == null) != (fin == null)) {
            throw new BusinessError.Invalid(champ + " needs both its bounds, or neither");
        }
    }

    /**
     * Refuses a threshold that could not mean anything, and nothing more. No
     * floor of ordre public here, unlike the legal parameters: these are
     * comforts the organiser arbitrates, and a value that makes a rule inert is
     * a legitimate way of saying « not here » — {@code heureServiceTardif} left
     * blank is exactly that.
     */
    static void checkParametresQualite(ParametresQualite parametres) {
        if (parametres.maxEmplacementsDistinctsParJour() < 1) {
            throw new BusinessError.Invalid("Le plafond d'emplacements distincts par jour doit valoir au moins 1 : "
                    + "à zéro, toute journée serait en écart.");
        }
        if (parametres.typologiesDistinctesMax() < 1) {
            throw new BusinessError.Invalid(
                    "Le plafond de typologies distinctes par animateur doit valoir au moins 1 : "
                            + "à zéro, tout animateur affecté serait en écart.");
        }
        if (parametres.joursConsecutifsMax() < 1) {
            throw new BusinessError.Invalid("Le plafond de jours travaillés d'affilée doit valoir au moins 1 : "
                    + "à zéro, tout animateur affecté serait en écart.");
        }
        if (parametres.reposSouhaiteApresServiceTardifMinutes() < 0) {
            throw new BusinessError.Invalid("Le repos souhaité après un service tardif ne peut pas être négatif.");
        }
        checkFenetre(parametres.heureServiceTardif(), parametres.heureServiceMatinal(), "Les heures de service");
    }

    static void checkParametresSolveur(ParametresSolveur parametres) {
        if (parametres.dureeResolutionSecondes() <= 0) {
            throw new BusinessError.Invalid("dureeResolutionSecondes must be positive");
        }
    }

    /**
     * Refuses a schedule that could not be honoured, or a delay that turns a
     * reminder into harassment.
     *
     * <p>The upper bounds are not arbitrary limits on the organiser: they are
     * what keeps a typo from making the feature dangerous. A reminder delay of
     * zero would write to somebody the minute their planning is published,
     * before they have had any chance to read it, and a swap-request threshold
     * of zero would alert on every request as it arrives — turning an alert
     * into the noise it exists to avoid.</p>
     */
    static void checkParametresNotifications(ParametresNotifications parametres) {
        if (parametres.heureRappelVeille() == null) {
            throw new BusinessError.Invalid("heureRappelVeille is required");
        }
        // Refused rather than accepted and silently ignored: past this hour the
        // sending window is shorter than the interval between two runs of the
        // hourly job, so the reminder would never leave at all — and nothing on
        // the screen would say so. See ParametresNotifications.HEURE_RAPPEL_VEILLE_MAX.
        if (parametres.heureRappelVeille().isAfter(ParametresNotifications.HEURE_RAPPEL_VEILLE_MAX)) {
            throw new BusinessError.Invalid("L'heure d'envoi du rappel ne peut pas dépasser "
                    + ParametresNotifications.HEURE_RAPPEL_VEILLE_MAX
                    + " : la tâche s'exécute une fois par heure, et un rappel réglé plus tard"
                    + " ne partirait jamais.");
        }
        if (parametres.delaiRelanceHeures() < 1 || parametres.delaiRelanceHeures() > 24 * 30) {
            throw new BusinessError.Invalid("delaiRelanceHeures must be between 1 and 720");
        }
        if (parametres.ancienneteEchangeJours() < 1 || parametres.ancienneteEchangeJours() > 60) {
            throw new BusinessError.Invalid("ancienneteEchangeJours must be between 1 and 60");
        }
    }

    /**
     * Refuses a weight outside {@code [1, CONSTRAINT_WEIGHT_MAX]}.
     *
     * <p>Zero is refused on purpose: a rule weighted zero scores nothing, so
     * it would be disabled in fact while the Contraintes screen kept showing
     * it as active — and, for a legal rule, without the confirmation that
     * protects it. Switching a rule off is the toggle's job.</p>
     *
     * <p>The upper bound is not a law, only a guard against a typo: the score
     * levels are ordered lexicographically, so a weight in the thousands
     * cannot promote a medium constraint above a hard one, but it does flatten
     * every other rule of its own level into noise.</p>
     */
    static void checkConstraintWeight(int poids) {
        if (poids < 1) {
            throw new BusinessError.Invalid(
                    "Le poids d'une contrainte doit valoir au moins 1 : pour ne plus l'appliquer, désactivez-la.");
        }
        if (poids > CONSTRAINT_WEIGHT_MAX) {
            throw new BusinessError.Invalid(
                    "Le poids d'une contrainte ne peut pas dépasser " + CONSTRAINT_WEIGHT_MAX + ".");
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
