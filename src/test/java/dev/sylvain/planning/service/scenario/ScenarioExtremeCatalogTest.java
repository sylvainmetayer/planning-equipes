package dev.sylvain.planning.service.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sylvain.planning.scenario.ScenarioBinder;
import dev.sylvain.planning.scenario.ScenarioValidator;
import dev.sylvain.planning.scenario.dto.CreneauDto;
import dev.sylvain.planning.scenario.dto.ScenarioDto;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The extreme scenarios as a set, in the default run: each file is valid, is
 * played by a test, and — for the sized ones — says in its name how many days,
 * stands and animateurs it holds. The solves themselves are tagged
 * {@code scenario-extreme}; this is the cheap part that keeps the files honest
 * between two of those runs.
 */
class ScenarioExtremeCatalogTest {

    private static final Path TESTS = Path.of("src/test/java/dev/sylvain/planning/service/scenario");
    private static final Pattern SIZED =
            Pattern.compile("extreme-(\\d{2})-(\\d+)j-(\\d+)stands-(\\d+)animateurs(-[a-z0-9-]+)?");
    private static final Pattern NAMED = Pattern.compile("extreme-(\\d{2})-[a-z0-9-]+");

    static List<String> extremes() throws IOException {
        return ScenariosLivres.noms(ScenarioLadder.EXTREME_PREFIX);
    }

    @Test
    void theExtremesAreNumberedWithoutAGap() throws IOException {
        List<String> noms = extremes();
        assertThat(noms)
                .extracting(name -> Integer.parseInt(name.substring("extreme-".length(), "extreme-".length() + 2)))
                .containsExactlyElementsOf(
                        IntStream.rangeClosed(1, noms.size()).boxed().toList());
    }

    @ParameterizedTest
    @MethodSource("extremes")
    void theFileIsValidAndASizedNameSaysWhatItHolds(String name) {
        String yaml = ScenarioLadder.yaml(name);
        assertThat(ScenarioValidator.validate(yaml)).isEmpty();

        Matcher sized = SIZED.matcher(name);
        if (!sized.matches()) {
            assertThat(name).matches(NAMED);
            return;
        }
        ScenarioDto scenario = ScenarioBinder.bind(yaml);
        assertThat(scenario.creneaux().stream().map(CreneauDto::date).distinct())
                .as("days")
                .hasSize(Integer.parseInt(sized.group(2)));
        assertThat(scenario.stands()).as("stands").hasSize(Integer.parseInt(sized.group(3)));
        assertThat(scenario.animateurs()).as("animateurs").hasSize(Integer.parseInt(sized.group(4)));
    }

    @ParameterizedTest
    @MethodSource("extremes")
    void everyFileIsPlayedByATest(String name) throws IOException {
        String sources;
        try (Stream<Path> files = Files.list(TESTS)) {
            sources = files.filter(file -> file.getFileName().toString().matches("ScenarioExtreme\\w+Test\\.java"))
                    .filter(file -> !file.getFileName().toString().equals("ScenarioExtremeCatalogTest.java"))
                    .map(file -> {
                        try {
                            return Files.readString(file);
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    })
                    .reduce("", String::concat);
        }
        assertThat(sources).contains("\"" + name + "\"");
    }
}
