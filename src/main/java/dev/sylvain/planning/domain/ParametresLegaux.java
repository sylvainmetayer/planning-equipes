package dev.sylvain.planning.domain;

/**
 * Global, admin-configurable legal parameters, loaded as a problem fact into
 * every {@link PlanningEvenement} (single instance, same pattern as
 * {@link ContrainteAdHoc}) so {@code LegalConstraints} can read them without
 * any static/global state.
 */
public class ParametresLegaux {

    /**
     * Absolute weekly working-time cap for every animateur, all paid. Default: 48 h = 2880 min, the ceiling set
     * by both the Code du travail (art. L3121-20, durée maximale hebdomadaire
     * absolue) and the Convention collective nationale de l'Animation (ÉCLAT,
     * IDCC 1518, art. 5.2) — editable from the "Constraints" screen.
     */
    public static final int DUREE_HEBDOMADAIRE_MAX_MINUTES_PAR_DEFAUT = 48 * 60;

    /**
     * Absolute weekly working-time cap for a minor ("jeune travailleur"),
     * whatever their age bracket. Default: 35 h = 2100 min, set by the Code du
     * travail art. L3162-1 (<i>« Les jeunes travailleurs ne peuvent être
     * employés à un travail effectif excédant huit heures par jour et
     * trente-cinq heures par semaine »</i>) and, for the 14-to-under-16
     * bracket employed during school holidays, by art. D4153-3 (same 35 h
     * weekly figure). Ordre public: a value above 35 h is rejected by
     * {@code ReferenceDataService.updateParametresLegaux}; a lower (more
     * protective) value stays free.
     */
    public static final int DUREE_HEBDOMADAIRE_MAX_MINEUR_MINUTES_PAR_DEFAUT = 35 * 60;

    /**
     * Minimum gap required between the end of one vacation ({@code Creneau})
     * and the start of another one, same day, for the same animateur — so a
     * découpage that generates several vacations per day for one seat-track
     * never reconstitutes an unbroken working day just by chaining vacations
     * back to back. Default: 30 min.
     */
    public static final int PAUSE_MINIMALE_ENTRE_VACATIONS_MINUTES_PAR_DEFAUT = 30;

    /**
     * Minimum rest required between the end of an animateur's last vacation on
     * a calendar day and the start of their first vacation the next calendar
     * day. Default: 11 h = 660 min, the absolute daily rest floor set by the
     * Code du travail (art. L3131-1). Generalizes {@code reposQuotidienMineur}
     * (night → next-day noon, minors only) to every animateur.
     */
    public static final int REPOS_QUOTIDIEN_MINIMAL_MINUTES_PAR_DEFAUT = 11 * 60;

    private int dureeHebdomadaireMaxMinutes = DUREE_HEBDOMADAIRE_MAX_MINUTES_PAR_DEFAUT;
    private int dureeHebdomadaireMaxMineurMinutes = DUREE_HEBDOMADAIRE_MAX_MINEUR_MINUTES_PAR_DEFAUT;
    private int pauseMinimaleEntreVacationsMinutes = PAUSE_MINIMALE_ENTRE_VACATIONS_MINUTES_PAR_DEFAUT;
    private int reposQuotidienMinimalMinutes = REPOS_QUOTIDIEN_MINIMAL_MINUTES_PAR_DEFAUT;

    public ParametresLegaux() {
    }

    public ParametresLegaux(int dureeHebdomadaireMaxMinutes) {
        this.dureeHebdomadaireMaxMinutes = dureeHebdomadaireMaxMinutes;
    }

    public ParametresLegaux(int dureeHebdomadaireMaxMinutes, int dureeHebdomadaireMaxMineurMinutes) {
        this.dureeHebdomadaireMaxMinutes = dureeHebdomadaireMaxMinutes;
        this.dureeHebdomadaireMaxMineurMinutes = dureeHebdomadaireMaxMineurMinutes;
    }

    public ParametresLegaux(int dureeHebdomadaireMaxMinutes, int pauseMinimaleEntreVacationsMinutes,
            int reposQuotidienMinimalMinutes) {
        this.dureeHebdomadaireMaxMinutes = dureeHebdomadaireMaxMinutes;
        this.pauseMinimaleEntreVacationsMinutes = pauseMinimaleEntreVacationsMinutes;
        this.reposQuotidienMinimalMinutes = reposQuotidienMinimalMinutes;
    }

    public int getDureeHebdomadaireMaxMinutes() {
        return dureeHebdomadaireMaxMinutes;
    }

    public void setDureeHebdomadaireMaxMinutes(int dureeHebdomadaireMaxMinutes) {
        this.dureeHebdomadaireMaxMinutes = dureeHebdomadaireMaxMinutes;
    }

    public int getDureeHebdomadaireMaxMineurMinutes() {
        return dureeHebdomadaireMaxMineurMinutes;
    }

    public void setDureeHebdomadaireMaxMineurMinutes(int dureeHebdomadaireMaxMineurMinutes) {
        this.dureeHebdomadaireMaxMineurMinutes = dureeHebdomadaireMaxMineurMinutes;
    }

    public int getPauseMinimaleEntreVacationsMinutes() {
        return pauseMinimaleEntreVacationsMinutes;
    }

    public void setPauseMinimaleEntreVacationsMinutes(int pauseMinimaleEntreVacationsMinutes) {
        this.pauseMinimaleEntreVacationsMinutes = pauseMinimaleEntreVacationsMinutes;
    }

    public int getReposQuotidienMinimalMinutes() {
        return reposQuotidienMinimalMinutes;
    }

    public void setReposQuotidienMinimalMinutes(int reposQuotidienMinimalMinutes) {
        this.reposQuotidienMinimalMinutes = reposQuotidienMinimalMinutes;
    }
}
