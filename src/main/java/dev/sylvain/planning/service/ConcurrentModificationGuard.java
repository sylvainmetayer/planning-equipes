package dev.sylvain.planning.service;

import java.time.Instant;
import java.util.Map;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Words the two refusals a referential write can meet (issue #362), and reads
 * the stamp they have to report.
 *
 * <p>Every referential row carries {@code modifie_le} (V67). A client that
 * edits a row sends the value it loaded back with the write; the write itself
 * carries that value as its precondition, so a row written since is refused
 * with {@link BusinessError.Stale} — a 409 the frontend answers with
 * « recharger » or « écraser quand même ». A write carrying <b>no</b> value is
 * not checked: that is how an import, an old client or a deliberate overwrite
 * say they have nothing to compare.</p>
 *
 * <p>The check is part of the write, not a read before it: one statement whose
 * {@code ON CONFLICT DO UPDATE} carries the precondition and returns no row
 * when it fails. A read-then-write would let two saves a millisecond apart both
 * pass, which is the very lost update this exists to prevent — and it would
 * cost a round trip per save.</p>
 *
 * <p>Not a lock: two sessions may still both edit, the second is simply told.
 * With one shared admin account the two sessions are indistinguishable, so
 * this is the most the server can know (see the issue).</p>
 */
@ApplicationScoped
public class ConcurrentModificationGuard {

    /** How each table names its rows in a refusal, subject included. */
    private static final Map<String, String> SUJET = Map.of(
            "stand", "Ce stand",
            "animateur", "Cette fiche",
            "creneau", "Ce créneau",
            "emplacement", "Cet emplacement",
            "typologie", "Cette typologie",
            "contrainte_ad_hoc", "Cet ajustement manuel");

    @Inject
    JdbcEditionScope scope;

    /**
     * Refuses a write whose precondition did not match.
     *
     * <p>The moment is carried as an instant and <b>not</b> spelled out in the
     * message: the server runs in UTC while every other date the user sees is
     * rendered by their own browser, and this is the one message whose job is
     * to convince them that somebody really did write after them.</p>
     */
    void refuseStale(String table, String id) {
        throw new BusinessError.Stale(sujet(table) + " a été modifié" + accord(table)
                + " par une autre session après son ouverture ici. Rechargez pour voir ce qui a changé, "
                + "ou enregistrez à nouveau pour écraser cette modification.", scope.lastWriteOf(table, id));
    }

    /** Same, for a table whose id is database-generated ({@code creneau}). */
    void refuseStale(String table, long id) {
        throw new BusinessError.Stale(sujet(table) + " a été modifié" + accord(table)
                + " par une autre session après son ouverture ici. Rechargez pour voir ce qui a changé, "
                + "ou enregistrez à nouveau pour écraser cette modification.", scope.lastWriteOf(table, id));
    }

    /**
     * Refuses a creation whose id is taken. Detected by the write itself, which
     * is the only way to be sure: a probe before the insert leaves a window in
     * which the other session commits, and the upsert would then have replaced
     * a row the caller never meant to touch.
     */
    void refuseDuplicate(String table, String id) {
        throw new BusinessError.Conflict("L'identifiant « " + id + " » est déjà pris dans cette édition. "
                + "Choisissez-en un autre, ou ouvrez la fiche existante pour la modifier.");
    }

    private static String sujet(String table) {
        return SUJET.getOrDefault(table, "Cette fiche");
    }

    /** « Ce stand a été modifié », « Cette fiche a été modifiée ». */
    private static String accord(String table) {
        return sujet(table).startsWith("Cette") ? "e" : "";
    }
}
