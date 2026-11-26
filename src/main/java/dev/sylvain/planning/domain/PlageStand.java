package dev.sylvain.planning.domain;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Collection;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * A <b>dated window</b> of a {@link Stand}: on {@code date}, between
 * {@code heureDebut} and {@code heureFin}, the stand is open
 * ({@link ModeHoraire#OUVERTURE}) or closed ({@link ModeHoraire#FERMETURE}).
 * A stand's schedule is nothing but a list of these (ADR 0104) — there is no
 * stored recurrence: "every Monday from 10:00 to 12:00" is a generator that
 * writes one plage per matching day.
 *
 * <p>A stand's day is read per calendar day (see
 * {@code Creneau#segmentsOuvertsMinutes(Stand)}):</p>
 * <ul>
 * <li>No plage that day: open all day (the default).</li>
 * <li>At least one {@code OUVERTURE} that day: closed by default, open only
 * during the listed windows.</li>
 * <li>Only {@code FERMETURE}s that day: open by default, closed only during
 * the listed windows.</li>
 * <li>Both modes that day (ADR 0125): the openings, minus every closure — what
 * a consigne writes when its band falls on a stand that states its openings.
 * {@code HoraireStandResolver} restates such a day as openings alone, so every
 * reader of the effective plages still meets one mode per day.</li>
 * </ul>
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
    /**
     * Where the plage comes from: {@code true} when the application unfolded it from the stand's model
     * (the stand's normal regime), {@code false} when somebody typed it (an exception).
     */
    private boolean depliee;
    /**
     * Written by the consigne of its date (ADR 0125): lifting the consigne removes exactly the plages that
     * still carry the mark. Never set together with {@link #depliee}.
     */
    private boolean consigne;

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

    public boolean isDepliee() {
        return depliee;
    }

    public void setDepliee(boolean depliee) {
        this.depliee = depliee;
    }

    public boolean isConsigne() {
        return consigne;
    }

    public void setConsigne(boolean consigne) {
        this.consigne = consigne;
    }

    /** What makes two plages the same one: everything but the row id and the origin. */
    public String contentKey() {
        return mode + "|" + date + "|" + heureDebut + "|" + heureFin + "|" + effectif + "|" + motif;
    }

    /**
     * Marks each plage of {@code after} as unfolded, or as the consigne's, when {@code before} holds a
     * plage of the same content carrying that mark, and as typed otherwise: the origin follows the
     * content, never what a caller says — a consigne's plage edited by hand loses the mark and stays.
     */
    public static void carryOrigins(Collection<PlageStand> before, Collection<PlageStand> after) {
        Set<String> unfolded = new HashSet<>();
        Set<String> laidByConsigne = new HashSet<>();
        before.forEach(plage -> {
            if (plage.isDepliee()) {
                unfolded.add(plage.contentKey());
            } else if (plage.isConsigne()) {
                laidByConsigne.add(plage.contentKey());
            }
        });
        after.forEach(plage -> {
            plage.setDepliee(unfolded.contains(plage.contentKey()));
            plage.setConsigne(!plage.isDepliee() && laidByConsigne.contains(plage.contentKey()));
        });
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
