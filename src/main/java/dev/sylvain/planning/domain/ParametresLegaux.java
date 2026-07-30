package dev.sylvain.planning.domain;

/**
 * Global, admin-configurable legal parameters, loaded as a problem fact into
 * every {@link PlanningFestival} (single instance, same pattern as
 * {@link ContrainteAdHoc}) so {@code LegalConstraints} can read them without
 * any static/global state.
 */
public class ParametresLegaux {

    /**
     * Absolute weekly working-time cap for every animateur, all paid (no more
     * bénévole/salarié distinction). Default: 48 h = 2880 min, the ceiling set
     * by both the Code du travail (art. L3121-20, durée maximale hebdomadaire
     * absolue) and the Convention collective nationale de l'Animation (ÉCLAT,
     * IDCC 1518, art. 5.2) — editable from the "Constraints" screen.
     */
    public static final int DUREE_HEBDOMADAIRE_MAX_MINUTES_PAR_DEFAUT = 48 * 60;

    private int dureeHebdomadaireMaxMinutes = DUREE_HEBDOMADAIRE_MAX_MINUTES_PAR_DEFAUT;

    public ParametresLegaux() {
    }

    public ParametresLegaux(int dureeHebdomadaireMaxMinutes) {
        this.dureeHebdomadaireMaxMinutes = dureeHebdomadaireMaxMinutes;
    }

    public int getDureeHebdomadaireMaxMinutes() {
        return dureeHebdomadaireMaxMinutes;
    }

    public void setDureeHebdomadaireMaxMinutes(int dureeHebdomadaireMaxMinutes) {
        this.dureeHebdomadaireMaxMinutes = dureeHebdomadaireMaxMinutes;
    }
}
