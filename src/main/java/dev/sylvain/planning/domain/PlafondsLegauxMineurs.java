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

    /** Art. L3162-3: the break that interrupts a young worker's stretch lasts at least 30 min. */
    public static final int PAUSE_MINIMALE_MINUTES = 30;

    /** Art. L3164-1: 12 consecutive hours of daily rest for a young worker. */
    public static final int REPOS_QUOTIDIEN_MIN_MINUTES = 12 * 60;

    /** Art. L3164-1: 14 consecutive hours of daily rest under 16. */
    public static final int REPOS_QUOTIDIEN_MIN_MOINS_DE_16_ANS_MINUTES = 14 * 60;

    /** Art. L3164-2: two consecutive rest days per week for young workers. */
    public static final int JOURS_REPOS_CONSECUTIFS_PAR_SEMAINE = 2;

    /**
     * Minutes of legal breaks a young worker takes <i>on the post</i> inside an
     * uninterrupted stretch of {@code stretchMinutes}, when the organiser has
     * declared that breaks are taken by relay ({@code ParametresLegaux.pauseSurPoste}):
     * one 30-minute break at the latest at 4 h 30, then another every 4 h 30 of
     * work. Zero for a stretch within the cap. Shared by the constraint and by
     * the move filter, so both read a long créneau the same way.
     */
    public static int onPostBreakMinutes(int stretchMinutes) {
        return onPostBreakMinutes(stretchMinutes, PAUSE_MINIMALE_MINUTES);
    }

    /**
     * The same, with the break the edition actually grants
     * ({@code ParametresLegaux.dureePauseMinutes}). It may be longer than
     * {@link #PAUSE_MINIMALE_MINUTES}, never shorter — the service refuses that
     * — so this deducts more rest from the amplitude, never less (issue #592).
     */
    public static int onPostBreakMinutes(int stretchMinutes, int pauseMinutes) {
        if (stretchMinutes <= TRAVAIL_CONTINU_MAX_MINUTES) {
            return 0;
        }
        return pauseMinutes
                * Math.ceilDiv(
                        stretchMinutes - TRAVAIL_CONTINU_MAX_MINUTES, TRAVAIL_CONTINU_MAX_MINUTES + pauseMinutes);
    }

    /**
     * The daily cap that applies to {@code animateur} on {@code date}, chosen
     * on the age bracket they are in that day.
     */
    public static int dureeQuotidienneMaxMinutes(boolean moinsDe16Ans) {
        return moinsDe16Ans ? DUREE_QUOTIDIENNE_MAX_MOINS_DE_16_ANS_MINUTES : DUREE_QUOTIDIENNE_MAX_MINUTES;
    }

    private PlafondsLegauxMineurs() {}
}
