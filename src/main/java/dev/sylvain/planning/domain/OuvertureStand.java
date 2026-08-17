package dev.sylvain.planning.domain;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Objects;

/**
 * The opposite of {@link IndisponibiliteStand}: an explicit opening window
 * for a {@link Stand} on {@code date}, for a stand that is normally closed
 * and only staffed during specific windows (e.g. a themed stand open only in
 * the evening) — entering every other closure by hand would be tedious, so
 * this lets the organiser instead say when the stand *is* open on that day.
 *
 * <p>A stand's day is in exactly one of three states, decided per calendar
 * day (see {@code Creneau#segmentsOuvertsMinutes(Stand)}):</p>
 * <ul>
 * <li>No {@link OuvertureStand} and no {@link IndisponibiliteStand} that day:
 * open all day (the default).</li>
 * <li>At least one {@link OuvertureStand} that day: closed by default, open
 * only during the listed window(s).</li>
 * <li>At least one {@link IndisponibiliteStand} that day (and no
 * {@link OuvertureStand}): open by default, closed only during the listed
 * window(s).</li>
 * </ul>
 *
 * <p>A stand can never carry both an opening and a closure window on the
 * same calendar day — mixing the two modes for one day is ambiguous, so
 * {@code ReferenceDataService} rejects it at write time. A window may not
 * itself cross midnight, for the same reason as {@link IndisponibiliteStand};
 * its {@code heureFin} may however be {@code null} ("until closing time").</p>
 *
 * <p>Entering one window <i>per festival day</i> is what {@link HoraireStand}
 * exists to avoid: a recurring rule expands to these rows, and a row entered
 * here is the per-date exception that overrides them.</p>
 */
public class OuvertureStand {

    private Long id;
    private LocalDate date;
    private LocalTime heureDebut;
    /** {@code null} = until the end of the evaluated créneau ("fin de journée"). */
    private LocalTime heureFin;
    /** Free-text reason, nullable — purely informative, never read by the solver. */
    private String motif;

    public OuvertureStand() {
    }

    public OuvertureStand(Long id, LocalDate date, LocalTime heureDebut, LocalTime heureFin, String motif) {
        this.id = id;
        this.date = date;
        this.heureDebut = heureDebut;
        this.heureFin = heureFin;
        this.motif = motif;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public LocalDate getDate() {
        return date;
    }

    public void setDate(LocalDate date) {
        this.date = date;
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

    public String getMotif() {
        return motif;
    }

    public void setMotif(String motif) {
        this.motif = motif;
    }

    /** True when {@code heureDebut}/{@code heureFin} form a non-empty, same-day window (an open end counts as valid). */
    public boolean estValide() {
        return date != null && heureDebut != null && (heureFin == null || heureFin.isAfter(heureDebut));
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof OuvertureStand that)) {
            return false;
        }
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
