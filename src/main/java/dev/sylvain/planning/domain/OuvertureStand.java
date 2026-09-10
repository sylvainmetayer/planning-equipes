package dev.sylvain.planning.domain;

import java.time.LocalDate;
import java.time.LocalTime;

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
 * {@code ReferenceDataService} rejects it at write time. Window shape and
 * midnight/open-end rules are shared with {@link IndisponibiliteStand} —
 * see {@link FenetreDateeStand}.</p>
 *
 * <p>Entering one window <i>per event day</i> is what {@link HoraireStand}
 * exists to avoid: a recurring rule expands to these rows, and a row entered
 * here is the per-date exception that overrides them.</p>
 *
 * <p>{@code effectif} carries the staffing of that window, {@code null}
 * meaning "inherit {@link Stand#getEffectifMin()}" — see
 * {@link FenetreHoraire#getEffectif()}, whose value a rule expansion copies
 * onto the rows it produces. A closure has no equivalent field, which is why
 * this lives here rather than on {@link FenetreDateeStand}: there is no
 * headcount attached to being shut.</p>
 */
public class OuvertureStand extends FenetreDateeStand {

    /** Seats to fill on this window; {@code null} = inherit {@link Stand#getEffectifMin()}. */
    private Integer effectif;

    public OuvertureStand() {}

    public OuvertureStand(Long id, LocalDate date, LocalTime heureDebut, LocalTime heureFin, String motif) {
        this(id, date, heureDebut, heureFin, motif, null);
    }

    public OuvertureStand(
            Long id, LocalDate date, LocalTime heureDebut, LocalTime heureFin, String motif, Integer effectif) {
        super(id, date, heureDebut, heureFin, motif);
        this.effectif = effectif;
    }

    /** {@code null} = inherit {@link Stand#getEffectifMin()}, the historical behaviour. */
    public Integer getEffectif() {
        return effectif;
    }

    public void setEffectif(Integer effectif) {
        this.effectif = effectif;
    }
}
