package dev.sylvain.planning.service.referentiel;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * The days on which a group's declared unavailabilities disagree: some members
 * off, others not. A grouped arrival cannot hold on those days whatever the
 * solver does, so the admin validating a covoiturage is told how many there
 * are, and a write of the exception warns about them.
 */
public final class DivergentDays {

    private DivergentDays() {}

    /**
     * @param indisponibilites each member's declared unavailable days
     * @param joursEvenement   the days that carry a timeslot; empty — no grid
     *                         yet — counts every declared day
     */
    public static List<LocalDate> of(
            Collection<? extends Set<LocalDate>> indisponibilites, Set<LocalDate> joursEvenement) {
        TreeSet<LocalDate> union = new TreeSet<>();
        indisponibilites.forEach(union::addAll);
        return union.stream()
                .filter(jour -> joursEvenement.isEmpty() || joursEvenement.contains(jour))
                .filter(jour -> !indisponibilites.stream().allMatch(jours -> jours.contains(jour)))
                .toList();
    }
}
