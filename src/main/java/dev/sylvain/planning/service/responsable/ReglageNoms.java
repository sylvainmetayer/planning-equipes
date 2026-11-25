package dev.sylvain.planning.service.responsable;

import java.time.Instant;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Whether the responsable de stand reads who holds each seat, or the shifts
 * only — for one edition (the default) or for one stand (its exception, either
 * way).
 *
 * <p>Names means first and last names, never an address, a phone number or an
 * age. {@code ouvertureLe} is the instant names start to show: before it the
 * setting reads « shifts only », {@code null} meaning at once. It means
 * nothing while {@code nomsVisibles} is off.</p>
 *
 * @param nomsVisibles whether names are shown (from {@code ouvertureLe} on)
 * @param ouvertureLe  {@code null} for no waiting
 */
@Schema(requiredProperties = {"nomsVisibles"})
public record ReglageNoms(boolean nomsVisibles, Instant ouvertureLe) {

    /** What an edition that never chose answers: shifts only. */
    public static final ReglageNoms HORAIRES_SEULS = new ReglageNoms(false, null);

    /** Whether names are shown at {@code instant}. */
    public boolean visibleA(Instant instant) {
        return nomsVisibles && (ouvertureLe == null || !instant.isBefore(ouvertureLe));
    }

    /** The instant names will start to show, when they are on but not yet shown at {@code instant}. */
    public Instant attenduA(Instant instant) {
        return nomsVisibles && !visibleA(instant) ? ouvertureLe : null;
    }
}
