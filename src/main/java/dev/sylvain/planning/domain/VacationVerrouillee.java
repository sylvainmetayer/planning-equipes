package dev.sylvain.planning.domain;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * The vacation a lock names, by the natural key of a créneau — day, start,
 * end (issue #577).
 *
 * <p>An id would not do. {@code creneau.id} is an identity {@code BIGINT}:
 * deleting a day's créneaux and recreating them unchanged hands every one of
 * them a new id, and a lock keyed on the old one disappeared with it —
 * silently, since the foreign key cascaded. Applying a validated swap poses
 * two locks precisely so « the next regeneration does not undo the swap », so
 * that cascade undid the promise the locks were there to keep.</p>
 *
 * <p>Same identity as {@code PublicationDiffService.Vacation}, the duplicate
 * check on the grid, and the differential application of journées types.</p>
 */
public record VacationVerrouillee(LocalDate date, LocalTime heureDebut, LocalTime heureFin) {

    /** Whether this créneau is the one named — the join the repository does in SQL. */
    public boolean names(Creneau creneau) {
        return creneau != null
                && date.equals(creneau.getDate())
                && heureDebut.equals(creneau.getHeureDebut())
                && heureFin.equals(creneau.getHeureFin());
    }

    @Override
    public String toString() {
        return date + " " + heureDebut + "-" + heureFin;
    }
}
