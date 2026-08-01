package dev.sylvain.planning.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.NiveauCompetence;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.domain.TypologieJeu;

/**
 * Exercises {@link PlanningService#construireScenarioYaml} directly
 * (package-private, no database needed), the reverse of what
 * {@code chargerScenarioYaml} parses. Checks the produced text is valid YAML
 * carrying the same shape as the hand-authored scenario files, in particular
 * that times like "09:00" stay strings instead of being reinterpreted as
 * YAML 1.1 sexagesimal numbers.
 */
class PlanningServiceScenarioExportTest {

    private final Stand stand = new Stand("STAND-A", "Stand A", Set.of(TypologieJeu.STRATEGIE), 1, 2, false);
    private final Creneau creneau = new Creneau(1L, 1, LocalDate.of(2026, 8, 14), LocalTime.of(9, 0), LocalTime.of(13, 0));
    private final Animateur animateur = new Animateur("A1", "Alice", "Referente", LocalDate.of(2000, 1, 1), false);

    @Test
    @SuppressWarnings("unchecked")
    void exportedYamlRoundTripsThroughAParser() {
        animateur.setCompetences(Map.of(TypologieJeu.STRATEGIE, NiveauCompetence.REFERENT));
        animateur.setJoursIndisponibles(Set.of(LocalDate.of(2026, 8, 15)));
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
                .containsEntry("effectifMax", 2);
        assertThat((List<String>) stands.get(0).get("typologiesProposees")).containsExactly("STRATEGIE");

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
        PlanningService planningService = new PlanningService(3L, 2L, referenceDataService, new FeasibilityAnalyzer());

        assertThatThrownBy(planningService::exporterScenarioYaml).isInstanceOf(IllegalStateException.class);
    }
}
