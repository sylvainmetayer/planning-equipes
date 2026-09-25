package dev.sylvain.planning.service.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.sylvain.planning.domain.ParametresLegaux;
import dev.sylvain.planning.domain.ParametresQualite;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.service.BusinessError;
import dev.sylvain.planning.service.EmptyReferenceData;
import dev.sylvain.planning.service.analyse.FeasibilityAnalyzer;
import dev.sylvain.planning.service.solve.PlanningService;
import java.io.IOException;
import java.util.List;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Reading a scenario file, with no database and no container: the reader is pure
 * and static, which is precisely what these exercise.
 *
 * <p>They target the one thing extracting it from {@link PlanningService}
 * actually changed — the fallback on the edition's own legal parameters when a
 * file pins none, now a {@link java.util.function.Supplier} rather than a direct
 * read of the service's field. The rest of the parser moved verbatim and stays
 * covered by {@code PlanningServiceScenarioAllerRetourTest} and the
 * scenario-lent classes.</p>
 */
class ScenarioYamlReaderTest {

    /** Recognisable at a glance: no {@link ParametresLegaux} default is this. */
    private static final int TELLTALE_REST_MINUTES = 999;

    /** {@code scenario.yml} carries no {@code parametresLegaux:} section, which is what makes it the fallback case. */
    private static final String WITHOUT_LEGAL_PARAMETERS = "scenario.yml";

    private static ParametresLegaux ofTheEdition() {
        ParametresLegaux parametres = new ParametresLegaux();
        parametres.setReposQuotidienMinimalMinutes(TELLTALE_REST_MINUTES);
        return parametres;
    }

    /**
     * The fallback takes the <b>edition's</b> parameters, not the class defaults.
     *
     * <p>This is the test that was missing, and its absence was invisible: the
     * repository's test double returns {@code new ParametresLegaux()}, so rewiring
     * the supplier to {@code ParametresLegaux::new} left the whole suite green —
     * the slow scenarios included. A scenario imported into an edition with tuned
     * parameters would then have solved, silently, with the defaults.</p>
     */
    @Test
    void aFileThatPinsNoLegalParametersFallsBackOnTheEditionsOwn() throws IOException {
        PlanningEvenement planning = ScenarioYamlReader.buildPlanning(
                ScenarioYamlReader.readScenario(ScenarioYamlReader.scenarioPath(WITHOUT_LEGAL_PARAMETERS)),
                ScenarioYamlReaderTest::ofTheEdition);

        assertThat(planning.getParametresLegaux())
                .singleElement()
                .extracting(ParametresLegaux::getReposQuotidienMinimalMinutes)
                .isEqualTo(TELLTALE_REST_MINUTES);
    }

    /**
     * And the fallback is consulted only then: a file that pins the section keeps
     * its own, which is what {@code parametresLegaux:} exists for.
     */
    @Test
    void aFileThatPinsItsOwnLegalParametersKeepsThem() {
        String yaml = """
                festival:
                  dateDebut: "2026-07-16"
                parametresLegaux:
                  reposQuotidienMinimalMinutes: 660
                creneaux:
                  - id: "1"
                    jour: 1
                    date: "2026-07-16"
                    heureDebut: "10:00:00"
                    heureFin: "18:00:00"
                stands:
                  - id: S1
                    nom: Stand
                    typologiesProposees: ["JEU"]
                    effectifMin: 1
                    effectifMax: 1
                animateurs:
                  - id: A1
                    prenom: Alice
                    nom: Martin
                    dateNaissance: "1990-01-01"
                    competences: {}
                """;

        ScenarioYamlReader.ScenarioImporte importe =
                ScenarioYamlReader.buildFromScenarioText(yaml, ScenarioYamlReaderTest::ofTheEdition);

        assertThat(importe.planning().getParametresLegaux())
                .singleElement()
                .extracting(ParametresLegaux::getReposQuotidienMinimalMinutes)
                .isEqualTo(660);
    }

    /**
     * A scenario is refused a fiche the form, MCP and the CSV import all refuse:
     * a blank nom is as missing as no nom. The file was the one way in, and a
     * fiche named by a single word is one nobody recognises on a planning.
     */
    @Test
    void anAnimateurWithABlankNameIsRefused() {
        String yaml = """
                festival:
                  dateDebut: "2026-07-16"
                creneaux:
                  - id: "1"
                    jour: 1
                    date: "2026-07-16"
                    heureDebut: "10:00:00"
                    heureFin: "18:00:00"
                stands:
                  - id: S1
                    nom: Stand
                    typologiesProposees: ["JEU"]
                    effectifMin: 1
                    effectifMax: 1
                animateurs:
                  - id: A1
                    prenom: Alice
                    nom: " "
                    dateNaissance: "1990-01-01"
                    competences: {}
                """;

        assertThatThrownBy(() -> ScenarioYamlReader.buildFromScenarioText(yaml, ParametresLegaux::new))
                .hasMessageContaining("Champ manquant: animateurs.nom");
    }

    /**
     * And the wiring holds end to end: {@link PlanningService} is what supplies the
     * fallback, and it must supply the <b>edition's</b> one.
     *
     * <p>The test above pins the reader's contract; this one pins the other half.
     * Without it, replacing {@code referenceDataService::getParametresLegaux} with
     * {@code ParametresLegaux::new} in the façade would leave everything green.</p>
     */
    @Test
    void theServiceHandsTheReaderTheEditionsOwnLegalParameters() {
        PlanningService planningService = new PlanningService(
                3L,
                2L,
                ParametresQualite.EMPLACEMENTS_DISTINCTS_PAR_JOUR_MAX_PAR_DEFAUT,
                new EditionWithTelltaleParameters(),
                new FeasibilityAnalyzer(),
                null,
                null,
                ConfigProvider.getConfig());

        PlanningEvenement planning = planningService.buildExample(WITHOUT_LEGAL_PARAMETERS);

        assertThat(planning.getParametresLegaux())
                .singleElement()
                .extracting(ParametresLegaux::getReposQuotidienMinimalMinutes)
                .isEqualTo(TELLTALE_REST_MINUTES);
    }

    /** An edition whose legal parameters look like no default. */
    private static final class EditionWithTelltaleParameters extends EmptyReferenceData {
        @Override
        public ParametresLegaux getParametresLegaux() {
            return ofTheEdition();
        }
    }

    /**
     * A missing supplier is a wiring mistake, not a fallback: without this check,
     * {@code Optional.orElseGet} would only notice on the day a file omits the
     * section — so never, in tests that all pin one.
     */
    @Test
    void aMissingSupplierIsRefusedAtTheDoor() {
        assertThatThrownBy(() ->
                        ScenarioYamlReader.buildFromScenarioText("festival:\n  dateDebut: \"2026-07-16\"\n", null))
                .isInstanceOf(NullPointerException.class);
    }

    /**
     * The scenario listing, which walks the classpath — the one place in the reader
     * where the extraction swapped {@code getClass()} for a class literal, and
     * which nothing covered.
     *
     * <p>One thing to know before adding a second {@code scenarios/} directory:
     * {@code getResource("scenarios")} returns the <b>first</b> match on the
     * classpath, not the union, so the second one would be invisible. That is
     * why every scenario — the hand-written fixtures, the ladder and the
     * extreme cases alike — lives in the single folder of {@code main}.</p>
     */
    @Test
    void everyListedScenarioIsAYamlFileAndTheListIsSorted() {
        List<String> scenarios = ScenarioYamlReader.listScenarios();

        assertThat(scenarios).isNotEmpty().isSorted();
        assertThat(scenarios).allSatisfy(name -> assertThat(name).matches(".+\\.(yaml|yml)"));
    }

    /**
     * Every listed scenario resolves to itself: the file-name whitelist the
     * reader applies must accept whatever the folder holds, or a file dropped
     * there would show in the dropdown and then be refused.
     */
    @Test
    void everyListedScenarioPassesThePathCheck() {
        assertThat(ScenarioYamlReader.listScenarios())
                .allSatisfy(name -> assertThat(
                                ScenarioYamlReader.checkedResourcePath(ScenarioYamlReader.scenarioPath(name)))
                        .isEqualTo(ScenarioYamlReader.SCENARIOS_DIR + "/" + name));
    }

    /** A name cannot climb out of the scenarios folder, by the name or by the path. */
    @ParameterizedTest
    @ValueSource(strings = {"../application.properties", "a/b.yaml", "..\\x.yaml", ".hidden.yaml", "..", "x\0.yaml"})
    void aNameThatLeavesTheScenariosFolderIsRefused(String name) {
        assertThatThrownBy(() -> ScenarioYamlReader.scenarioPath(name))
                .isInstanceOf(BusinessError.Invalid.class)
                .hasMessageContaining("Nom de scénario invalide");
        assertThatThrownBy(() -> ScenarioYamlReader.readScenario(ScenarioYamlReader.SCENARIOS_DIR + "/" + name))
                .isInstanceOf(BusinessError.Invalid.class);
    }

    /** The path handed to the reader must itself sit under the scenarios folder. */
    @ParameterizedTest
    @ValueSource(
            strings = {"application.properties", "db/migration/V1__init.sql", "scenarios/../application.properties"})
    void aPathOutsideTheScenariosFolderIsRefusedWhenRead(String path) {
        assertThatThrownBy(() -> ScenarioYamlReader.readScenario(path)).isInstanceOf(BusinessError.Invalid.class);
    }
}
