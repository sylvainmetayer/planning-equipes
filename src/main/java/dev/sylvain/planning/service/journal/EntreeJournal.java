package dev.sylvain.planning.service.journal;

import java.time.Instant;
import java.util.List;

/**
 * One line of the history, as it is written and as it is read back.
 *
 * <p><b>Nothing nominative goes in.</b> {@code acteurId} and {@code entiteId}
 * are identifiers, never names, addresses or birth dates, and {@code champs}
 * carries the <em>names</em> of the fields an edit changed, never their
 * values: « nom, email » says what moved without saying what it became. The
 * screen joins the identity from the referential when it reads, so a deleted
 * fiche leaves a line that names nobody — see {@code docs/rgpd.md}.</p>
 *
 * @param id        server-side sequence, unique within the edition
 * @param survenuLe when it happened
 * @param acteur    who did it, as coarsely as the application really knows
 * @param acteurId  {@code "admin"}, an animateur id, or {@code null}
 * @param action    the code of an {@link ActionJournalisee}
 * @param entite    what it acted on, {@code null} when nothing nameable
 * @param entiteId  which one, {@code null} likewise
 * @param champs    the fields an edit actually changed; empty when the action
 *                  is not an edit, or when the writer could not tell
 * @param resultat  whether it went through
 * @param statut    HTTP status when the action came from a request, else {@code null}
 */
public record EntreeJournal(
        long id,
        Instant survenuLe,
        Acteur acteur,
        String acteurId,
        String action,
        String entite,
        String entiteId,
        List<String> champs,
        Resultat resultat,
        Integer statut) {

    public EntreeJournal {
        champs = champs == null ? List.of() : List.copyOf(champs);
    }

    /** Whether the action went through, as coarse as it needs to be to be read at a glance. */
    public enum Resultat {
        SUCCES,
        REFUS
    }
}
