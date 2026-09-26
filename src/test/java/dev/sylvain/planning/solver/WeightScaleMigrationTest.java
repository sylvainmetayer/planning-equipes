package dev.sylvain.planning.solver;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sylvain.planning.solver.ConstraintCatalog.ConstraintDefinition;
import dev.sylvain.planning.solver.ConstraintCatalog.Niveau;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The migration that multiplied the stored weights by five (ADR 0057) names
 * the rules it rescales one by one, as the catalogue stood that day. A rule it
 * forgot would keep its old weight in every edition and weigh five times less
 * than the others, with nothing on screen to say so: this test holds the list
 * against the catalogue, without a database.
 */
class WeightScaleMigrationTest {

    private static final Pattern NAMES = Pattern.compile("WHERE nom IN \\(([^)]*)\\)");
    private static final Pattern NAME = Pattern.compile("'([^']+)'");

    /**
     * Medium or soft rules added to the catalogue after the migration: they
     * were born on the new scale, and the migration rightly ignores them.
     */
    private static final Set<String> ADDED_AFTER_MIGRATION = Set.of();

    @Test
    void rescalesEveryDosedRuleAndNoHardOne() throws IOException {
        List<Set<String>> lists = namesPerStatement();

        assertThat(lists).as("the weights and their history, rescaled together").hasSize(2);
        assertThat(lists.get(1)).as("the history follows the same list").isEqualTo(lists.get(0));
        Set<String> dosed = ConstraintCatalog.definitions().stream()
                .filter(definition -> definition.niveau() != Niveau.HARD)
                .map(ConstraintDefinition::name)
                .filter(name -> !ADDED_AFTER_MIGRATION.contains(name))
                .collect(Collectors.toCollection(TreeSet::new));
        assertThat(lists.get(0))
                .as("every medium or soft rule of the catalogue, and nothing else")
                .isEqualTo(dosed);
    }

    private static List<Set<String>> namesPerStatement() throws IOException {
        Path migration;
        try (Stream<Path> files = Files.list(Path.of("src/main/resources/db/migration"))) {
            migration = files.filter(file -> file.getFileName().toString().startsWith("V108__"))
                    .findFirst()
                    .orElseThrow();
        }
        Matcher statement = NAMES.matcher(Files.readString(migration));
        List<Set<String>> lists = new ArrayList<>();
        while (statement.find()) {
            Matcher name = NAME.matcher(statement.group(1));
            Set<String> names = new TreeSet<>();
            while (name.find()) {
                names.add(name.group(1));
            }
            lists.add(names);
        }
        return lists;
    }
}
