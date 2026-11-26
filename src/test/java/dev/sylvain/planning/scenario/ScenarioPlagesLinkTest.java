package dev.sylvain.planning.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sylvain.planning.domain.ModeHoraire;
import dev.sylvain.planning.scenario.dto.PlageStandDto;
import dev.sylvain.planning.scenario.dto.StandDto;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Link 2 → 3 of the scenario chain: a stand's schedule becomes a list of dated plages (ADR 0104). */
class ScenarioPlagesLinkTest {

    private static final String HEAD = """
            festival:
              dateDebut: 2026-07-08
            creneaux:
              - { id: C1, jour: 1, date: 2026-07-08, heureDebut: "10:00", heureFin: "20:00" }
              - { id: C2, jour: 2, date: 2026-07-09, heureDebut: "22:00", heureFin: "02:00" }
            stands:
            """;

    private static StandDto standOf(String yaml, List<String> warnings) {
        ScenarioBinder.Bound bound = ScenarioBinder.bindWithWarnings("schemaVersion: 2\n" + yaml);
        warnings.addAll(bound.warnings());
        return bound.scenario().stands().get(0);
    }

    @Test
    void aRecurringRuleBecomesOnePlagePerDayAndWindow() {
        List<String> warnings = new java.util.ArrayList<>();
        StandDto stand = standOf(HEAD + """
                  - id: S
                    nom: S
                    typologiesProposees: [T]
                    effectifMin: 1
                    effectifMax: 3
                    horaires:
                      - mode: OUVERTURE
                        fenetres:
                          - { heureDebut: "10:00", heureFin: "12:00", effectif: 2 }
                          - { heureDebut: "14:00" }
                """, warnings);

        // 8 July, 9 July (night timeslot) and 10 July (the morning after it): three days, two windows each.
        assertThat(stand.plages()).hasSize(6);
        assertThat(stand.plages())
                .extracting(PlageStandDto::date)
                .containsExactlyInAnyOrder(
                        LocalDate.of(2026, 7, 8),
                        LocalDate.of(2026, 7, 8),
                        LocalDate.of(2026, 7, 9),
                        LocalDate.of(2026, 7, 9),
                        LocalDate.of(2026, 7, 10),
                        LocalDate.of(2026, 7, 10));
        assertThat(stand.plages().get(0)).satisfies(plage -> {
            assertThat(plage.mode()).isEqualTo(ModeHoraire.OUVERTURE);
            assertThat(plage.heureDebut()).isEqualTo(LocalTime.of(10, 0));
            assertThat(plage.heureFin()).isEqualTo(LocalTime.of(12, 0));
            assertThat(plage.effectif()).isEqualTo(2);
        });
        assertThat(warnings).isEmpty();
    }

    @Test
    void datedWindowsKeepTheirDayAndBecomePlagesWithTheirMode() {
        List<String> warnings = new java.util.ArrayList<>();
        StandDto stand = standOf(HEAD + """
                  - id: S
                    nom: S
                    typologiesProposees: [T]
                    effectifMin: 1
                    effectifMax: 3
                    horaires:
                      - mode: OUVERTURE
                        fenetres:
                          - { heureDebut: "14:00" }
                    indisponibilites:
                      - { date: 2026-07-09, heureDebut: "10:00", motif: Férié }
                    ouvertures:
                      - { date: 2026-07-08, heureDebut: "18:00", heureFin: "20:00", effectif: 3 }
                """, warnings);

        // 8 July and 9 July carry a dated window: the rule leaves them alone and only lays 10 July down.
        assertThat(stand.plages())
                .extracting(PlageStandDto::date, PlageStandDto::mode, PlageStandDto::heureDebut)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(
                                LocalDate.of(2026, 7, 9), ModeHoraire.FERMETURE, LocalTime.of(10, 0)),
                        org.assertj.core.groups.Tuple.tuple(
                                LocalDate.of(2026, 7, 8), ModeHoraire.OUVERTURE, LocalTime.of(18, 0)),
                        org.assertj.core.groups.Tuple.tuple(
                                LocalDate.of(2026, 7, 10), ModeHoraire.OUVERTURE, LocalTime.of(14, 0)));
        assertThat(stand.plages().get(0).motif()).isEqualTo("Férié");
    }

    /** A file with no timeslot has no day to lay the rules on: they are dropped and the report says so, by stand. */
    @Test
    void rulesOfAFileWithoutTimeslotAreDroppedWithAWarningNamingTheStand() {
        List<String> warnings = new java.util.ArrayList<>();
        StandDto stand = standOf("""
                festival:
                  dateDebut: 2026-07-08
                creneaux: []
                stands:
                  - id: SANS-JOUR
                    nom: S
                    typologiesProposees: [T]
                    effectifMin: 1
                    effectifMax: 3
                    horaires:
                      - mode: OUVERTURE
                        fenetres:
                          - { heureDebut: "14:00" }
                """, warnings);

        assertThat(stand.plages()).isNullOrEmpty();
        assertThat(warnings)
                .singleElement()
                .satisfies(avertissement ->
                        assertThat(avertissement).contains("SANS-JOUR").contains("horaires récurrents"));
    }

    /** A 3.x export without {@code schemaVersion} (version 1) walks the whole chain and finds its plages too. */
    @Test
    void aVersionOneFileRestoresItsRulesAsPlages() {
        ScenarioBinder.Bound bound = ScenarioBinder.bindWithWarnings(HEAD + """
                  - id: S
                    nom: S
                    typologiesProposees: [T]
                    effectifMin: 1
                    effectifMax: 3
                    horaires:
                      - mode: OUVERTURE
                        fenetres:
                          - { heureDebut: "10:00", heureFin: "12:00" }
                    indisponibilites:
                      - { date: 2026-07-09, heureDebut: "00:00", motif: Montage }
                """);

        StandDto stand = bound.scenario().stands().get(0);
        assertThat(stand.plages())
                .extracting(PlageStandDto::date, PlageStandDto::mode)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(LocalDate.of(2026, 7, 9), ModeHoraire.FERMETURE),
                        org.assertj.core.groups.Tuple.tuple(LocalDate.of(2026, 7, 8), ModeHoraire.OUVERTURE),
                        org.assertj.core.groups.Tuple.tuple(LocalDate.of(2026, 7, 10), ModeHoraire.OUVERTURE));
    }

    @Test
    void aVersionThreeDocumentIsLeftAlone() {
        ScenarioBinder.Bound bound = ScenarioBinder.bindWithWarnings("schemaVersion: 3\n" + HEAD + """
                  - id: S
                    nom: S
                    typologiesProposees: [T]
                    effectifMin: 1
                    effectifMax: 3
                    plages:
                      - { date: 2026-07-08, mode: FERMETURE, heureDebut: "09:00" }
                """);

        assertThat(bound.scenario().stands().get(0).plages()).hasSize(1);
        assertThat(bound.warnings()).isEmpty();
    }

    @Test
    void anUnreadableDateOfADatedWindowIsReportedUnderItsOwnPath() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> ScenarioBinder.bind("schemaVersion: 2\n" + HEAD + """
                  - id: S
                    nom: S
                    typologiesProposees: [T]
                    effectifMin: 1
                    effectifMax: 3
                    ouvertures:
                      - { date: pas-une-date, heureDebut: "14:00" }
                """))
                .isInstanceOf(ScenarioFormatException.class)
                .hasMessageContaining("plages");
    }
}
