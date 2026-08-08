package dev.sylvain.planning.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.Test;

import dev.sylvain.planning.domain.PlanningFestival;

/**
 * {@link PlanningService#construireDepuisTexteScenario}: the "Importer un
 * fichier" button on the Scénarios page uploads a scenario YAML file (same
 * shape as {@code src/main/resources/scenarios/*.yaml}) instead of naming a
 * bundled one — this must parse an uploaded file's raw text into the exact
 * same result {@code construireExemple}/{@code chargerParametresXScenario}
 * produce for a classpath scenario, and turn a malformed file into a
 * readable {@link IllegalArgumentException} rather than a raw parser
 * exception.
 */
class PlanningServiceScenarioDepuisTexteTest {

    private static PlanningService service() {
        ReferenceDataService referenceDataService = new ReferenceDataService();
        referenceDataService.init();
        return new PlanningService(3L, 2L, referenceDataService, new FeasibilityAnalyzer(),
                ConfigProvider.getConfig());
    }

    private static String scenarioYamlText(String fileName) {
        try (InputStream inputStream = PlanningServiceScenarioDepuisTexteTest.class.getClassLoader()
                .getResourceAsStream("scenarios/" + fileName)) {
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    void construitLeMemePlanningQueConstruireExemple() {
        PlanningService service = service();
        String yaml = scenarioYamlText("scenario.yml");

        PlanningFestival depuisTexte = service.construireDepuisTexteScenario(yaml).planning();
        PlanningFestival depuisNom = service.construireExemple("scenario.yml");

        assertThat(depuisTexte.getAnimateurs()).hasSameSizeAs(depuisNom.getAnimateurs());
        assertThat(depuisTexte.getPostes()).hasSameSizeAs(depuisNom.getPostes());
        assertThat(depuisTexte.getDateDebutFestival()).isEqualTo(depuisNom.getDateDebutFestival());
    }

    @Test
    void appliqueLesParametresOptionnelsDuFichierQuandPresents() {
        PlanningService service = service();
        String yaml = scenarioYamlText("scenario-parametres-optionnels.yaml");

        PlanningService.ScenarioImporte importe = service.construireDepuisTexteScenario(yaml);

        assertThat(importe.parametresLegaux()).isPresent();
        assertThat(importe.parametresLegaux().orElseThrow().getReposQuotidienMinimalMinutes()).isEqualTo(500);
        assertThat(importe.parametresDecoupage()).isPresent();
        assertThat(importe.parametresSolveur()).isPresent();
        assertThat(importe.parametresSolveur().orElseThrow().getDureeResolutionSecondes()).isEqualTo(400);
    }

    @Test
    void rejetteUnFichierVideAvecUnMessageLisible() {
        PlanningService service = service();

        assertThatThrownBy(() -> service.construireDepuisTexteScenario(""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("vide");
        assertThatThrownBy(() -> service.construireDepuisTexteScenario(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejetteUnYamlMalformeAvecUnMessageLisible() {
        PlanningService service = service();

        assertThatThrownBy(() -> service.construireDepuisTexteScenario("festival: [unclosed"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("YAML invalide");
    }

    @Test
    void rejetteUnScenarioAvecUneSectionManquanteAvecUnMessageLisible() {
        PlanningService service = service();

        assertThatThrownBy(() -> service.construireDepuisTexteScenario("festival:\n  dateDebut: 2026-07-01\n"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("Scénario invalide");
    }
}
