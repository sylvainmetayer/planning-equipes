package dev.sylvain.planning.domain;

/**
 * Admin-configurable parameters for {@code VacationGeneratorService}, which
 * slices a day-long "amplitude" (opening window) into shorter, overlapping
 * work vacations. Every field is generation-time only: it drives what
 * {@link Creneau} rows get created, not how the solver scores them.
 *
 * <p>The meal break — its length and its two windows — is <em>not</em> here
 * any more. The découpage still places the midday and evening reliefs on
 * them, but reads them from {@link ParametresLegaux}: they are a rule of the
 * event the solver judges on every grid, sliced or not, and they belong with
 * the other rules the organiser sets (issue #438).</p>
 */
public class ParametresDecoupage {

    public enum PauseCoverageStrategy {
        /** The stand closes (no poste generated) during an unavoidable internal pause. */
        FERMETURE,
        /** A temporary extra poste is generated to cover the stand during the pause. */
        RELEVE,
        /**
         * The stand stays open during the pause but at <b>half</b> its usual
         * headcount: like {@link #RELEVE} a covering vacation is generated, but
         * it is flagged ({@link Creneau#isCouverturePause()}) so poste
         * generation only creates {@code ceil(effectifMin / 2)} seats on it
         * instead of the full complement.
         *
         * <p>This is what the source event actually does — see the
         * {@code Recap. Espaces - VOLUMES ANIM.} formula
         * {@code ARRONDI.SUP(effectif * .../2)} documented in
         * the reduced-headcount analysis (kept out of the public repository). {@link #FERMETURE}
         * (nobody) and {@link #RELEVE} (a whole extra crew) bracket that rule
         * without expressing it.</p>
         *
         * <p>Rounding is deliberately <b>up</b>: a stand needing a single
         * animateur keeps that one rather than silently closing, so closing a
         * stand stays an explicit decision ({@link #FERMETURE} or a dated
         * indisponibilité) instead of a side effect of integer division.</p>
         */
        EFFECTIF_REDUIT
    }

    public static final int DUREE_VACATION_CIBLE_MINUTES_PAR_DEFAUT = 5 * 60;
    public static final int DUREE_VACATION_MIN_MINUTES_PAR_DEFAUT = 3 * 60;
    /** Art. L3121-16: staying strictly under this threshold means no vacation ever needs an internal legal break. */
    public static final int DUREE_VACATION_MAX_MINUTES_PAR_DEFAUT = 6 * 60;

    public static final int DUREE_CHEVAUCHEMENT_MINUTES_PAR_DEFAUT = 30;
    /** What the Créneaux screen presumed silently before the mode was declared. */
    /**
     * A new edition holds final vacations.
     *
     * <p>It was AMPLITUDES, « what the screen presumed silently » before the
     * mode was declared at all (V66). The reason to keep it there has gone: a
     * grid declared in vacations now puts the slicing away instead of offering
     * its destructive button, so the old default no longer protects anything —
     * it only made a hand-typed grid read its meal relays as overlapping
     * amplitudes until somebody flipped the toggle. An edition that does slice
     * says so once, on the Créneaux page, or through its scenario file.</p>
     */
    public static final ModeGrilleCreneaux MODE_GRILLE_PAR_DEFAUT = ModeGrilleCreneaux.VACATIONS;

    private int dureeVacationCibleMinutes = DUREE_VACATION_CIBLE_MINUTES_PAR_DEFAUT;
    private int dureeVacationMinMinutes = DUREE_VACATION_MIN_MINUTES_PAR_DEFAUT;
    private int dureeVacationMaxMinutes = DUREE_VACATION_MAX_MINUTES_PAR_DEFAUT;
    private int dureeChevauchementMinutes = DUREE_CHEVAUCHEMENT_MINUTES_PAR_DEFAUT;
    private PauseCoverageStrategy strategieCouverturePendantPause = PauseCoverageStrategy.FERMETURE;
    private ModeGrilleCreneaux modeGrille = MODE_GRILLE_PAR_DEFAUT;

    public ParametresDecoupage() {}

    /**
     * How the edition's créneaux are to be read — see {@link ModeGrilleCreneaux}.
     * Kept with the découpage settings because that is the operation the
     * distinction governs: {@link ModeGrilleCreneaux#AMPLITUDES} is what the
     * découpage consumes, {@link ModeGrilleCreneaux#VACATIONS} what it
     * produces. Never {@code null}: a missing value reads as the default.
     */
    public ModeGrilleCreneaux getModeGrille() {
        return modeGrille;
    }

    public void setModeGrille(ModeGrilleCreneaux modeGrille) {
        this.modeGrille = modeGrille != null ? modeGrille : MODE_GRILLE_PAR_DEFAUT;
    }

    public int getDureeVacationCibleMinutes() {
        return dureeVacationCibleMinutes;
    }

    public void setDureeVacationCibleMinutes(int dureeVacationCibleMinutes) {
        this.dureeVacationCibleMinutes = dureeVacationCibleMinutes;
    }

    public int getDureeVacationMinMinutes() {
        return dureeVacationMinMinutes;
    }

    public void setDureeVacationMinMinutes(int dureeVacationMinMinutes) {
        this.dureeVacationMinMinutes = dureeVacationMinMinutes;
    }

    public int getDureeVacationMaxMinutes() {
        return dureeVacationMaxMinutes;
    }

    public void setDureeVacationMaxMinutes(int dureeVacationMaxMinutes) {
        this.dureeVacationMaxMinutes = dureeVacationMaxMinutes;
    }

    public int getDureeChevauchementMinutes() {
        return dureeChevauchementMinutes;
    }

    public void setDureeChevauchementMinutes(int dureeChevauchementMinutes) {
        this.dureeChevauchementMinutes = dureeChevauchementMinutes;
    }

    public PauseCoverageStrategy getStrategieCouverturePendantPause() {
        return strategieCouverturePendantPause;
    }

    public void setStrategieCouverturePendantPause(PauseCoverageStrategy strategieCouverturePendantPause) {
        this.strategieCouverturePendantPause = strategieCouverturePendantPause;
    }
}
