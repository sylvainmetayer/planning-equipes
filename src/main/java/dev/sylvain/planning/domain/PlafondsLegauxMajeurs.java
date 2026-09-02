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

    /** Art. L3132-1: no more than six worked days in the same week. */
    public static final int JOURS_TRAVAILLES_MAX_PAR_SEMAINE = 6;

    private PlafondsLegauxMajeurs() {
    }
}
