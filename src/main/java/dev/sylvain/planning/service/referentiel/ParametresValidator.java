package dev.sylvain.planning.service.referentiel;

import dev.sylvain.planning.domain.ContactOrganisation;
import dev.sylvain.planning.domain.ParametresLegaux;
import dev.sylvain.planning.domain.ParametresNotifications;
import dev.sylvain.planning.domain.ParametresQualite;
import dev.sylvain.planning.domain.ParametresSolveur;
import dev.sylvain.planning.domain.PlafondsLegauxMajeurs;
import dev.sylvain.planning.service.BusinessError;
import dev.sylvain.planning.service.solve.SolveBudgetPolicy;
import dev.sylvain.planning.service.solve.SolverBudgetBounds;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

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

    /**
     * Highest weight an edition may give one constraint — see
     * {@link #checkConstraintWeight}. 500 rather than 100 since the scale was
     * multiplied by five (ADR 0057): what an edition had stored stays storable.
     */
    static final int CONSTRAINT_WEIGHT_MAX = 500;

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
        checkTrajet(parametres);
        if (parametres.toleranceArriveeGroupeeMinutes() < 0
                || parametres.toleranceArriveeGroupeeMinutes() > TOLERANCE_ARRIVEE_GROUPEE_MAX_MINUTES) {
            throw new BusinessError.Invalid("La tolérance d'une arrivée groupée doit valoir entre 0 et "
                    + TOLERANCE_ARRIVEE_GROUPEE_MAX_MINUTES + " minutes.");
        }
    }

    /** Largest grouped-arrival tolerance accepted: beyond four hours, nobody is waiting for anybody. */
    static final int TOLERANCE_ARRIVEE_GROUPEE_MAX_MINUTES = 240;

    /** Fastest walking pace accepted, in km/h: beyond it, nobody is walking any more. */
    static final double VITESSE_MARCHE_MAX_KM_H = 15.0;

    /** Largest detour factor accepted: a path five times the straight line is a maze, not a site. */
    static final double FACTEUR_DETOUR_MAX = 5.0;

    /** Largest tolerance accepted, in minutes: beyond two hours, no gap on a day could ever lack enough. */
    static final int TOLERANCE_TRAJET_MAX_MINUTES = 120;

    /**
     * The three walking-time settings. A speed of zero would make every trip
     * infinite, and a detour factor under 1 would walk shorter than the
     * straight line — two values that cannot mean anything. The upper bounds
     * only keep a typo (40 km/h, a factor of 13) from silently muting the rule.
     *
     * <p>Both decimals are judged as they will be stored — two decimals, the
     * column's {@code NUMERIC(4, 2)} — so a speed of 0.001 that the database
     * would keep as 0.00 is refused now rather than on the next save.</p>
     */
    private static void checkTrajet(ParametresQualite parametres) {
        double vitesse = asStored(parametres.vitesseMarcheKmH());
        if (!(vitesse > 0) || vitesse > VITESSE_MARCHE_MAX_KM_H) {
            throw new BusinessError.Invalid("La vitesse de marche doit être strictement positive et au plus de "
                    + (int) VITESSE_MARCHE_MAX_KM_H + " km/h.");
        }
        double facteur = asStored(parametres.facteurDetour());
        if (!(facteur >= 1) || facteur > FACTEUR_DETOUR_MAX) {
            throw new BusinessError.Invalid("Le facteur de détour doit valoir entre 1 et " + (int) FACTEUR_DETOUR_MAX
                    + " : sous 1, le trajet serait plus court que la ligne droite.");
        }
        if (parametres.toleranceTrajetMinutes() < 0
                || parametres.toleranceTrajetMinutes() > TOLERANCE_TRAJET_MAX_MINUTES) {
            throw new BusinessError.Invalid(
                    "La tolérance de trajet doit valoir entre 0 et " + TOLERANCE_TRAJET_MAX_MINUTES + " minutes.");
        }
    }

    /** Scale of the walking-speed and detour-factor columns. */
    private static final int TRAJET_DECIMALES = 2;

    /** A decimal rounded as PostgreSQL rounds it into its column; NaN and infinities are left to fail the bounds. */
    private static double asStored(double value) {
        if (!Double.isFinite(value)) {
            return value;
        }
        return BigDecimal.valueOf(value)
                .setScale(TRAJET_DECIMALES, RoundingMode.HALF_UP)
                .doubleValue();
    }

    /**
     * Refuses a budget the instance does not allow, rather than trimming it: an
     * organiser who set three hours and silently got two would read an
     * exhausted budget into a run that was simply cut. Either half may be
     * {@code null}, which follows the deployment default.
     *
     * <p>The ceilings bind only the halves this write <b>changes</b>: a value
     * equal to {@code stored} passes whatever it is. A duration saved before
     * the operator lowered the ceiling is not the organiser's doing, and every
     * launch already runs it capped with a warning (ADR 0051); refusing it here
     * would make every other write of these settings fail — the mail switch,
     * or a plateau changed on its own — until someone lowered it by hand.</p>
     */
    static void checkParametresSolveur(
            ParametresSolveur parametres, ParametresSolveur stored, SolverBudgetBounds bounds) {
        checkParametresSolveur(
                parametres,
                bounds,
                !Objects.equals(parametres.dureeResolutionSecondes(), stored.dureeResolutionSecondes()),
                !Objects.equals(parametres.plateauSecondes(), stored.plateauSecondes()));
    }

    /**
     * The budget a scenario file carries: every rule but the ceilings. The file
     * describes what it was verified against, the ceiling is how long this
     * instance lets any edition hold the solver; a value above it is stored as
     * the file says and runs capped with a warning, like a value saved before
     * the ceiling was lowered — refusing the whole import over a termination
     * duration would lose the referential it came with.
     */
    static void checkImportedParametresSolveur(ParametresSolveur parametres, SolverBudgetBounds bounds) {
        checkParametresSolveur(parametres, bounds, false, false);
    }

    private static void checkParametresSolveur(
            ParametresSolveur parametres, SolverBudgetBounds bounds, boolean durationCapped, boolean plateauCapped) {
        Integer duree = parametres.dureeResolutionSecondes();
        Integer plateau = parametres.plateauSecondes();
        if (duree != null && duree <= 0) {
            throw new BusinessError.Invalid("La durée de résolution doit être strictement positive.");
        }
        if (durationCapped && duree != null && duree > bounds.maxSecondsLimit()) {
            throw new BusinessError.Invalid("La durée de résolution (" + SolveBudgetPolicy.humanDuration(duree)
                    + ") dépasse le plafond de l'instance : au plus "
                    + SolveBudgetPolicy.humanDuration(bounds.maxSecondsLimit()) + ", fixé par l'exploitant.");
        }
        if (plateau != null && plateau < 0) {
            throw new BusinessError.Invalid("L'arrêt sur plateau ne peut pas être négatif : 0 signifie « jamais ».");
        }
        if (plateauCapped && plateau != null && plateau > bounds.maxPlateauSeconds()) {
            throw new BusinessError.Invalid("L'arrêt sur plateau (" + SolveBudgetPolicy.humanDuration(plateau)
                    + ") dépasse le plafond de l'instance : au plus "
                    + SolveBudgetPolicy.humanDuration(bounds.maxPlateauSeconds()) + ", fixé par l'exploitant.");
        }
        long dureeEffective = duree != null ? duree : bounds.defaultSecondsLimit();
        if (plateau != null && plateau > dureeEffective) {
            throw new BusinessError.Invalid("L'arrêt sur plateau (" + SolveBudgetPolicy.humanDuration(plateau)
                    + ") ne peut pas dépasser la durée de résolution ("
                    + SolveBudgetPolicy.humanDuration(dureeEffective) + ").");
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

    /**
     * Refuses a contact nobody could use: a number or an address too long to
     * be one, an address without its {@code @}, a number holding letters.
     * Blank halves are fine — they mean « not published » — and are stored as
     * {@code null} by the service.
     */
    static void checkContactOrganisation(ContactOrganisation contact) {
        String telephone = contact.telephone();
        if (telephone != null) {
            if (telephone.length() > ContactOrganisation.TELEPHONE_MAX) {
                throw new BusinessError.Invalid(
                        "Le téléphone ne peut pas dépasser " + ContactOrganisation.TELEPHONE_MAX + " caractères.");
            }
            if (!telephone.matches("[0-9+().\\s-]+")) {
                throw new BusinessError.Invalid(
                        "Le téléphone ne contient que des chiffres, des espaces, et les signes + - . ( ).");
            }
        }
        String email = contact.email();
        if (email != null) {
            if (email.length() > ContactOrganisation.EMAIL_MAX) {
                throw new BusinessError.Invalid(
                        "L'adresse e-mail ne peut pas dépasser " + ContactOrganisation.EMAIL_MAX + " caractères.");
            }
            if (!email.matches("[^@\\s]+@[^@\\s]+")) {
                throw new BusinessError.Invalid(
                        "L'adresse e-mail du contact n'est pas une adresse : il y manque un @.");
            }
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
