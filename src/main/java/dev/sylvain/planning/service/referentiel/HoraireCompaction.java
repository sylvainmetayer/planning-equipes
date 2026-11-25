package dev.sylvain.planning.service.referentiel;

import java.util.List;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * What the "compact the horaires" action reports. Since 4.0 a stand's schedule
 * is a list of dated plages and there is no recurring rule left to fold them
 * into (ADR 0104), so the action has nothing to do and always answers with
 * the empty report. The two records stay on the wire only until the API and
 * the screen stop offering the action.
 */
public final class HoraireCompaction {

    private HoraireCompaction() {}

    /** The empty report: nothing is compacted, nothing is written. */
    public static RapportCompactage nothing(boolean applique) {
        return new RapportCompactage(applique, 0, 0, 0, List.of());
    }

    /** What compaction would do, or did, to one stand. */
    @Schema(
            requiredProperties = {
                "compacte",
                "ecartMinutes",
                "exceptionsApres",
                "fenetresAvant",
                "reglesApres",
                "reglesElaguees"
            })
    public record LigneCompactage(
            String standId,
            int fenetresAvant,
            int reglesApres,
            int exceptionsApres,
            int ecartMinutes,
            boolean compacte,
            int reglesElaguees,
            String raison) {}

    /** Overall outcome, plus one line per stand that was looked at. */
    @Schema(requiredProperties = {"applique", "fenetresApres", "fenetresAvant", "standsCompactes"})
    public record RapportCompactage(
            boolean applique,
            int standsCompactes,
            int fenetresAvant,
            int fenetresApres,
            List<LigneCompactage> stands) {}
}
