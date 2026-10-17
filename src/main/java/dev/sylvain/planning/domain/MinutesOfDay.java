package dev.sylvain.planning.domain;

import java.time.LocalTime;

/** Minute arithmetic on times of day, for windows that may cross midnight. */
public final class MinutesOfDay {

    private static final int PER_DAY = 24 * 60;

    private MinutesOfDay() {}

    /**
     * {@code [start, end]} in minutes from the start day's midnight. An end at
     * or before the start crosses midnight, so it lies past 1440 — an end equal
     * to the start is a full day.
     */
    public static int[] interval(LocalTime start, LocalTime end) {
        int s = start.toSecondOfDay() / 60;
        int e = end.toSecondOfDay() / 60;
        return new int[] {s, e > s ? e : e + PER_DAY};
    }

    /** Minutes from {@code origin} to the next {@code time}, the same day or the next. */
    public static int minutesAfter(LocalTime origin, LocalTime time) {
        int minutes = (time.toSecondOfDay() - origin.toSecondOfDay()) / 60;
        return minutes < 0 ? minutes + PER_DAY : minutes;
    }
}
