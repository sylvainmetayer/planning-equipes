package dev.sylvain.planning.domain;

/**
 * The Code du travail caps on a minor's working time that are <b>not</b>
 * configurable: unlike {@link ParametresLegaux}, whose values an organiser may
 * tighten per edition, these come straight from the law and the application
 * has no business letting anyone raise them.
 *
 * <p>They live here because <b>two</b> unrelated pieces of the solver must
 * agree on them to the minute:</p>
 * <ul>
 *   <li>{@code LegalConstraints} — which penalises a plan that breaks them;</li>
 *   <li>{@code EligibleAnimateurMoveFilter} — which stops the search from ever
 *       proposing a move that would break them, in their single-créneau form.</li>
 * </ul>
 *
 * <p>The two used to hold their own copies, with a javadoc asking the next
 * reader to keep them in sync by hand. That is a silent failure mode: a filter
 * stricter than the constraints hides feasible assignments from local search,
 * so the plan simply stops improving — no error, no violation, just a score
 * that plateaus above zero hard. One declaration makes the drift impossible.</p>
 *
 * <p>Age brackets are always derived from {@code dateNaissance} at the
 * créneau's date, never stored — see {@link Animateur#isMineurOn} /
 * {@link Animateur#isUnder16On}.</p>
 */
public final class PlafondsLegauxMineurs {

    /** Art. L3162-1: 8 h/day for a young worker aged 16 to 18. */
    public static final int DUREE_QUOTIDIENNE_MAX_MINUTES = 8 * 60;

    /** Art. D4153-3: 7 h/day for a minor aged 14 to under 16 (school holidays). */
    public static final int DUREE_QUOTIDIENNE_MAX_MOINS_DE_16_ANS_MINUTES = 7 * 60;

    /** Art. L3162-3: a young worker's uninterrupted work may not exceed 4 h 30. */
    public static final int TRAVAIL_CONTINU_MAX_MINUTES = 4 * 60 + 30;

    /**
     * The daily cap that applies to {@code animateur} on {@code date}, chosen
     * on the age bracket they are in that day.
     */
    public static int dureeQuotidienneMaxMinutes(boolean moinsDe16Ans) {
        return moinsDe16Ans ? DUREE_QUOTIDIENNE_MAX_MOINS_DE_16_ANS_MINUTES : DUREE_QUOTIDIENNE_MAX_MINUTES;
    }

    private PlafondsLegauxMineurs() {
    }
}
