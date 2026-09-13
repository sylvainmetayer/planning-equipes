package dev.sylvain.planning.service.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sylvain.planning.scenario.ScenarioBinder;
import dev.sylvain.planning.scenario.ScenarioValidator;
import dev.sylvain.planning.scenario.dto.CreneauDto;
import dev.sylvain.planning.scenario.dto.ScenarioDto;
import java.io.IOException;
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
 * What holds the ladder together as a set, cheaply enough for every run: each
 * file is valid, is played by a test, and says in its name what it contains.
 *
 * <p>The name is the index a reader uses to pick the rung that matches a case
 * — « 14j-35stands-132animateurs » — so a file regenerated with another roster
 * and not renamed would send them to the wrong one.</p>
 */
class ScenarioLadderCatalogTest {

    private static final Path TESTS = Path.of("src/test/java/dev/sylvain/planning/service/scenario");
    private static final Pattern FEASIBLE =
            Pattern.compile("gamme-(\\d{2})-(\\d+)j-(\\d+)stands-(\\d+)animateurs(-[a-z0-9-]+)?");
    private static final Pattern INFEASIBLE = Pattern.compile("gamme-(\\d{2})-infaisable-[a-z0-9-]+");

    static List<String> ladder() throws IOException {
        return ScenariosLivres.noms(ScenarioLadder.GAMME_PREFIX);
    }

    @Test
    void theRungsAreNumberedOneToThirtyWithoutAGap() throws IOException {
        assertThat(ladder())
                .extracting(name -> Integer.parseInt(name.substring("gamme-".length(), "gamme-".length() + 2)))
                .containsExactlyElementsOf(IntStream.rangeClosed(1, 30).boxed().toList());
    }

    @ParameterizedTest
    @MethodSource("ladder")
    void theFileIsValidAndItsNameSaysWhatItHolds(String name) {
        String yaml = ScenarioLadder.yaml(name);
        assertThat(ScenarioValidator.validate(yaml)).isEmpty();

        Matcher feasible = FEASIBLE.matcher(name);
        if (!feasible.matches()) {
            assertThat(name).matches(INFEASIBLE);
            return;
        }
        ScenarioDto scenario = ScenarioBinder.bind(yaml);
        assertThat(scenario.creneaux().stream().map(CreneauDto::date).distinct())
                .as("days")
                .hasSize(Integer.parseInt(feasible.group(2)));
        assertThat(scenario.stands()).as("stands").hasSize(Integer.parseInt(feasible.group(3)));
        assertThat(scenario.animateurs()).as("animateurs").hasSize(Integer.parseInt(feasible.group(4)));
    }

    @ParameterizedTest
    @MethodSource("ladder")
    void everyFileIsPlayedByATest(String name) throws IOException {
        String sources;
        try (Stream<Path> files = Files.list(TESTS)) {
            sources = files.filter(file -> file.getFileName().toString().matches("ScenarioLadder\\w+Test\\.java"))
                    .filter(file -> !file.getFileName().toString().equals("ScenarioLadderCatalogTest.java"))
                    .map(file -> {
                        try {
                            return Files.readString(file);
                        } catch (IOException e) {
                            throw new java.io.UncheckedIOException(e);
                        }
                    })
                    .reduce("", String::concat);
        }
        assertThat(sources).contains("\"" + name + "\"");
    }
}
