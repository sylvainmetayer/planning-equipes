package dev.sylvain.planning.domain;

import java.time.LocalTime;

/**
 * One vacation of a {@link JourneeType}: the hours, and whether it is a meal
 * relay — the same flag a {@link Creneau} carries as {@code couverturePause},
 * so that materialising the template writes exactly what the créneau form
 * would.
 *
 * <p>A vacation ending at or before its start crosses midnight, as a créneau
 * does (« 20:00-00:00 » is the evening).</p>
 */
public record VacationType(LocalTime heureDebut, LocalTime heureFin, boolean couverturePause) {

    /** The créneau this vacation becomes on {@code date}; no id, no day number (both assigned later). */
    public Creneau toCreneau(java.time.LocalDate date) {
        Creneau creneau = new Creneau(null, 0, date, heureDebut, heureFin);
        creneau.setCouverturePause(couverturePause);
        return creneau;
    }

    /** The natural key shared with créneaux: two vacations with the same hours are the same slot. */
    public String key() {
        return heureDebut + "→" + heureFin;
    }
}
