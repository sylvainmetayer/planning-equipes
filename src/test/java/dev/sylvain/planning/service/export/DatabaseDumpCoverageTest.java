package dev.sylvain.planning.service.export;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import dev.sylvain.planning.service.export.DatabaseDumpService;

/**
 * Every table this database holds is either dumped or deliberately not.
 *
 * <p>The audit of issue #392 found fifteen live tables outside
 * {@code DatabaseDumpService.TABLES} — among them the KPI history, the
 * published plan and the animateurs' own declarations. None of them was there
 * by decision; they had simply never been examined, because nothing forced
 * anyone to examine them. A table was added, and the dump did not notice.</p>
 *
 * <p>This test is what forces the examination. Adding a migration that creates
 * a table now fails here until the table is either put in {@code TABLES} or
 * named in {@code DELIBERATELY_NOT_DUMPED} — where the javadoc asks for a
 * reason. The decision may go either way; what it may no longer do is go by
 * default.</p>
 */
@QuarkusTest
class DatabaseDumpCoverageTest {

    /** Flyway's own bookkeeping: never dumped, never a business table. */
    private static final String FLYWAY_HISTORY = "flyway_schema_history";

    @Inject
    DataSource dataSource;

    @Test
    void everyLiveTableIsEitherDumpedOrDeliberatelyNot() throws SQLException {
        List<String> unclassified = new ArrayList<>();
        for (String table : liveTables()) {
            if (!DatabaseDumpService.TABLES.contains(table)
                    && !DatabaseDumpService.DELIBERATELY_NOT_DUMPED.contains(table)) {
                unclassified.add(table);
            }
        }

        assertThat(unclassified)
                .as("""
                        tables that exist but are neither dumped nor listed as deliberately \
                        excluded. Put each one in DatabaseDumpService.TABLES if restoring a \
                        dump should bring it back, or in DELIBERATELY_NOT_DUMPED with the \
                        reason it must not travel between instances.""")
                .isEmpty();
    }

    /**
     * The mirror image: a name that no longer matches a table is a leftover
     * from a dropped migration, and would make the dump fail on a table that
     * is not there.
     */
    @Test
    void noClassifiedTableHasSinceBeenDropped() throws SQLException {
        List<String> live = liveTables();
        List<String> ghosts = new ArrayList<>();
        for (String table : DatabaseDumpService.TABLES) {
            if (!live.contains(table)) {
                ghosts.add(table);
            }
        }
        for (String table : DatabaseDumpService.DELIBERATELY_NOT_DUMPED) {
            if (!live.contains(table)) {
                ghosts.add(table);
            }
        }

        assertThat(ghosts)
                .as("names classified in DatabaseDumpService that match no table any more")
                .isEmpty();
    }

    private List<String> liveTables() throws SQLException {
        List<String> tables = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery(
                        "SELECT table_name FROM information_schema.tables"
                                + " WHERE table_schema = 'public' AND table_type = 'BASE TABLE'"
                                + " ORDER BY table_name")) {
            while (rs.next()) {
                String name = rs.getString(1);
                if (!FLYWAY_HISTORY.equals(name)) {
                    tables.add(name);
                }
            }
        }
        return tables;
    }
}
