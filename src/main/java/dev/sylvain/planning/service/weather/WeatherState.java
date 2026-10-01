package dev.sylvain.planning.service.weather;

import java.time.Instant;

/**
 * What the last query of an edition saw — the line under the settings:
 * « Prévisions lues le 12/07 à 06:05 », « Service météo injoignable depuis le
 * 11/07 », « hors prévision ». The machine's state, not a setting: it is
 * neither dumped nor copied by a duplication ({@code meteo_etat}).
 *
 * @param outcome          how the last run ended, {@code null} before the first
 * @param lastReadAt       the last successful read
 * @param unreachableSince the first failure of the current streak, {@code null}
 *                         while the service answers
 * @param error            the sentence of the last failure, for the screen
 */
public record WeatherState(
        Outcome outcome, Instant lastAttemptAt, Instant lastReadAt, Instant unreachableSince, String error) {

    /** How a run ended. */
    public enum Outcome {
        /** The forecast was read and compared. */
        READ,
        /** No date of the event falls in the forecast window: nothing was queried. */
        OUT_OF_FORECAST,
        /** No stand open on those dates has a located place: nothing was queried. */
        NO_PLACE,
        /** The service did not answer usefully. */
        UNREACHABLE
    }

    public static WeatherState never() {
        return new WeatherState(null, null, null, null, null);
    }
}
