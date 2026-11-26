package dev.sylvain.planning.service.referentiel;

import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.HorairesRecurrents;
import dev.sylvain.planning.domain.PlageStand;
import dev.sylvain.planning.domain.Stand;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * The first layer of a stand's schedule: its stored {@link PlageStand}s, read
 * as they are. Since 4.0 there is no recurring rule to expand (ADR 0104): a
 * stand's days are exactly the plages it carries, so "resolving" a stand means
 * laying its stored plages down as its effective ones, which the consigne layer
 * ({@code ConsigneResolver}) then amends on top.
 *
 * <p>Pure and static, no CDI, no database, unit-testable on plain objects.</p>
 */
public final class HoraireStandResolver {

    private HoraireStandResolver() {}

    /**
     * Resets every stand of {@code stands} to its stored plages — a stand that
     * went through a consigne earlier is brought back to the nominal schedule,
     * so calling this twice yields the same result.
     */
    public static void apply(Collection<Stand> stands, Collection<Creneau> creneaux) {
        for (Stand stand : stands) {
            apply(stand, Set.of());
        }
    }

    /** The same reset for one stand; {@code dates} is kept for the callers that resolve a single stand over a known set of days. */
    public static void apply(Stand stand, Collection<LocalDate> dates) {
        if (stand != null) {
            stand.setPlagesEffectives(null);
        }
    }

    /**
     * Days a resolution has to cover: every date carrying a créneau, plus the
     * day after a créneau that crosses midnight — the only case
     * {@link Creneau#segmentsOuvertsMinutes(Stand)} reads a window dated later
     * than the slot's own date.
     */
    public static Set<LocalDate> datesConcernees(Collection<Creneau> creneaux) {
        return HorairesRecurrents.datesConcernees(creneaux);
    }

    /** What decided a given day of a stand's schedule. */
    public enum SourceHoraire {
        /** Nothing states anything about that day: open all day. */
        DEFAUT,
        /** Only plages the application unfolded from the stand's model state that day: its normal regime. */
        REGLE,
        /** The stand carries at least one plage somebody typed for that day. */
        EXCEPTION
    }

    /**
     * What governs {@code date} for {@code stand}. Reads the <b>stored</b>
     * plages, not the effective ones, so the answer stays the same whether or
     * not a consigne has been laid on top.
     */
    public static SourceHoraire sourceOfDay(Stand stand, LocalDate date) {
        if (stand == null || date == null) {
            return SourceHoraire.DEFAUT;
        }
        List<PlageStand> duJour = stand.getPlages().stream()
                .filter(plage -> date.equals(plage.getDate()))
                .toList();
        if (duJour.isEmpty()) {
            return SourceHoraire.DEFAUT;
        }
        return duJour.stream().allMatch(PlageStand::isDepliee) ? SourceHoraire.REGLE : SourceHoraire.EXCEPTION;
    }
}
