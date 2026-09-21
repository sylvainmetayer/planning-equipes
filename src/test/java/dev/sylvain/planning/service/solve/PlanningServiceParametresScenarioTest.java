package dev.sylvain.planning.service.solve;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.sylvain.planning.domain.ParametresLegaux;
import dev.sylvain.planning.domain.ParametresQualite;
import dev.sylvain.planning.domain.ParametresSolveur;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.service.EmptyReferenceData;
import dev.sylvain.planning.service.analyse.FeasibilityAnalyzer;
import dev.sylvain.planning.service.referentiel.ReferenceData;
import dev.sylvain.planning.service.scenario.ScenarioYamlReader;
import java.time.LocalTime;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.Test;

/**
 * Exercises the optional {@code parametresLegaux:}
 * / {@code parametresSolveur:} scenario sections: absent, a scenario keeps
 * depending on whatever is currently configured (unchanged behavior);
 * present, only the fields it names are overridden, everything else falls
 * back to the domain class's own defaults rather than to the (here DB-less)
 * ReferenceDataService.
 */
class PlanningServiceParametresScenarioTest {

    private static PlanningService service() {
        ReferenceData referenceDataService = new EmptyReferenceData();
        return new PlanningService(
                3L,
                2L,
                ParametresQualite.EMPLACEMENTS_DISTINCTS_PAR_JOUR_MAX_PAR_DEFAUT,
                referenceDataService,
                new FeasibilityAnalyzer(),
                null,
                null,
                ConfigProvider.getConfig());
    }

    @Test
    void scenarioSansSectionNeDefinitAucunParametre() {
        PlanningService service = service();

        assertThat(service.loadScenarioSections("scenario.yml").parametresLegaux())
                .isEmpty();
        assertThat(service.loadScenarioSections("scenario.yml").parametresSolveur())
                .isEmpty();
    }

    @Test
    void scenarioAvecSectionPartielleNeSurchargeQueLesChampsNommes() {
        PlanningService service = service();

        ParametresLegaux legaux = service.loadScenarioSections("scenario-parametres-optionnels.yaml")
                .parametresLegaux()
                .orElseThrow();
        assertThat(legaux.getReposQuotidienMinimalMinutes()).isEqualTo(500);
        // Fields the YAML does not mention: the defaults of the class, not those
        // of the database (absent here).
        assertThat(legaux.getDureeHebdomadaireMaxMinutes())
                .isEqualTo(ParametresLegaux.DUREE_HEBDOMADAIRE_MAX_MINUTES_PAR_DEFAUT);
        assertThat(legaux.getDureePauseMinutes()).isEqualTo(ParametresLegaux.DUREE_PAUSE_MINUTES_PAR_DEFAUT);
        // A scenario can pin the evening: left out of the DTO, the field was not
        // « not overridable » but silently reset to 20:00 on every import, and
        // every evening hour of the equity table moved with it.
        assertThat(legaux.getHeureDebutSoiree()).isEqualTo(LocalTime.of(22, 0));
        // The vacation ceiling: pinned by the file, and a field it leaves out
        // (the daily rest) still falls back on the class default above.
        assertThat(legaux.getDureeVacationMaxMinutes()).isEqualTo(250);
        // Regression: an unquoted HH:MM:SS scalar is read by SnakeYAML as a
        // sexagesimal Number (43830 = 12*3600 + 30*60), not a String — a naive
        // (String) cast throws ClassCastException instead of parsing it.
        assertThat(legaux.getCoupureRepasMidiDebut()).isEqualTo(LocalTime.of(12, 30));

        ParametresSolveur solveur = service.loadScenarioSections("scenario-parametres-optionnels.yaml")
                .parametresSolveur()
                .orElseThrow();
        assertThat(solveur.dureeResolutionSecondes()).isEqualTo(400);
    }

    @Test
    void construireExempleAppliqueLesParametresLegauxDuScenarioQuandPresents() {
        PlanningService service = service();

        PlanningEvenement evenement = service.buildExample("scenario-parametres-optionnels.yaml");

        assertThat(evenement.getParametresLegaux()).hasSize(1);
        assertThat(evenement.getParametresLegaux().get(0).getReposQuotidienMinimalMinutes())
                .isEqualTo(500);
    }

    /**
     * A missing or blank name (an import with no scenario selected) must fall
     * back on the default scenario, like {@code buildExample}: concatenated
     * as is, it used to aim at {@code scenarios/null}, or worse at the
     * {@code scenarios/} folder itself, whose listing parses as a plain YAML
     * string and broke the import with a ClassCastException.
     */
    @Test
    void nomDeScenarioAbsentOuVideRetombeSurLeScenarioParDefaut() {
        PlanningService service = service();

        for (String nom : new String[] {null, "", "   "}) {
            assertThat(service.loadScenarioSections(nom).parametresLegaux().isPresent())
                    .isEqualTo(service.loadScenarioSections(ScenarioYamlReader.DEFAULT_SCENARIO)
                            .parametresLegaux()
                            .isPresent());
            assertThat(service.loadScenarioSections(nom).parametresSolveur()).isNotNull();
            assertThat(service.loadScenarioSections(nom).typologies())
                    .isEqualTo(service.loadScenarioSections(ScenarioYamlReader.DEFAULT_SCENARIO)
                            .typologies());
        }
    }

    @Test
    void nomDeScenarioAvecComposantDeCheminEstRejete() {
        PlanningService service = service();

        assertThatThrownBy(() -> service.loadScenarioSections("../application.properties"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Nom de scénario invalide");
    }
}
