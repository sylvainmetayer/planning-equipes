package dev.sylvain.planning.domain;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Objects;

/**
 * A single closure window of a {@link Stand}: the stand cannot be staffed
 * between {@code heureDebut} and {@code heureFin} on {@code date}. Replaces
 * the former {@code Creneau.standsOuvertsIds} (a per-créneau, whole-slot
 * open/closed toggle) so a stand can be closed for only part of a créneau —
 * see {@code Creneau#segmentsOuvertsMinutes(Stand)}, which subtracts these
 * windows from a créneau to find its still-open sub-intervals.
 *
 * <p>A window may not itself cross midnight ({@code heureFin} must be
 * strictly after {@code heureDebut}); a closure spanning midnight is entered
 * as two windows, one per calendar day. This keeps the overlap arithmetic in
 * {@code Creneau} simple and mirrors how a créneau itself is never allowed to
 * span more than "start day + one day".</p>
 */
public class IndisponibiliteStand {

    private Long id;
    private LocalDate date;
    private LocalTime heureDebut;
    private LocalTime heureFin;
    /** Free-text reason, nullable — purely informative, never read by the solver. */
    private String motif;

    public IndisponibiliteStand() {
    }

    public IndisponibiliteStand(Long id, LocalDate date, LocalTime heureDebut, LocalTime heureFin, String motif) {
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

    /** True when {@code heureDebut}/{@code heureFin} form a non-empty, same-day window. */
    public boolean estValide() {
        return date != null && heureDebut != null && heureFin != null && heureFin.isAfter(heureDebut);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof IndisponibiliteStand that)) {
            return false;
        }
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
