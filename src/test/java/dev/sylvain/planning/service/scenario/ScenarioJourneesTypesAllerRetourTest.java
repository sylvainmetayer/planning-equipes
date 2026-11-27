package dev.sylvain.planning.service.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.Gabarit;
import dev.sylvain.planning.domain.ParametresLegaux;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.domain.VacationType;
import dev.sylvain.planning.service.referentiel.JourneesTypesMaterialisation.Affectation;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The {@code gabarits:} section, written by the export and read by the
 * import: the same joint {@code PlanningServiceScenarioAllerRetourTest} holds
 * for the entity sections.
 */
class ScenarioJourneesTypesAllerRetourTest {

    private static final LocalDate LUNDI = LocalDate.of(2027, 7, 12);

    @Test
    void laSectionEcriteParLExportEstRelueParLImport() {
        Gabarit nocturne = new Gabarit(
                7L,
                "Nocturne",
                List.of(
                        new VacationType(LocalTime.of(14, 0), LocalTime.of(20, 0)),
                        new VacationType(LocalTime.of(20, 0), LocalTime.MIDNIGHT)));
        Gabarit normal =
                new Gabarit(8L, "Jour normal", List.of(new VacationType(LocalTime.of(12, 0), LocalTime.of(13, 0))));
        String yaml = ScenarioYamlWriter.buildScenarioYaml(new ScenarioYamlWriter.ScenarioExport(
                List.of(animateur()),
                List.of(stand()),
                List.of(creneau()),
                null,
                List.of(),
                List.of(),
                null,
                null,
                Map.of(),
                Map.of(),
                List.of(),
                List.of(nocturne, normal),
                List.of(
                        new Affectation(LUNDI, 8L),
                        new Affectation(LUNDI.plusDays(1), 7L),
                        new Affectation(LUNDI.plusDays(2), 8L))));

        assertThat(yaml).contains("gabarits:").contains("Nocturne").doesNotContain("couverturePause");

        ScenarioYamlReader.ScenarioImporte relu = ScenarioYamlReader.buildFromScenarioText(yaml, ParametresLegaux::new);

        ScenarioYamlReader.GabaritsScenario section = relu.sections().gabarits().orElseThrow();
        assertThat(section.gabarits()).extracting(Gabarit::getNom).containsExactly("Nocturne", "Jour normal");
        assertThat(section.gabarits().get(0).getVacations()).hasSize(2);
        assertThat(section.gabarits().get(1).getVacations()).hasSize(1);
        Long idNocturne = section.gabarits().get(0).getId();
        Long idNormal = section.gabarits().get(1).getId();
        assertThat(section.calendrier())
                .containsExactlyInAnyOrder(
                        new Affectation(LUNDI, idNormal),
                        new Affectation(LUNDI.plusDays(1), idNocturne),
                        new Affectation(LUNDI.plusDays(2), idNormal));
    }

    @Test
    void unFichierSansLaSectionNEnAPas() {
        String yaml = ScenarioYamlWriter.buildScenarioYaml(
                List.of(animateur()), List.of(stand()), List.of(creneau()), List.of());

        assertThat(yaml).doesNotContain("gabarits");
        assertThat(ScenarioYamlReader.buildFromScenarioText(yaml, ParametresLegaux::new)
                        .sections()
                        .gabarits())
                .isEmpty();
    }

    private static Creneau creneau() {
        return new Creneau(1L, 1, LUNDI, LocalTime.of(9, 0), LocalTime.of(12, 0));
    }

    private static Stand stand() {
        Stand stand = new Stand();
        stand.setId("S1");
        stand.setNom("Stand");
        stand.setEffectifMin(1);
        stand.setEffectifMax(1);
        stand.setTypologiesProposees(Set.of("JEU"));
        return stand;
    }

    private static Animateur animateur() {
        Animateur animateur = new Animateur();
        animateur.setId("A1");
        animateur.setPrenom("Alice");
        animateur.setNom("Martin");
        animateur.setDateNaissance(LocalDate.of(1990, 1, 1));
        return animateur;
    }
}
