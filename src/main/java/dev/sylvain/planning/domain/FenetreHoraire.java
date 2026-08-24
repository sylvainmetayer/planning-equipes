package dev.sylvain.planning.domain;

import java.time.LocalTime;
import java.util.Objects;

/**
 * One time window of a {@link HoraireStand}, without a date: the date comes
 * from the rule's day selector, which is exactly what lets a single rule cover
 * twelve event days.
 *
 * <p>{@code heureFin} may be {@code null}, meaning <b>"until closing time"</b>:
 * the window then runs to the end of whatever créneau it is evaluated against
 * (see {@link Creneau#segmentsOuvertsMinutes(Stand)}). That is what a stand
 * open "from 14:00 to closing" actually means, and it is why an event day
 * ending at 20:00, at 21:00 or at midnight no longer needs three different
 * rules — nor the {@code 23:59} stand-in a concrete end time forces whenever
 * the real end is midnight (a window may not cross midnight).</p>
 */
public class FenetreHoraire {

    private LocalTime heureDebut;
    /** {@code null} = until the end of the evaluated créneau ("fin de journée"). */
    private LocalTime heureFin;

    public FenetreHoraire() {
    }

    public FenetreHoraire(LocalTime heureDebut, LocalTime heureFin) {
        this.heureDebut = heureDebut;
        this.heureFin = heureFin;
    }

    public LocalTime getHeureDebut() {
        return heureDebut;
    }

    public void setHeureDebut(LocalTime heureDebut) {
        this.heureDebut = heureDebut;
    }

    public LocalTime getHeureFin() {
        return heureFin;
    }

    public void setHeureFin(LocalTime heureFin) {
        this.heureFin = heureFin;
    }

    /** True when this is a genuine, non-empty, same-day window (an open end counts as valid). */
    public boolean hasValidRange() {
        return heureDebut != null && (heureFin == null || heureFin.isAfter(heureDebut));
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof FenetreHoraire that)) {
            return false;
        }
        return Objects.equals(heureDebut, that.heureDebut) && Objects.equals(heureFin, that.heureFin);
    }

    @Override
    public int hashCode() {
        return Objects.hash(heureDebut, heureFin);
    }

    @Override
    public String toString() {
        return heureDebut + "→" + (heureFin != null ? heureFin.toString() : "fin de journée");
    }
}
