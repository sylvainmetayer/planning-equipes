package dev.sylvain.planning.domain;

import java.time.LocalTime;

/**
 * Global, admin-configurable legal parameters, loaded as a problem fact into
 * every {@link PlanningEvenement} (single instance, same pattern as
 * {@link ContrainteAdHoc}) so {@code LegalConstraints} can read them without
 * any static/global state.
 *
 * <p>The meal break travels with them although it is not a legal obligation:
 * it is the rule the organisation sets itself, judged by
 * {@code coupureRepasObligatoire} on the {@link FenetreRepas} facts projected
 * from these five fields, and used by the découpage to place the midday and
 * evening reliefs. It used to live with the découpage parameters, which is
 * where nobody looked for it, and where a grid entered as vacations never
 * read it at all (issue #438).</p>
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

    /**
     * Whether the legal break — 20 consecutive minutes once an adult's working
     * time reaches 6 h (art. L3121-16), 30 minutes at 4 h 30 for a minor (art.
     * L3162-3) — is taken <b>on the post</b>, by relay between the colleagues
     * of the stand, rather than as a gap between two vacations. The Code
     * requires the break to be real, not to be scheduled: an organiser that
     * relieves each animateur for twenty minutes inside a 13:00-20:00 vacation
     * complies. When true, the continuous-work constraints treat the break as
     * organised inside the vacation, and the daily caps deduct it from the
     * amplitude (art. L3121-18 and L3162-1 count <i>travail effectif</i>).
     * Default false: the application never presumes an organisational fact it
     * does not hold; the organiser declares it.
     */
    public static final boolean PAUSE_SUR_POSTE_PAR_DEFAUT = false;

    /**
     * How long the meal break lasts, uninterrupted: one hour, so that a
     * two-hour window splits into two whole slots — 12-13 or 13-14 — that the
     * organiser can plan rather than endure (issue #438).
     */
    public static final int COUPURE_REPAS_MINUTES_PAR_DEFAUT = 60;

    public static final LocalTime COUPURE_REPAS_MIDI_DEBUT_PAR_DEFAUT = LocalTime.of(12, 0);
    public static final LocalTime COUPURE_REPAS_MIDI_FIN_PAR_DEFAUT = LocalTime.of(14, 0);
    public static final LocalTime COUPURE_REPAS_SOIR_DEBUT_PAR_DEFAUT = LocalTime.of(19, 0);
    public static final LocalTime COUPURE_REPAS_SOIR_FIN_PAR_DEFAUT = LocalTime.of(21, 0);

    /**
     * When the evening starts, for the equity read-out ({@code EquiteService}):
     * the minutes a poste covers past this hour are its evening hours, the
     * figure the organiser compares across the roster — « three nocturnes for
     * me, none for him » is the complaint the Équité screen exists to settle
     * before publication. A rule of the organisation, not of the law: a
     * minor's legal night stays in {@link Creneau}. Default 20:00, the earliest
     * legal night.
     */
    public static final LocalTime HEURE_DEBUT_SOIREE_PAR_DEFAUT = LocalTime.of(20, 0);

    private int dureeHebdomadaireMaxMinutes = DUREE_HEBDOMADAIRE_MAX_MINUTES_PAR_DEFAUT;
    private int dureeHebdomadaireMaxMineurMinutes = DUREE_HEBDOMADAIRE_MAX_MINEUR_MINUTES_PAR_DEFAUT;
    private int pauseMinimaleEntreVacationsMinutes = PAUSE_MINIMALE_ENTRE_VACATIONS_MINUTES_PAR_DEFAUT;
    private int reposQuotidienMinimalMinutes = REPOS_QUOTIDIEN_MINIMAL_MINUTES_PAR_DEFAUT;
    private boolean pauseSurPoste = PAUSE_SUR_POSTE_PAR_DEFAUT;

    private int coupureRepasMinutes = COUPURE_REPAS_MINUTES_PAR_DEFAUT;
    private LocalTime coupureRepasMidiDebut = COUPURE_REPAS_MIDI_DEBUT_PAR_DEFAUT;
    private LocalTime coupureRepasMidiFin = COUPURE_REPAS_MIDI_FIN_PAR_DEFAUT;
    private LocalTime coupureRepasSoirDebut = COUPURE_REPAS_SOIR_DEBUT_PAR_DEFAUT;
    private LocalTime coupureRepasSoirFin = COUPURE_REPAS_SOIR_FIN_PAR_DEFAUT;
    private LocalTime heureDebutSoiree = HEURE_DEBUT_SOIREE_PAR_DEFAUT;

    public ParametresLegaux() {}

    public ParametresLegaux(int dureeHebdomadaireMaxMinutes) {
        this.dureeHebdomadaireMaxMinutes = dureeHebdomadaireMaxMinutes;
    }

    public ParametresLegaux(int dureeHebdomadaireMaxMinutes, int dureeHebdomadaireMaxMineurMinutes) {
        this.dureeHebdomadaireMaxMinutes = dureeHebdomadaireMaxMinutes;
        this.dureeHebdomadaireMaxMineurMinutes = dureeHebdomadaireMaxMineurMinutes;
    }

    public ParametresLegaux(
            int dureeHebdomadaireMaxMinutes, int pauseMinimaleEntreVacationsMinutes, int reposQuotidienMinimalMinutes) {
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

    public boolean isPauseSurPoste() {
        return pauseSurPoste;
    }

    public void setPauseSurPoste(boolean pauseSurPoste) {
        this.pauseSurPoste = pauseSurPoste;
    }

    public int getCoupureRepasMinutes() {
        return coupureRepasMinutes;
    }

    public void setCoupureRepasMinutes(int coupureRepasMinutes) {
        this.coupureRepasMinutes = coupureRepasMinutes;
    }

    public LocalTime getCoupureRepasMidiDebut() {
        return coupureRepasMidiDebut;
    }

    public void setCoupureRepasMidiDebut(LocalTime coupureRepasMidiDebut) {
        this.coupureRepasMidiDebut = coupureRepasMidiDebut;
    }

    public LocalTime getCoupureRepasMidiFin() {
        return coupureRepasMidiFin;
    }

    public void setCoupureRepasMidiFin(LocalTime coupureRepasMidiFin) {
        this.coupureRepasMidiFin = coupureRepasMidiFin;
    }

    public LocalTime getCoupureRepasSoirDebut() {
        return coupureRepasSoirDebut;
    }

    public void setCoupureRepasSoirDebut(LocalTime coupureRepasSoirDebut) {
        this.coupureRepasSoirDebut = coupureRepasSoirDebut;
    }

    public LocalTime getCoupureRepasSoirFin() {
        return coupureRepasSoirFin;
    }

    public void setCoupureRepasSoirFin(LocalTime coupureRepasSoirFin) {
        this.coupureRepasSoirFin = coupureRepasSoirFin;
    }

    public LocalTime getHeureDebutSoiree() {
        return heureDebutSoiree;
    }

    public void setHeureDebutSoiree(LocalTime heureDebutSoiree) {
        this.heureDebutSoiree = heureDebutSoiree;
    }
}
