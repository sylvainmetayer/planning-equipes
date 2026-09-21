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
     * by the Code du travail (art. L3121-20, durée maximale hebdomadaire
     * absolue, disposition d'ordre public) — editable from the "Constraints"
     * screen.
     *
     * <p>The Convention collective de l'Animation (ÉCLAT, IDCC 1518) used to
     * be cited beside it, at art. 5.2. Two things were wrong with that: art.
     * 5.2 is about rest days, the 48 h high week is at art. 5.7.2.3
     * (modulation) — and the organisation this deployment serves has confirmed
     * it does not fall under that convention. The Code alone founds the value.
     * See {@code docs/contraintes.md}, « La convention collective de
     * l'Animation ».</p>
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
     * How long one vacation may run before the grid check says so. Default 6 h,
     * the art. L3121-16 threshold at which a break becomes legally mandatory:
     * a vacation staying strictly under it never needs an internal break, and
     * the gap before the animateur's next vacation is their pause.
     *
     * <p>It was a <em>slicing</em> setting, read from {@code ParametresDecoupage}
     * to bound what the découpage produced. The découpage is gone — every grid
     * is typed or projected from a journée type — but the rule it encoded is
     * not: it is a rule of the event, judged on whatever grid the organiser
     * ends up with, so it belongs here with the other rules, the way the meal
     * break already made that same move (issue #438).</p>
     */
    public static final int DUREE_VACATION_MAX_MINUTES_PAR_DEFAUT = 6 * 60;

    /**
     * Minimum rest required between the end of an animateur's last vacation on
     * a calendar day and the start of their first vacation the next calendar
     * day. Default: 11 h = 660 min, the absolute daily rest floor set by the
     * Code du travail (art. L3131-1). Generalizes {@code reposQuotidienMineur}
     * (night → next-day noon, minors only) to every animateur.
     */
    public static final int REPOS_QUOTIDIEN_MINIMAL_MINUTES_PAR_DEFAUT = 11 * 60;

    /**
     * How long the break that ends a working stretch lasts — <b>one duration
     * for the whole edition</b>, adult and young worker alike (ADR 0048).
     *
     * <p>Default: the thirty minutes the organisation settled on (issue #31).
     * Art. <b>L3121-16</b> owes an adult twenty at the sixth hour and that
     * twenty is the floor {@code ParametresValidator} refuses to go under; the
     * organisation gives more, because a thirty-minute relay is easier to
     * organise than a twenty-minute one and nothing in the Code forbids it.</p>
     *
     * <p>For a young worker the thirty minutes of art. <b>L3162-3</b> are
     * themselves a floor of ordre public, so {@link #dureePauseMinutes(boolean)}
     * raises a shorter edition value rather than apply it — see there. Two
     * separate fields used to carry the two brackets, which let an edition set
     * the adult one and leave the minors' one at its default, and let the
     * eligibility filter read one while the rules read the other.</p>
     */
    public static final int DUREE_PAUSE_MINUTES_PAR_DEFAUT = 30;

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
    private int dureeVacationMaxMinutes = DUREE_VACATION_MAX_MINUTES_PAR_DEFAUT;
    private int reposQuotidienMinimalMinutes = REPOS_QUOTIDIEN_MINIMAL_MINUTES_PAR_DEFAUT;

    private int dureePauseMinutes = DUREE_PAUSE_MINUTES_PAR_DEFAUT;
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

    public int getDureeVacationMaxMinutes() {
        return dureeVacationMaxMinutes;
    }

    public void setDureeVacationMaxMinutes(int dureeVacationMaxMinutes) {
        this.dureeVacationMaxMinutes = dureeVacationMaxMinutes;
    }

    public int getReposQuotidienMinimalMinutes() {
        return reposQuotidienMinimalMinutes;
    }

    public void setReposQuotidienMinimalMinutes(int reposQuotidienMinimalMinutes) {
        this.reposQuotidienMinimalMinutes = reposQuotidienMinimalMinutes;
    }

    public int getDureePauseMinutes() {
        return dureePauseMinutes;
    }

    public void setDureePauseMinutes(int dureePauseMinutes) {
        this.dureePauseMinutes = dureePauseMinutes;
    }

    /**
     * The break owed to this animateur on that date: the edition's own
     * duration, raised to the thirty minutes of art. <b>L3162-3</b> for a young
     * worker. That floor is d'ordre public, so an edition that sets twenty-five
     * gives twenty-five to its adults and thirty to its minors rather than be
     * refused outright — and every reader of a break length, rules, eligibility
     * filter, Pauses screen and hours alike, goes through here, so none of them
     * can be stricter or laxer than the others (ADR 0048).
     */
    public int dureePauseMinutes(boolean mineur) {
        return mineur ? Math.max(dureePauseMinutes, PlafondsLegauxMineurs.PAUSE_MINIMALE_MINUTES) : dureePauseMinutes;
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
