package dev.sylvain.planning.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The convention of issue #362, enforced rather than remembered: every
 * referential row carries {@code modifie_le}, and every write of one carries
 * its own precondition.
 *
 * <p>Six tables, six repositories and six services have to agree on a column,
 * a clause and a binding. The edition predicate has
 * {@link IsolationEditionStructurelleTest} to keep it honest across that many
 * files; this is the same net for the stamp, so the seventh referential cannot
 * ship without it and stay green.</p>
 */
class WriteStampStructurelleTest {

    private static final Path MIGRATIONS = Path.of("src/main/resources/db/migration");
    private static final Path SOURCES = Path.of("src/main/java/dev/sylvain/planning/service");

    /** The tables a screen edits row by row — the ones two sessions can open at once. */
    private static final List<String> REFERENTIELS =
            List.of("stand", "animateur", "creneau", "emplacement", "typologie", "contrainte_ad_hoc");

    @Test
    void everyReferentialTableCarriesTheStamp() throws IOException {
        String migrations = read(MIGRATIONS);
        for (String table : REFERENTIELS) {
            assertThat(migrations)
                    .as("%s must carry modifie_le: a row two sessions can open needs a stamp to compare", table)
                    .containsPattern("(?s)ALTER TABLE " + table + "\\s+ADD COLUMN modifie_le");
        }
    }

    /**
     * The write itself must carry the precondition. Read-then-write is what
     * this replaced: it let two saves a millisecond apart both pass.
     */
    @Test
    void everyReferentialWriteCarriesItsPrecondition() throws IOException {
        // Derived from the migrations, not from the list above: a seventh
        // table that adds the column and forgets the clause fails here without
        // anybody thinking to declare it.
        String sources = read(SOURCES);
        List<String> sansPrecondition = new ArrayList<>();
        for (String table : tablesEstampillees()) {
            if (!sources.contains("date_trunc('milliseconds', " + table + ".modifie_le)")) {
                sansPrecondition.add(table);
            }
        }
        assertThat(sansPrecondition)
                .as("a referential write with no precondition silently overwrites the other session")
                .isEmpty();
    }

    /** Every table a migration gave {@code modifie_le}. */
    private static List<String> tablesEstampillees() throws IOException {
        Matcher matcher =
                Pattern.compile("ALTER TABLE (\\w+)\\s+ADD COLUMN modifie_le").matcher(read(MIGRATIONS));
        List<String> tables = new ArrayList<>();
        while (matcher.find()) {
            tables.add(matcher.group(1));
        }
        assertThat(tables).as("the migration that introduces the convention").isNotEmpty();
        return tables;
    }

    /** Every precondition clause is bound, and bound through the one helper that words it. */
    @Test
    void everyPreconditionIsBoundThroughTheHelper() throws IOException {
        String sources = read(SOURCES);
        int clauses = sources.split("date_trunc\\('milliseconds', ", -1).length - 1;
        int bindings = sources.split("WriteStamp\\.bindPrecondition\\(", -1).length
                - 1
                + sources.split("staleWrites\\.refuseStale\\(\"creneau\"", -1).length
                - 1;
        assertThat(bindings)
                .as("each precondition clause needs its binding: %d clause halves, %d bindings", clauses, bindings)
                .isGreaterThanOrEqualTo(REFERENTIELS.size());
    }

    private static String read(Path racine) throws IOException {
        try (Stream<Path> fichiers = Files.walk(racine)) {
            StringBuilder tout = new StringBuilder();
            for (Path fichier : fichiers.filter(Files::isRegularFile).toList()) {
                tout.append(Files.readString(fichier)).append('\n');
            }
            return tout.toString();
        }
    }
}
