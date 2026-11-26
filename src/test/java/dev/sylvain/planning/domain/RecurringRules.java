package dev.sylvain.planning.domain;

import dev.sylvain.planning.domain.HorairesRecurrents.Regle;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

/** What a test needs to write a stand's schedule as 3.x rules and read it as the plages they stand for. */
public final class RecurringRules {

    private RecurringRules() {}

    /** Lays {@code regles} down as plages on exactly {@code jours}. */
    public static void poserSurJours(Stand stand, Collection<LocalDate> jours, Regle... regles) {
        stand.setPlages(HorairesRecurrents.expand(List.of(regles), stand.getPlages(), jours));
    }

    /** Lays {@code regles} down as plages on the days {@code creneaux} cover; the stand's own plages keep their days. */
    public static void poser(Stand stand, Collection<Creneau> creneaux, Regle... regles) {
        stand.setPlages(HorairesRecurrents.expand(
                List.of(regles), stand.getPlages(), HorairesRecurrents.datesConcernees(creneaux)));
    }
}
