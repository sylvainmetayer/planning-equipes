package dev.sylvain.planning.service;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Detects a write based on an out-of-date read (issue #362).
 *
 * <p>Every referential row carries {@code modifie_le} (V67). A client that
 * edits a row sends the value it loaded back with the write; if the row was
 * written since, the two differ and the write is refused with
 * {@link BusinessError.Stale} — a 409 the frontend answers with « recharger »
 * or « écraser quand même ». A write carrying <b>no</b> value is not checked:
 * that is how an import, an MCP merge that just read the row, an old client or
 * a deliberate overwrite say they have nothing to compare.</p>
 *
 * <p>Not a lock: two sessions may still both edit, the second is simply told.
 * With one shared admin account the two sessions are indistinguishable, so
 * this is the most the server can know (see the issue). Compared at the
 * millisecond: the database keeps microseconds, a client that went through a
 * {@code Date} does not, and two writes of the same row in the same
 * millisecond are not a case worth a false conflict.</p>
 */
@ApplicationScoped
public class ConcurrentModificationGuard {

    private static final DateTimeFormatter MOMENT = DateTimeFormatter.ofPattern("dd/MM/yyyy 'à' HH:mm:ss");

    @Inject
    JdbcEditionScope scope;

    /** Refuses the write when {@code attendu} is set and older than the row's own stamp. */
    public void check(String table, String id, Instant attendu) {
        if (attendu == null) {
            return;
        }
        check(attendu, scope.lastWriteOf(table, id));
    }

    /** Same, for a table whose id is database-generated ({@code creneau}). */
    public void check(String table, long id, Instant attendu) {
        if (attendu == null) {
            return;
        }
        check(attendu, scope.lastWriteOf(table, id));
    }

    private static void check(Instant attendu, Instant actuel) {
        // A missing row is the caller's NotFound, not a conflict.
        if (actuel == null || meme(attendu, actuel)) {
            return;
        }
        throw new BusinessError.Stale("Cette fiche a été modifiée par une autre session le "
                + MOMENT.format(actuel.atZone(ZoneId.systemDefault()))
                + ", après son ouverture ici. Rechargez-la pour voir ce qui a changé, ou enregistrez à nouveau "
                + "pour écraser cette modification.", actuel);
    }

    private static boolean meme(Instant a, Instant b) {
        return a.truncatedTo(ChronoUnit.MILLIS).equals(b.truncatedTo(ChronoUnit.MILLIS));
    }
}
