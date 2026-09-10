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
            assertThat(dto.festival())
                    .as(scenario.getFileName() + " : section festival")
                    .isNotNull();
            assertThat(dto.animateurs())
                    .as(scenario.getFileName() + " : animateurs")
                    .isNotEmpty();
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
        ScenarioDto dto = ScenarioBinder.bind(Files.readString(Path.of("src/main/resources/scenarios/scenario.yml")));

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

    /**
     * A year typed where a date was expected is refused, not read as an epoch.
     *
     * <p>Binding through {@code convertValue} used to turn SnakeYAML's date
     * into a number of milliseconds, which made {@code dateNaissance: 1990}
     * indistinguishable from a date and bound it to <b>1970-01-01</b> — handing
     * the legal constraints a 56-year-old where a minor had been declared. The
     * hand-written reader refused it; the binder does now too.</p>
     */
    @Test
    void unEntierNEstPasUneDate() throws IOException {
        String scenario = Files.readString(Path.of("src/main/resources/scenarios/scenario.yml"))
                .replaceFirst("dateNaissance: [0-9-]+", "dateNaissance: 1990");

        assertThatThrownBy(() -> ScenarioBinder.bind(scenario))
                .isInstanceOf(ScenarioFormatException.class)
                .hasMessageContaining("2026-08-17");
    }

    /**
     * And the same for an hour, for the reason {@link ScenarioYaml} exists:
     * {@code 9:30} used to resolve to the number 570 and be read as 00:09:30.
     */
    @Test
    void uneHeureSurDeuxPartiesEstLueCommeElleEstEcrite() throws IOException {
        String scenario = Files.readString(Path.of("src/main/resources/scenarios/scenario.yml"))
                .replaceFirst("heureDebut: \"?[0-9:]+\"?", "heureDebut: 9:30");

        assertThat(ScenarioBinder.bind(scenario).creneaux().get(0).heureDebut())
                .isEqualTo(java.time.LocalTime.of(9, 30));
    }

    /**
     * The binder carries its own alias limit, and it is not the reader's: a
     * test that goes through the reader would leave this one uncovered, and
     * the two would drift apart on the day one is raised.
     */
    @Test
    void unDocumentAvecPlusDeCinquanteAliasSeLie() throws IOException {
        String scenario = Files.readString(Path.of("src/main/resources/scenarios/scenario.yml"));
        StringBuilder added = new StringBuilder();
        for (int i = 0; i < 60; i++) {
            added.append("  - id: ALIAS")
                    .append(i)
                    .append(System.lineSeparator())
                    .append("    email: alias")
                    .append(i)
                    .append("@example.test")
                    .append(System.lineSeparator())
                    .append("    prenom: Alias")
                    .append(System.lineSeparator())
                    .append("    nom: Test")
                    .append(System.lineSeparator())
                    .append("    dateNaissance: 2000-01-01")
                    .append(System.lineSeparator())
                    .append("    manager: false")
                    .append(System.lineSeparator())
                    .append("    competences:")
                    .append(System.lineSeparator())
                    .append("      STRATEGIE: AUTONOME")
                    .append(System.lineSeparator())
                    .append("    joursIndisponibles: ")
                    .append(i == 0 ? "&jours []" : "*jours")
                    .append(System.lineSeparator());
        }
        // Added at the head of the existing list: what SnakeYAML anchors and
        // then aliases is one shared list instance, not a count of animateurs.
        String withAliases = scenario.replace(
                "animateurs:" + System.lineSeparator(), "animateurs:" + System.lineSeparator() + added);

        assertThat(ScenarioBinder.bind(withAliases).animateurs()).hasSize(63);
    }

    /**
     * {@code decoupageAuto:} means "slice on import" by its presence. Four
     * spellings, and the one that matters most is the absent key: Jackson
     * fills a missing record component with the deserialiser's null value
     * unless told otherwise, and told otherwise it was not, for one run of
     * the differential test — every scenario read as asking to be sliced.
     */
    @Test
    void decoupageAutoIsReadByPresenceNotByContent() throws IOException {
        String scenario = Files.readString(Path.of("src/main/resources/scenarios/scenario.yml"));
        assertThat(scenario).doesNotContain("decoupageAuto");

        assertThat(ScenarioBinder.bind(scenario).decoupageAuto()).as("absent").isNull();
        assertThat(ScenarioBinder.bind(scenario + "\ndecoupageAuto: false\n").decoupageAuto())
                .as("false")
                .isNull();
        assertThat(ScenarioBinder.bind(scenario + "\ndecoupageAuto:\n").decoupageAuto())
                .as("bare")
                .isNotNull();
        assertThat(ScenarioBinder.bind(scenario + "\ndecoupageAuto: {}\n").decoupageAuto())
                .as("empty object")
                .isNotNull();
        assertThat(ScenarioBinder.bind(scenario + "\ndecoupageAuto:\n  groupeSourceNom: A\n")
                        .decoupageAuto()
                        .groupeSourceNom())
                .as("historical fields")
                .isEqualTo("A");
        assertThatThrownBy(() -> ScenarioBinder.bind(scenario + "\ndecoupageAuto:\n  grouppe: A\n"))
                .isInstanceOf(ScenarioFormatException.class)
                .hasMessageContaining("grouppe");
    }

    private static List<Path> bundledScenarios() throws IOException {
        try (Stream<Path> files = Files.list(Path.of("src/main/resources/scenarios"))) {
            return files.filter(path -> path.toString().matches(".*\\.ya?ml"))
                    .sorted()
                    .toList();
        }
    }
}
