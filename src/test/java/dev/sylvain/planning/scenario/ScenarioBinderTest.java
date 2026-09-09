package dev.sylvain.planning.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.sylvain.planning.scenario.dto.ScenarioDto;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The binder accepts every scenario the application ships, and refuses what
 * the hand-written parser used to swallow.
 */
class ScenarioBinderTest {

    @Test
    void everyBundledScenarioBinds() throws IOException {
        List<Path> scenarios = bundledScenarios();
        assertThat(scenarios).as("les scénarios livrés doivent être trouvés").isNotEmpty();

        for (Path scenario : scenarios) {
            ScenarioDto dto = ScenarioBinder.bind(Files.readString(scenario));
            assertThat(dto.festival()).as(scenario.getFileName() + " : section festival").isNotNull();
            assertThat(dto.animateurs()).as(scenario.getFileName() + " : animateurs").isNotEmpty();
            assertThat(dto.stands()).as(scenario.getFileName() + " : stands").isNotEmpty();
        }
    }

    /**
     * The coercions of YAML 1.1, on the file that carries them: an unquoted
     * date resolves to a {@code java.util.Date} and an unquoted hour to a
     * sexagesimal number. Neither reaches a plain date deserialiser intact.
     */
    @Test
    void yamlOneOneScalarsAreAccepted() throws IOException {
        ScenarioDto dto = ScenarioBinder.bind(
                Files.readString(Path.of("src/main/resources/scenarios/scenario.yml")));

        assertThat(dto.festival().dateDebut()).isNotNull();
        assertThat(dto.creneaux().get(0).heureDebut()).isNotNull();
        assertThat(dto.animateurs().get(0).dateNaissance()).isNotNull();
    }

    /**
     * The behaviour change this unification carries: a mistyped key used to be
     * dropped in silence, so a scenario could pin {@code parametresSolveur} and
     * see the solve run on the database's values instead.
     */
    @Test
    void anUnknownKeyIsRefusedByName() throws IOException {
        String scenario = Files.readString(Path.of("src/main/resources/scenarios/scenario.yml"));

        assertThatThrownBy(() -> ScenarioBinder.bind(scenario + "\nparametresSolveurs: {}\n"))
                .isInstanceOf(ScenarioFormatException.class)
                .hasMessageContaining("parametresSolveurs")
                .hasMessageContaining("la racine du fichier");
    }

    @Test
    void anUnknownKeyDeepInTheFileNamesItsPath() throws IOException {
        String scenario = Files.readString(Path.of("src/main/resources/scenarios/scenario.yml"))
                .replaceFirst("(?m)^  - id: A1$", "  - id: A1\n    prenomm: coquille");

        assertThatThrownBy(() -> ScenarioBinder.bind(scenario))
                .isInstanceOf(ScenarioFormatException.class)
                .hasMessageContaining("prenomm")
                .hasMessageContaining("animateurs");
    }

    @Test
    void aDocumentThatIsNotAMappingSaysSo() {
        assertThatThrownBy(() -> ScenarioBinder.bind("- juste une liste\n"))
                .isInstanceOf(ScenarioFormatException.class)
                .hasMessageContaining("mapping attendu");
    }

    private static List<Path> bundledScenarios() throws IOException {
        try (Stream<Path> files = Files.list(Path.of("src/main/resources/scenarios"))) {
            return files.filter(path -> path.toString().matches(".*\\.ya?ml")).sorted().toList();
        }
    }
}
