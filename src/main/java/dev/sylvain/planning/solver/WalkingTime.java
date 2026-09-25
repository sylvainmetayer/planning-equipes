package dev.sylvain.planning.solver;

import dev.sylvain.planning.domain.Emplacement;

/**
 * Walking time between two emplacements, the one computation the rule
 * {@code trajetInsuffisantEntrePostes} and the read-out
 * {@code WalkSequenceAnalyzer} share — so the score and the screen can never
 * tell two stories about the same trip.
 *
 * <p>Great-circle distance ({@link Emplacement#distanceMetresTo}) multiplied by
 * a detour factor, divided by a walking speed, rounded <b>up</b> to the minute:
 * a trip of 19.5 minutes is announced as 20, never as 19. No routing service:
 * no network dependency, nothing sent to a third party, and a deterministic
 * value inside the solver. The detour factor is the lever for a site where one
 * walks around something.</p>
 *
 * <p>Pure and static, and it takes the settings as numbers rather than a
 * {@code ParametresQualite}: the constraint names the accessors it reads in its
 * own source, where {@code ConstraintParametersStructuralTest} looks for
 * them.</p>
 */
public final class WalkingTime {

    private static final double METRES_PER_KM = 1000.0;
    private static final double MINUTES_PER_HOUR = 60.0;

    private WalkingTime() {}

    /**
     * Walking minutes between two emplacements, or {@code null} when either is
     * missing or has no coordinates — an unknown trip, never a zero one.
     */
    public static Integer minutes(Emplacement from, Emplacement to, double speedKmH, double detourFactor) {
        if (from == null || to == null) {
            return null;
        }
        Double metres = from.distanceMetresTo(to);
        return metres == null ? null : minutes(metres, speedKmH, detourFactor);
    }

    /** Walking minutes for a straight-line distance, rounded up; zero for no distance. */
    public static int minutes(double metres, double speedKmH, double detourFactor) {
        if (metres <= 0 || speedKmH <= 0) {
            return 0;
        }
        double metresPerMinute = speedKmH * METRES_PER_KM / MINUTES_PER_HOUR;
        // A hair of tolerance so 1 000 m × 1.3 at 4 km/h (exactly 19.5 min)
        // does not become 20.000000001 and round to 21 on a float artefact.
        return (int) Math.ceil(metres * detourFactor / metresPerMinute - 1e-9);
    }

    /**
     * Minutes a gap between two seats lacks for the walk between them, beyond
     * the tolerance: {@code walk − tolerance − gap}, never negative.
     */
    public static int missingMinutes(int walkMinutes, long gapMinutes, int toleranceMinutes) {
        return (int) Math.max(0, walkMinutes - toleranceMinutes - gapMinutes);
    }
}
