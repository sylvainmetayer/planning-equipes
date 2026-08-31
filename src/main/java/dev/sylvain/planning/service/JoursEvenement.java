package dev.sylvain.planning.service;

import java.time.LocalDate;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

import dev.sylvain.planning.domain.Creneau;

/**
 * The days an edition's event spans, and the two bounds around them.
 *
 * <p><b>An {@code Edition} stores neither dates nor an "ongoing" flag</b>, so
 * there is nothing to read: the span is <em>derived</em> from the dates its
 * créneaux carry, which is what already decides which days an animateur may
 * declare unavailable ({@link DeclarationDisponibiliteService#joursEvenement}).
 * That derivation used to live there alone; it is here now so the write-time
 * warnings and the declaration form cannot drift into two different answers to
 * the same question.</p>
 *
 * <p>An edition with no créneau has <b>no</b> span — {@link #isEmpty()} — and
 * that is not a degenerate case to paper over: nothing can be said to fall
 * outside bounds that do not exist yet, and a warning invented there would be
 * a false one on the very screens (grid still empty, roster being typed in)
 * where it would be read first.</p>
 */
public record JoursEvenement(List<LocalDate> jours) {

    public JoursEvenement {
        jours = List.copyOf(jours);
    }

    /** The distinct dates of {@code creneaux}, ascending; undated ones ignored. */
    public static JoursEvenement of(Collection<Creneau> creneaux) {
        if (creneaux == null) {
            return new JoursEvenement(List.of());
        }
        return new JoursEvenement(creneaux.stream()
                .map(Creneau::getDate)
                .filter(Objects::nonNull)
                .distinct()
                .sorted(Comparator.naturalOrder())
                .toList());
    }

    public boolean isEmpty() {
        return jours.isEmpty();
    }

    /** Earliest day, or {@code null} when the edition has no créneau. */
    public LocalDate first() {
        return jours.isEmpty() ? null : jours.get(0);
    }

    /** Latest day, or {@code null} when the edition has no créneau. */
    public LocalDate last() {
        return jours.isEmpty() ? null : jours.get(jours.size() - 1);
    }

    /**
     * True when {@code date} lies between the two bounds, both included.
     *
     * <p>Deliberately the <b>span</b> and not the set of dated days: a gap day
     * inside a two-weekend event carries no créneau, and calling a date in
     * there "outside the event" would be wrong to the reader even though
     * nothing is scheduled on it.</p>
     *
     * <p><b>Being covered is not the same as being usable</b>, and the two must
     * not be confused by a caller: the availability circuit works on the exact
     * dates ({@link #hasCreneauOn}), not on the span — the declaration form
     * only offers those dates, and applying a declaration replaces
     * {@code joursIndisponibles} wholesale. An off day set on a gap day is
     * therefore inside the event and doomed all the same, which is why
     * {@link CoherenceAnalyzer} reports it under its own type instead of
     * staying silent on the strength of this method alone.</p>
     */
    public boolean covers(LocalDate date) {
        return date != null && !isEmpty() && !date.isBefore(first()) && !date.isAfter(last());
    }

    /**
     * True when a créneau is actually scheduled on {@code date} — the set the
     * availability declaration form offers, and the only days an applied
     * declaration keeps. See {@link #covers} for why the two differ.
     */
    public boolean hasCreneauOn(LocalDate date) {
        return date != null && jours.contains(date);
    }
}
