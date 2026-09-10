package dev.sylvain.planning.domain;

/**
 * The Code du travail caps that apply to every animateur and that an organiser
 * may <b>not</b> raise — the adult counterpart of {@link PlafondsLegauxMineurs},
 * and the same rationale for existing as a shared declaration rather than as
 * private constants: two unrelated pieces of the application must agree on them
 * to the minute.
 *
 * <ul>
 *   <li>{@code LegalConstraints} — which penalises a plan that breaks them;</li>
 *   <li>{@code StaffingAnalyzer} — which derives the minimum headcount from
 *       them. A staffing floor computed under caps laxer than the ones the
 *       solver enforces is not a floor at all: it promises a plan the solver
 *       will then refuse to produce.</li>
 * </ul>
 *
 * <p>The weekly ceiling itself is <em>not</em> here: it is
 * {@link ParametresLegaux#getDureeHebdomadaireMaxMinutes()}, which an organiser
 * may lower per edition.</p>
 */
public final class PlafondsLegauxMajeurs {

    /** Art. L3121-18: 10 h/day of travail effectif for an adult. */
    public static final int DUREE_QUOTIDIENNE_MAX_MINUTES = 10 * 60;

    /** Art. L3121-16: an adult's uninterrupted work may not exceed 6 h. */
    public static final int TRAVAIL_CONTINU_MAX_MINUTES = 6 * 60;

    /** Art. L3121-16: the break that interrupts an adult's working stretch lasts at least 20 min. */
    public static final int PAUSE_MINIMALE_MINUTES = 20;

    /**
     * Minutes of legal breaks an adult takes <i>on the post</i> inside an
     * uninterrupted stretch of {@code stretchMinutes}, when the organiser has
     * declared that breaks are taken by relay ({@code ParametresLegaux.pauseSurPoste}):
     * one 20-minute break at the latest at the sixth hour, then another every
     * 6 h of work — so {@code p} breaks let a stretch run for
     * {@code 6 h × (p + 1) + 20 min × p}. Zero for a stretch within the cap.
     */
    public static int onPostBreakMinutes(int stretchMinutes) {
        if (stretchMinutes <= TRAVAIL_CONTINU_MAX_MINUTES) {
            return 0;
        }
        return PAUSE_MINIMALE_MINUTES * Math.ceilDiv(stretchMinutes - TRAVAIL_CONTINU_MAX_MINUTES,
                TRAVAIL_CONTINU_MAX_MINUTES + PAUSE_MINIMALE_MINUTES);
    }

    /** Art. L3131-1: 11 consecutive hours of daily rest for an adult. */
    public static final int REPOS_QUOTIDIEN_MIN_MINUTES = 11 * 60;

    /**
     * Art. L3132-2 + L3131-1: 24 consecutive hours of weekly rest, on top of the
     * 11 h of daily rest — i.e. 35 consecutive hours.
     */
    public static final int REPOS_HEBDOMADAIRE_MIN_MINUTES = 35 * 60;

    /** Art. L3132-1: no more than six worked days in the same week. */
    public static final int JOURS_TRAVAILLES_MAX_PAR_SEMAINE = 6;

    private PlafondsLegauxMajeurs() {
    }
}
