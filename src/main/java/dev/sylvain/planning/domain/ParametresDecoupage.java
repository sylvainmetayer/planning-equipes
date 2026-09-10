package dev.sylvain.planning.domain;

import java.time.LocalTime;

/**
 * Admin-configurable parameters for {@code VacationGeneratorService}, which
 * slices a day-long "amplitude" (opening window) into shorter, overlapping
 * work vacations. Generation-time only: never exposed to the solver as a
 * problem fact (unlike {@link ParametresLegaux}), since it drives what
 * {@link Creneau} rows get created, not how the solver scores them.
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
    public static final int DUREE_PAUSE_REPAS_MINUTES_PAR_DEFAUT = 45;
    public static final LocalTime FENETRE_REPAS_MIDI_DEBUT_PAR_DEFAUT = LocalTime.of(12, 0);
    public static final LocalTime FENETRE_REPAS_MIDI_FIN_PAR_DEFAUT = LocalTime.of(14, 0);
    public static final LocalTime FENETRE_REPAS_SOIR_DEBUT_PAR_DEFAUT = LocalTime.of(19, 0);
    public static final LocalTime FENETRE_REPAS_SOIR_FIN_PAR_DEFAUT = LocalTime.of(21, 0);
    /** No staggering by default: every stand shares the same relay grid, exactly the historical behaviour. */
    public static final int NOMBRE_FAMILLES_DECALAGE_PAR_DEFAUT = 1;

    public static final int DUREE_DECALAGE_MAX_MINUTES_PAR_DEFAUT = 0;
    /** What the Créneaux screen presumed silently before the mode was declared. */
    public static final ModeGrilleCreneaux MODE_GRILLE_PAR_DEFAUT = ModeGrilleCreneaux.AMPLITUDES;

    private int dureeVacationCibleMinutes = DUREE_VACATION_CIBLE_MINUTES_PAR_DEFAUT;
    private int dureeVacationMinMinutes = DUREE_VACATION_MIN_MINUTES_PAR_DEFAUT;
    private int dureeVacationMaxMinutes = DUREE_VACATION_MAX_MINUTES_PAR_DEFAUT;
    private int dureeChevauchementMinutes = DUREE_CHEVAUCHEMENT_MINUTES_PAR_DEFAUT;
    private int dureePauseRepasMinutes = DUREE_PAUSE_REPAS_MINUTES_PAR_DEFAUT;
    private int nombreFamillesDecalage = NOMBRE_FAMILLES_DECALAGE_PAR_DEFAUT;
    private int dureeDecalageMaxMinutes = DUREE_DECALAGE_MAX_MINUTES_PAR_DEFAUT;
    private LocalTime fenetreRepasMidiDebut = FENETRE_REPAS_MIDI_DEBUT_PAR_DEFAUT;
    private LocalTime fenetreRepasMidiFin = FENETRE_REPAS_MIDI_FIN_PAR_DEFAUT;
    private LocalTime fenetreRepasSoirDebut = FENETRE_REPAS_SOIR_DEBUT_PAR_DEFAUT;
    private LocalTime fenetreRepasSoirFin = FENETRE_REPAS_SOIR_FIN_PAR_DEFAUT;
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

    public int getDureePauseRepasMinutes() {
        return dureePauseRepasMinutes;
    }

    public void setDureePauseRepasMinutes(int dureePauseRepasMinutes) {
        this.dureePauseRepasMinutes = dureePauseRepasMinutes;
    }

    public LocalTime getFenetreRepasMidiDebut() {
        return fenetreRepasMidiDebut;
    }

    public void setFenetreRepasMidiDebut(LocalTime fenetreRepasMidiDebut) {
        this.fenetreRepasMidiDebut = fenetreRepasMidiDebut;
    }

    public LocalTime getFenetreRepasMidiFin() {
        return fenetreRepasMidiFin;
    }

    public void setFenetreRepasMidiFin(LocalTime fenetreRepasMidiFin) {
        this.fenetreRepasMidiFin = fenetreRepasMidiFin;
    }

    public LocalTime getFenetreRepasSoirDebut() {
        return fenetreRepasSoirDebut;
    }

    public void setFenetreRepasSoirDebut(LocalTime fenetreRepasSoirDebut) {
        this.fenetreRepasSoirDebut = fenetreRepasSoirDebut;
    }

    public LocalTime getFenetreRepasSoirFin() {
        return fenetreRepasSoirFin;
    }

    public void setFenetreRepasSoirFin(LocalTime fenetreRepasSoirFin) {
        this.fenetreRepasSoirFin = fenetreRepasSoirFin;
    }

    public PauseCoverageStrategy getStrategieCouverturePendantPause() {
        return strategieCouverturePendantPause;
    }

    public void setStrategieCouverturePendantPause(PauseCoverageStrategy strategieCouverturePendantPause) {
        this.strategieCouverturePendantPause = strategieCouverturePendantPause;
    }

    /**
     * How many time-staggered relay-grid variants to generate per amplitude
     * (1 = off, the default: every stand shares one grid, unchanged
     * behaviour). Each stand is deterministically assigned to exactly one
     * variant by a hash of its id, so stands don't all hand over to a fresh
     * animateur at the same clock minute.
     */
    public int getNombreFamillesDecalage() {
        return nombreFamillesDecalage;
    }

    public void setNombreFamillesDecalage(int nombreFamillesDecalage) {
        this.nombreFamillesDecalage = nombreFamillesDecalage;
    }

    /**
     * Spread, in minutes, across which the {@code nombreFamillesDecalage}
     * variants' internal relay cuts are offset from the un-staggered target
     * (symmetric: half the variants land earlier, half later). Ignored when
     * {@code nombreFamillesDecalage <= 1}.
     */
    public int getDureeDecalageMaxMinutes() {
        return dureeDecalageMaxMinutes;
    }

    public void setDureeDecalageMaxMinutes(int dureeDecalageMaxMinutes) {
        this.dureeDecalageMaxMinutes = dureeDecalageMaxMinutes;
    }
}
