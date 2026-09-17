package dev.sylvain.planning.domain;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * One seat of the last <b>published</b> plan, as a problem fact (issue
 * « stabilité »): the stand, the vacation and the animateur the people were
 * told about. Positional like every other reconciliation of the code base —
 * the seats of one stand on one vacation are interchangeable, so no poste id
 * is kept.
 *
 * <p>The vacation is named by its <b>natural key</b> — day, hours, stand —
 * and not by the créneau id it happened to carry (issue #578). A créneau id is
 * an identity {@code BIGINT}: deleting a day's créneaux and recreating them
 * unchanged — a grid regeneration, or a journée type reapplied after a delete —
 * hands every one of them a new id. Matched on the id, the rule then found no
 * counterpart, stopped costing anything and let the solver reshuffle a day the
 * animateurs had already been sent, without a word anywhere. Matched on the
 * day and the hours, it keeps biting: the vacation is the same vacation,
 * whatever number the database gave it. Same identity as
 * {@code PublicationDiffService.Vacation.cle()}, the duplicate check and the
 * differential application of journées types already use.</p>
 *
 * <p>The list is empty until a plan has been published, and the constraint
 * reading it ({@code stabiliteDuPlanPublie}) is then silent: before the first
 * publication the solver stays free to reshuffle.</p>
 *
 * @param standId     stand the seat was published on
 * @param date        day of the vacation
 * @param heureDebut  hour the vacation opens at
 * @param heureFin    hour it closes at, past midnight included — the value is
 *                    compared, never interpreted
 * @param animateurId who was named on it, never {@code null}: an empty
 *                    published seat carries nobody to keep and is not a fact
 */
public record AffectationPubliee(
        String standId, LocalDate date, LocalTime heureDebut, LocalTime heureFin, String animateurId) {

    /** Same day, same hours, same stand: the same vacation, whatever its créneau id. */
    public String key() {
        return key(standId, date, heureDebut, heureFin);
    }

    /** The key of a vacation described piece by piece, the one form every caller builds. */
    public static String key(String standId, LocalDate date, LocalTime heureDebut, LocalTime heureFin) {
        return date + "|" + heureDebut + "|" + heureFin + "|" + standId;
    }
}
