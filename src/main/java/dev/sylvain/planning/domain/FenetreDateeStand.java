package dev.sylvain.planning.domain;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Objects;

/**
 * A dated time window attached to a {@link Stand} on one calendar day — the
 * shape shared by {@link IndisponibiliteStand} (closure) and
 * {@link OuvertureStand} (explicit opening). The two subclasses carry opposite
 * business meanings and must never be interchangeable, so equality is scoped
 * to the exact runtime class, not to this base.
 *
 * <p>A window may not itself cross midnight ({@code heureFin} must be strictly
 * after {@code heureDebut}); a window spanning midnight is entered as two
 * rows, one per calendar day. This keeps the overlap arithmetic in
 * {@code Creneau#segmentsOuvertsMinutes(Stand)} simple and mirrors how a
 * créneau itself is never allowed to span more than "start day + one day".</p>
 *
 * <p>{@code heureFin} may however be {@code null}, meaning "until closing
 * time": the window then runs to the end of whatever créneau it is evaluated
 * against — see {@link FenetreHoraire}.</p>
 */
public abstract class FenetreDateeStand {

    private Long id;
    private LocalDate date;
    private LocalTime heureDebut;
    /** {@code null} = until the end of the evaluated créneau ("fin de journée"). */
    private LocalTime heureFin;
    /** Free-text reason, nullable — purely informative, never read by the solver. */
    private String motif;

    protected FenetreDateeStand() {}

    protected FenetreDateeStand(Long id, LocalDate date, LocalTime heureDebut, LocalTime heureFin, String motif) {
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
    public boolean hasValidRange() {
        return date != null && heureDebut != null && (heureFin == null || heureFin.isAfter(heureDebut));
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        // Exact-class comparison: an OuvertureStand and an IndisponibiliteStand
        // with the same id are opposite facts, never equal.
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        return Objects.equals(id, ((FenetreDateeStand) o).id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
