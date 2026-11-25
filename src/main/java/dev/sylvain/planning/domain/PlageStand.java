package dev.sylvain.planning.domain;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Objects;

/**
 * A <b>dated window</b> of a {@link Stand}: on {@code date}, between
 * {@code heureDebut} and {@code heureFin}, the stand is open
 * ({@link ModeHoraire#OUVERTURE}) or closed ({@link ModeHoraire#FERMETURE}).
 * A stand's schedule is nothing but a list of these (ADR 0104) — there is no
 * stored recurrence: "every Monday from 10:00 to 12:00" is a generator that
 * writes one plage per matching day.
 *
 * <p>A stand's day is in exactly one of three states, decided per calendar
 * day (see {@code Creneau#segmentsOuvertsMinutes(Stand)}):</p>
 * <ul>
 * <li>No plage that day: open all day (the default).</li>
 * <li>At least one {@code OUVERTURE} that day: closed by default, open only
 * during the listed windows.</li>
 * <li>At least one {@code FERMETURE} that day (and no {@code OUVERTURE}): open
 * by default, closed only during the listed windows.</li>
 * </ul>
 *
 * <p>A stand can never carry both modes on the same calendar day — mixing the
 * two is ambiguous, so {@code StandValidator} rejects it at write time.</p>
 *
 * <p>A window may not itself cross midnight ({@code heureFin} must be strictly
 * after {@code heureDebut}); a window spanning midnight is entered as two
 * plages, one per calendar day. {@code heureFin} may however be {@code null},
 * meaning "until closing time": the window then runs to the end of whatever
 * créneau it is evaluated against.</p>
 *
 * <p>{@code effectif} is the staffing of an {@code OUVERTURE} window,
 * {@code null} meaning "inherit {@link Stand#getEffectifMin()}"; a closure
 * carries none.</p>
 */
public class PlageStand {

    private Long id;
    private ModeHoraire mode = ModeHoraire.OUVERTURE;
    private LocalDate date;
    private LocalTime heureDebut;
    /** {@code null} = until the end of the evaluated créneau ("fin de journée"). */
    private LocalTime heureFin;
    /** Free-text reason, nullable — purely informative, never read by the solver. */
    private String motif;
    /** Seats to fill on an opening; {@code null} = inherit {@link Stand#getEffectifMin()}. */
    private Integer effectif;

    public PlageStand() {}

    public PlageStand(
            Long id,
            ModeHoraire mode,
            LocalDate date,
            LocalTime heureDebut,
            LocalTime heureFin,
            String motif,
            Integer effectif) {
        this.id = id;
        this.mode = mode != null ? mode : ModeHoraire.OUVERTURE;
        this.date = date;
        this.heureDebut = heureDebut;
        this.heureFin = heureFin;
        this.motif = motif;
        this.effectif = effectif;
    }

    /** An opening window; {@code effectif} {@code null} inherits the stand's minimum. */
    public static PlageStand ouverture(
            Long id, LocalDate date, LocalTime heureDebut, LocalTime heureFin, String motif, Integer effectif) {
        return new PlageStand(id, ModeHoraire.OUVERTURE, date, heureDebut, heureFin, motif, effectif);
    }

    public static PlageStand ouverture(
            Long id, LocalDate date, LocalTime heureDebut, LocalTime heureFin, String motif) {
        return ouverture(id, date, heureDebut, heureFin, motif, null);
    }

    /** A closure window. */
    public static PlageStand fermeture(
            Long id, LocalDate date, LocalTime heureDebut, LocalTime heureFin, String motif) {
        return new PlageStand(id, ModeHoraire.FERMETURE, date, heureDebut, heureFin, motif, null);
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public ModeHoraire getMode() {
        return mode;
    }

    public void setMode(ModeHoraire mode) {
        this.mode = mode != null ? mode : ModeHoraire.OUVERTURE;
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

    public Integer getEffectif() {
        return effectif;
    }

    public void setEffectif(Integer effectif) {
        this.effectif = effectif;
    }

    /** True when {@code heureDebut}/{@code heureFin} form a non-empty, same-day window (an open end counts as valid). */
    public boolean hasValidRange() {
        return date != null && heureDebut != null && (heureFin == null || heureFin.isAfter(heureDebut));
    }

    /** Equality is scoped to the mode: an opening and a closure with the same id are opposite facts. */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof PlageStand that)) {
            return false;
        }
        return mode == that.mode && Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mode, id);
    }
}
