package dev.sylvain.planning.service.edition;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.junit.QuarkusTest;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * V155 derives the phase of each edition from its 3.x state (ADR 0115).
 * Played on a database filled the way 3.x filled it — the migrations up to
 * V152 (the last one before this file on its branch) in a schema of their own, then the rows and the V155 file itself in a
 * transaction rolled back afterwards.
 */
@QuarkusTest
class PhaseMigrationTest {

    private static final String SCHEMA = "migration_v155";
    private static final String V155 = "db/migration/V155__phase_de_l_edition.sql";

    @BeforeAll
    static void migrateUpToV152() throws SQLException {
        drop();
        Config config = ConfigProvider.getConfig();
        Flyway.configure()
                .dataSource(
                        config.getValue("quarkus.datasource.jdbc.url", String.class),
                        config.getValue("quarkus.datasource.username", String.class),
                        config.getValue("quarkus.datasource.password", String.class))
                .schemas(SCHEMA)
                .createSchemas(true)
                .locations("classpath:db/migration")
                .target("152")
                .load()
                .migrate();
    }

    @AfterAll
    static void dropSchema() throws SQLException {
        drop();
    }

    @Test
    void thePhaseIsDerivedFromThePublicationAndTheFreeze() throws Exception {
        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            try (Statement sql = connection.createStatement()) {
                sql.execute("SET LOCAL search_path TO " + SCHEMA);
                sql.execute("INSERT INTO edition (id, nom) VALUES ('E-RIEN', 'Rien'), ('E-GEL', 'Gel'),"
                        + " ('E-PUB', 'Publiée'), ('E-PUB-GEL', 'Publiée, créneaux figés'),"
                        + " ('E-PUB-COMP', 'Publiée, compétences figées')");
                sql.execute("INSERT INTO version_plan (edition_id, libelle, contenu, origine, publie_le) VALUES"
                        + " ('E-RIEN', 'Manuelle', '{}', 'MANUELLE', NULL),"
                        + " ('E-PUB', 'Publication', '{}', 'PUBLIEE', '2026-07-01T10:00:00Z'),"
                        + " ('E-PUB', 'Publication', '{}', 'PUBLIEE', '2026-07-03T10:00:00Z'),"
                        + " ('E-PUB-GEL', 'Publication', '{}', 'PUBLIEE', '2026-07-01T10:00:00Z'),"
                        + " ('E-PUB-COMP', 'Publication', '{}', 'PUBLIEE', '2026-07-01T10:00:00Z')");
                sql.execute("INSERT INTO gel_referentiel (edition_id, famille, fige_le) VALUES"
                        + " ('E-GEL', 'STANDS', '2026-06-01T10:00:00Z'),"
                        + " ('E-GEL', 'COMPETENCES', '2026-06-02T10:00:00Z'),"
                        + " ('E-PUB-GEL', 'CRENEAUX', '2026-06-01T10:00:00Z'),"
                        + " ('E-PUB-COMP', 'COMPETENCES', '2026-06-01T10:00:00Z')");

                migrate(sql);

                assertThat(rows(
                                sql,
                                "SELECT id || '|' || phase || '|' || structure_debloquee FROM edition"
                                        + " WHERE id LIKE 'E-%' ORDER BY id"))
                        .containsExactly(
                                "E-GEL|CALCUL|false",
                                "E-PUB|PUBLIEE|true",
                                "E-PUB-COMP|PUBLIEE|true",
                                "E-PUB-GEL|PUBLIEE|false",
                                "E-RIEN|PREPARATION|false");
                assertThat(rows(
                                sql,
                                "SELECT id || '|' || to_char(phase_depuis AT TIME ZONE 'UTC', 'YYYY-MM-DD') FROM"
                                        + " edition WHERE id IN ('E-GEL', 'E-PUB') ORDER BY id"))
                        .as("since the first freeze, since the last publication")
                        .containsExactly("E-GEL|2026-06-01", "E-PUB|2026-07-03");
                // The 3.x rows stay, read by nothing, for a way back.
                assertThat(rows(sql, "SELECT count(*) FROM gel_referentiel")).containsExactly("4");
            } finally {
                connection.rollback();
            }
        }
    }

    private static void migrate(Statement sql) throws Exception {
        try (InputStream file = Thread.currentThread().getContextClassLoader().getResourceAsStream(V155)) {
            assertThat(file).as(V155).isNotNull();
            sql.execute(new String(file.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    private static List<String> rows(Statement sql, String query) throws SQLException {
        List<String> rows = new ArrayList<>();
        try (ResultSet rs = sql.executeQuery(query)) {
            while (rs.next()) {
                rows.add(rs.getString(1));
            }
        }
        return rows;
    }

    private static Connection connect() throws SQLException {
        Config config = ConfigProvider.getConfig();
        return DriverManager.getConnection(
                config.getValue("quarkus.datasource.jdbc.url", String.class),
                config.getValue("quarkus.datasource.username", String.class),
                config.getValue("quarkus.datasource.password", String.class));
    }

    private static void drop() throws SQLException {
        try (Connection connection = connect();
                Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
        }
    }
}
