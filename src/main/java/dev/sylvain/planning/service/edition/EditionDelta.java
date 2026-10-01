package dev.sylvain.planning.service.edition;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * What changed in the referential from one edition to another — the stands,
 * animateurs, timeslots and staffing the next edition is prepared on, read
 * from the two referentials as they are, never from a solved plan.
 *
 * <p>Every list carries <b>differences only</b>: an edition compared with
 * itself yields empty lists and a zero summary. The two volumes are the
 * exception, shown side by side whatever they say.</p>
 *
 * <p>Rows are matched across editions by what a person recognises, never by
 * id: two editions number their rows from the same counter, so {@code A151}
 * of one and {@code A151} of the other may be two different people (ADR
 * 0050). Every matched line says which key matched it.</p>
 *
 * @param reference          the edition compared from (A)
 * @param target             the edition compared to (B)
 * @param noAnimateurMatched the reference has animateurs and the target none,
 *                           so none of them can be found there — what a
 *                           duplication without the people leaves; the screen
 *                           says so once rather than listing the whole team as
 *                           gone. A target with a team of its own, however
 *                           different, never raises it
 * @param creneaux           the grid, aligned on the rank of the opening day
 *                           then on the start of the shift
 * @param parametres         legal parameters, constraint switches and weights
 *                           whose effective value differs
 * @param ajustements        ad hoc constraints counted by type, where the
 *                           counts differ; locks belong to a plan and are not
 *                           compared
 */
@Schema(
        requiredProperties = {
            "reference",
            "target",
            "summary",
            "noAnimateurMatched",
            "typologies",
            "emplacements",
            "stands",
            "animateurs",
            "journeesTypes",
            "creneaux",
            "parametres",
            "ajustements",
            "referenceVolumes",
            "targetVolumes"
        })
public record EditionDelta(
        DeltaSide reference,
        DeltaSide target,
        DeltaSummary summary,
        boolean noAnimateurMatched,
        List<DeltaLine> typologies,
        List<DeltaLine> emplacements,
        List<DeltaLine> stands,
        List<DeltaLine> animateurs,
        List<DeltaLine> journeesTypes,
        List<DeltaTimeslotLine> creneaux,
        List<DeltaValueLine> parametres,
        List<DeltaValueLine> ajustements,
        DeltaVolumes referenceVolumes,
        DeltaVolumes targetVolumes) {

    /**
     * The same delta with no animateur named: the label of every animateur
     * line dropped. Package-private: outside this package the anonymous delta
     * is what {@link EditionDeltaService#compare} returns, and the named one
     * has to be asked for by name ({@link EditionDeltaService#compareNamingAnimateurs}).
     */
    EditionDelta withoutPersonNames() {
        return new EditionDelta(
                reference,
                target,
                summary,
                noAnimateurMatched,
                typologies,
                emplacements,
                stands,
                animateurs.stream().map(DeltaLine::withoutLabel).toList(),
                journeesTypes,
                creneaux,
                parametres,
                ajustements,
                referenceVolumes,
                targetVolumes);
    }

    /** Whether nothing differs: every list empty. */
    public boolean noDifference() {
        return typologies.isEmpty()
                && emplacements.isEmpty()
                && stands.isEmpty()
                && animateurs.isEmpty()
                && journeesTypes.isEmpty()
                && creneaux.isEmpty()
                && parametres.isEmpty()
                && ajustements.isEmpty();
    }

    /** An edition as the delta names it. */
    @Schema(requiredProperties = {"id", "nom"})
    public record DeltaSide(String id, String nom) {}

    /** What became of a row from one edition to the other. */
    public enum DeltaChange {
        ADDED,
        REMOVED,
        MODIFIED
    }

    /**
     * Which key matched two rows: the readable code, the name (a fallback the
     * screen flags — except for a day template, whose only key it is), the
     * e-mail or the identity (first name, last name and birth date) of an
     * animateur, the position of a timeslot in the event.
     */
    public enum DeltaMatch {
        CODE,
        NOM,
        EMAIL,
        IDENTITE,
        POSITION
    }

    /** The families the summary counts. */
    public enum DeltaFamily {
        TYPOLOGIE,
        EMPLACEMENT,
        STAND,
        ANIMATEUR,
        JOURNEE_TYPE,
        CRENEAU,
        PARAMETRE,
        AJUSTEMENT
    }

    /** What a value line is a value of. */
    public enum DeltaValueGroup {
        LEGAL,
        CONSTRAINT_ACTIVE,
        CONSTRAINT_WEIGHT,
        AJUSTEMENT
    }

    /**
     * @param families              per family, the rows added, removed and modified
     * @param referenceDays         opening days of the reference: the dates carrying a timeslot
     * @param targetDays            same, in the target
     * @param seatDifference        seats to fill, target minus reference
     * @param hoursToFillDifference hours to fill, target minus reference
     */
    @Schema(requiredProperties = {"families", "referenceDays", "targetDays", "seatDifference", "hoursToFillDifference"})
    public record DeltaSummary(
            List<DeltaFamilyCount> families,
            int referenceDays,
            int targetDays,
            int seatDifference,
            double hoursToFillDifference) {}

    /**
     * @param matchedByName rows paired by their name for want of a code — always
     *                      zero for the day templates, which have none — the
     *                      fallback the screen flags even on a row nothing
     *                      else changed on, so an unchanged pair is still
     *                      known to rest on a name
     */
    @Schema(requiredProperties = {"family", "added", "removed", "modified", "matchedByName"})
    public record DeltaFamilyCount(DeltaFamily family, int added, int removed, int modified, int matchedByName) {}

    /**
     * One row of a referential that differs.
     *
     * @param matching    the key that matched the two rows; {@code null} on a
     *                    row present on one side only
     * @param referenceId the row's id in the reference, {@code null} when added
     * @param targetId    the row's id in the target, {@code null} when removed —
     *                    what « ouvrir dans l'édition B » opens
     * @param code        the readable code, when the referential has one
     * @param label       what the row is called — for an animateur, the name,
     *                    on the administrator's screen only
     * @param fields      the fields that differ, by name, never their values
     */
    @Schema(requiredProperties = {"change", "fields"})
    public record DeltaLine(
            DeltaChange change,
            DeltaMatch matching,
            String referenceId,
            String targetId,
            String code,
            String label,
            List<String> fields) {

        DeltaLine withoutLabel() {
            return new DeltaLine(change, matching, referenceId, targetId, code, null, fields);
        }
    }

    /**
     * One shift of the grid that differs, or one whole opening day.
     *
     * @param day           rank of the opening day, from 1: the N-th date carrying a timeslot
     * @param start         start of the shift; {@code null} on a whole day present on one side only
     * @param referenceDate the date of that day in the reference, {@code null} when it has none
     * @param targetDate    same, in the target
     * @param fields        the fields of the shift that differ ({@code heureFin}, {@code couverturePause})
     */
    @Schema(requiredProperties = {"change", "day", "fields"})
    public record DeltaTimeslotLine(
            DeltaChange change,
            DeltaMatch matching,
            int day,
            LocalTime start,
            LocalDate referenceDate,
            LocalDate targetDate,
            String referenceId,
            String targetId,
            List<String> fields) {}

    /**
     * A setting or a count whose value differs.
     *
     * @param key            the parameter's field, the constraint's name or the ad hoc type
     * @param label          the constraint's short label, from the catalogue; {@code null} otherwise
     * @param referenceValue the value in the reference — for a constraint, the
     *                       effective one, its default when the edition set none
     * @param targetValue    same, in the target
     */
    @Schema(requiredProperties = {"group", "key"})
    public record DeltaValueLine(
            DeltaValueGroup group, String key, String label, String referenceValue, String targetValue) {}

    /**
     * The volumetry of one edition — the figures of the Solveur's card — and
     * the fill ratio computed here rather than in the browser, so the CSV and
     * the assistant read the same one.
     *
     * @param fillRatio hours to fill over hours available; {@code null} when
     *                  nobody is available
     */
    @Schema(requiredProperties = {"animateurCount", "posteCount", "hoursToFill", "hoursAvailable"})
    public record DeltaVolumes(
            int animateurCount, int posteCount, double hoursToFill, double hoursAvailable, Double fillRatio) {}
}
