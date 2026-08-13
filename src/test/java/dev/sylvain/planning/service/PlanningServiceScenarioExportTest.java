package dev.sylvain.planning.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.IndisponibiliteStand;
import dev.sylvain.planning.domain.NiveauCompetence;
import dev.sylvain.planning.domain.NiveauEffort;
import dev.sylvain.planning.domain.OuvertureStand;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;

/**
 * Exercises {@link PlanningService#construireScenarioYaml} directly
 * (package-private, no database needed), the reverse of what
 * {@code chargerScenarioYaml} parses. Checks the produced text is valid YAML
 * carrying the same shape as the hand-authored scenario files, in particular
 * that times like "09:00" stay strings instead of being reinterpreted as
 * YAML 1.1 sexagesimal numbers.
 */
class PlanningServiceScenarioExportTest {

    private final Stand stand = new Stand("STAND-A", "Stand A", Set.of("STRATEGIE"), 1, 2, false);
    private final Creneau creneau = new Creneau(1L, 1, LocalDate.of(2026, 8, 14), LocalTime.of(9, 0), LocalTime.of(13, 0));
    private final Animateur animateur = new Animateur("A1", "Alice", "Referente", LocalDate.of(2000, 1, 1), false);

    @Test
    @SuppressWarnings("unchecked")
    void exportedYamlRoundTripsThroughAParser() {
        animateur.setCompetences(Map.of("STRATEGIE", NiveauCompetence.REFERENT));
        animateur.setJoursIndisponibles(Set.of(LocalDate.of(2026, 8, 15)));
        stand.setNiveauEffort(NiveauEffort.EPUISANT);
        stand.setIndisponibilites(List.of(
                new IndisponibiliteStand(1L, LocalDate.of(2026, 8, 14), LocalTime.of(14, 0), LocalTime.of(16, 0), "Pause")));
        // A date clearly unrelated to the créneau's day (2026-08-14) and the day
        // after (2026-08-15, which Creneau#segmentsOuvertsMinutes also treats as
        // relevant for a créneau crossing into it) — an ouverture on either would
        // switch the stand to closed-by-default for this créneau's day and starve
        // construirePostes of a poste to build below.
        stand.setOuvertures(List.of(
                new OuvertureStand(2L, LocalDate.of(2026, 8, 20), LocalTime.of(20, 0), LocalTime.of(23, 0), null)));
        List<PosteAffectation> postes = PlanningService.construirePostes(List.of(stand), List.of(creneau));

        String yaml = PlanningService.construireScenarioYaml(List.of(animateur), List.of(stand), List.of(creneau), postes);
        Map<String, Object> parsed = new Yaml().load(yaml);

        assertThat(parsed.get("festival")).isInstanceOfSatisfying(Map.class,
                festival -> assertThat(festival.get("dateDebut")).isEqualTo("2026-08-14"));

        List<Map<String, Object>> creneaux = (List<Map<String, Object>>) parsed.get("creneaux");
        assertThat(creneaux).hasSize(1);
        assertThat(creneaux.get(0)).containsEntry("id", 1)
                .containsEntry("heureDebut", "09:00")
                .containsEntry("heureFin", "13:00");

        List<Map<String, Object>> stands = (List<Map<String, Object>>) parsed.get("stands");
        assertThat(stands).hasSize(1);
        assertThat(stands.get(0)).containsEntry("id", "STAND-A")
                .containsEntry("effectifMin", 1)
                .containsEntry("effectifMax", 2)
                .containsEntry("niveauEffort", "EPUISANT");
        assertThat((List<String>) stands.get(0).get("typologiesProposees")).containsExactly("STRATEGIE");

        List<Map<String, Object>> indisponibilites = (List<Map<String, Object>>) stands.get(0).get("indisponibilites");
        assertThat(indisponibilites).hasSize(1);
        assertThat(indisponibilites.get(0)).containsEntry("date", "2026-08-14")
                .containsEntry("heureDebut", "14:00")
                .containsEntry("heureFin", "16:00")
                .containsEntry("motif", "Pause");

        List<Map<String, Object>> ouvertures = (List<Map<String, Object>>) stands.get(0).get("ouvertures");
        assertThat(ouvertures).hasSize(1);
        assertThat(ouvertures.get(0)).containsEntry("date", "2026-08-20")
                .containsEntry("heureDebut", "20:00")
                .containsEntry("heureFin", "23:00")
                .containsEntry("motif", null);

        List<Map<String, Object>> animateurs = (List<Map<String, Object>>) parsed.get("animateurs");
        assertThat(animateurs).hasSize(1);
        assertThat(animateurs.get(0)).containsEntry("id", "A1")
                .containsEntry("dateNaissance", "2000-01-01");
        assertThat((Map<String, String>) animateurs.get(0).get("competences")).containsEntry("STRATEGIE", "REFERENT");
        assertThat((List<String>) animateurs.get(0).get("joursIndisponibles")).containsExactly("2026-08-15");

        List<Map<String, Object>> postesYaml = (List<Map<String, Object>>) parsed.get("postes");
        assertThat(postesYaml).hasSize(1);
        assertThat(postesYaml.get(0)).containsEntry("standId", "STAND-A")
                .containsEntry("creneauId", 1)
                .containsEntry("animateurId", null);
    }

    @Test
    void exportingWithoutReferenceDataFails() {
        ReferenceDataService referenceDataService = new ReferenceDataService();
        referenceDataService.init();
        PlanningService planningService = new PlanningService(3L, 2L, referenceDataService, new FeasibilityAnalyzer(),
                ConfigProvider.getConfig());

        assertThatThrownBy(planningService::exporterScenarioYaml).isInstanceOf(IllegalStateException.class);
    }
}
