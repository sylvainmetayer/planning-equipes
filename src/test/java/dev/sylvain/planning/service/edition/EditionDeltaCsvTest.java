package dev.sylvain.planning.service.edition;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sylvain.planning.service.edition.EditionDelta.DeltaChange;
import dev.sylvain.planning.service.edition.EditionDelta.DeltaFamily;
import dev.sylvain.planning.service.edition.EditionDelta.DeltaFamilyCount;
import dev.sylvain.planning.service.edition.EditionDelta.DeltaLine;
import dev.sylvain.planning.service.edition.EditionDelta.DeltaMatch;
import dev.sylvain.planning.service.edition.EditionDelta.DeltaSide;
import dev.sylvain.planning.service.edition.EditionDelta.DeltaSummary;
import dev.sylvain.planning.service.edition.EditionDelta.DeltaTimeslotLine;
import dev.sylvain.planning.service.edition.EditionDelta.DeltaValueGroup;
import dev.sylvain.planning.service.edition.EditionDelta.DeltaValueLine;
import dev.sylvain.planning.service.edition.EditionDelta.DeltaVolumes;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The delta's CSV: one line per difference, the volumes, and never an animateur's name. */
class EditionDeltaCsvTest {

    private static EditionDelta delta() {
        return new EditionDelta(
                new DeltaSide("E1", "Année 2025"),
                new DeltaSide("E2", "Année 2026"),
                new DeltaSummary(List.of(new DeltaFamilyCount(DeltaFamily.STAND, 1, 0, 0, 0)), 3, 3, 4, 16.0),
                false,
                List.of(),
                List.of(),
                List.of(new DeltaLine(DeltaChange.ADDED, null, null, "S3", "BUV", "=Buvette", List.of())),
                List.of(new DeltaLine(
                        DeltaChange.MODIFIED,
                        DeltaMatch.EMAIL,
                        "A1",
                        "A9",
                        null,
                        "Alice Martin",
                        List.of("nom", "dateNaissance"))),
                List.of(),
                List.of(new DeltaTimeslotLine(
                        DeltaChange.ADDED,
                        null,
                        2,
                        LocalTime.of(18, 0),
                        LocalDate.parse("2025-07-12"),
                        LocalDate.parse("2026-07-11"),
                        null,
                        "14",
                        List.of())),
                List.of(new DeltaValueLine(
                        DeltaValueGroup.CONSTRAINT_WEIGHT, "equiteHeures", "Équité des heures", null, "25")),
                List.of(),
                new DeltaVolumes(2, 4, 16.0, 70.0, 16.0 / 70.0),
                new DeltaVolumes(2, 8, 32.0, 0.0, null));
    }

    @Test
    void writesOneLinePerDifferenceThenTheVolumes() {
        List<String> lines = EditionDeltaCsv.write(delta()).lines().toList();

        assertThat(lines.get(0)).isEqualTo(EditionDeltaCsv.HEADER);
        assertThat(lines)
                .contains(
                        "stand;ADDED;;;S3;BUV;'=Buvette;;;",
                        "animateur;MODIFIED;EMAIL;A1;A9;;;nom,dateNaissance;;",
                        "creneau;ADDED;;;14;;jour 2 18:00;;2025-07-12;2026-07-11",
                        "constraint_weight;MODIFIED;;;;equiteHeures;Équité des heures;;;25",
                        "volumes;;;;;;sieges a pourvoir;;4;8",
                        "volumes;;;;;;heures a pourvoir;;16,00;32,00",
                        "volumes;;;;;;taux de remplissage;;0,23;");
    }

    @Test
    void namesNoAnimateur() {
        String csv = EditionDeltaCsv.write(delta());

        assertThat(csv).doesNotContain("Alice").doesNotContain("Martin");
    }
}
