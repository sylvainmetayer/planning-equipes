package dev.sylvain.planning.service.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sylvain.planning.domain.PlageStand;
import dev.sylvain.planning.domain.Stand;
import java.time.LocalDate;
import java.time.LocalTime;
import org.junit.jupiter.api.Test;

/** A version 5 file whose stand gives its model without the plages it stands for (ADR 0104). */
class ScenarioModelUnfoldTest {

    private static final String YAML = """
            schemaVersion: 5
            festival:
              dateDebut: 2026-07-08
            creneaux:
              - { id: C1, jour: 1, date: 2026-07-08, heureDebut: "10:00", heureFin: "20:00" }
              - { id: C2, jour: 2, date: 2026-07-09, heureDebut: "22:00", heureFin: "02:00" }
            stands:
              - id: S
                nom: S
                typologiesProposees: [T]
                effectifMin: 1
                effectifMax: 3
                horaires:
                  - mode: OUVERTURE
                    fenetres:
                      - { heureDebut: "10:00", heureFin: "12:00" }
                plages:
                  - { date: 2026-07-08, mode: OUVERTURE, heureDebut: "14:00", heureFin: "16:00" }
            animateurs: []
            """;

    @Test
    void theModelIsUnfoldedOnTheFileDaysTheStandCarriesNoPlageOn() {
        Stand stand = ScenarioDomainMapper.reference(ScenarioYamlReader.parseScenarioText(YAML))
                .standsById()
                .get("S");

        assertThat(stand.getHoraires()).hasSize(1);
        assertThat(stand.getPlages())
                .extracting(PlageStand::getDate, PlageStand::getHeureDebut)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(LocalDate.of(2026, 7, 8), LocalTime.of(14, 0)),
                        org.assertj.core.groups.Tuple.tuple(LocalDate.of(2026, 7, 9), LocalTime.of(10, 0)),
                        org.assertj.core.groups.Tuple.tuple(LocalDate.of(2026, 7, 10), LocalTime.of(10, 0)));
    }
}
