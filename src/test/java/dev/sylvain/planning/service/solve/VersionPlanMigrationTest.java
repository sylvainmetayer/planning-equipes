package dev.sylvain.planning.service.solve;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
 * V143 turns {@code plan_snapshot} into {@code version_plan}. Played on a
 * database filled the way 3.x filled it — the migrations up to V142 in a schema
 * of their own, then the rows and the V143 file itself in a transaction rolled
 * back afterwards: no version is lost, none changes content, and the rows that
 * pointed at one still do.
 */
@QuarkusTest
class VersionPlanMigrationTest {

    private static final String SCHEMA = "migration_v143";
    private static final String V143 = "db/migration/V143__version_plan.sql";

    /** Everything a version is, but its origin: what must not move. */
    private static final String CONTENT = "SELECT id || '|' || libelle || '|' || score || '|' || nombre_affectations"
            + " || '|' || cree_le || '|' || coalesce(publie_le::text, '-') || '|' || md5(contenu::text) || '|'"
            + " || md5(coalesce(kpi::text, '-')) || '|' || md5(coalesce(consignes::text, '-'))";

    @BeforeAll
    static void migrateUpToV142() throws SQLException {
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
                .target("142")
                .load()
                .migrate();
    }

    @AfterAll
    static void dropSchema() throws SQLException {
        drop();
    }

    @Test
    void theThreeKindsOfSnapshotBecomeVersionsWithNoLossAndTheirOrigin() throws Exception {
        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            try (Statement sql = connection.createStatement()) {
                sql.execute("SET LOCAL search_path TO " + SCHEMA);
                String edition = text(sql, "SELECT min(id) FROM edition");
                String insert = "INSERT INTO plan_snapshot (edition_id, libelle, automatique, score,"
                        + " nombre_affectations, contenu, kpi, consignes, publie_le) VALUES ('" + edition + "', ";
                sql.execute(insert + "'manuelle', false, '0hard', 1, '[{\"a\":1}]', '{\"k\":1}', '[]', NULL)");
                sql.execute(insert + "'auto 1', true, '1hard', 2, '[{\"a\":2}]', NULL, NULL, NULL)");
                sql.execute(insert + "'auto 2', true, '2hard', 3, '[{\"a\":3}]', NULL, '[]', NULL)");
                sql.execute(insert + "'publiee 1', false, '3hard', 4, '[{\"a\":4}]', '{\"k\":4}', '[]', now())");
                sql.execute(insert + "'publiee 2', false, '4hard', 5, '[{\"a\":5}]', NULL, NULL,"
                        + " now() + interval '1 day')");
                // Both flags at once: no code path writes it, and the publication wins.
                sql.execute(insert + "'auto publiee', true, '5hard', 6, '[]', NULL, NULL, now())");
                sql.execute("INSERT INTO animateur (edition_id, id, prenom, nom, date_naissance) VALUES ('" + edition
                        + "', 'A1', 'P', 'N', DATE '1990-01-01')");
                long publiee = Long.parseLong(text(sql, "SELECT id FROM plan_snapshot WHERE libelle = 'publiee 1'"));
                sql.execute("INSERT INTO publication_destinataire (edition_id, snapshot_id, animateur_id,"
                        + " nom_affiche, statut) VALUES ('" + edition + "', " + publiee + ", 'A1', 'P N', 'ENVOYE')");

                List<String> avant = rows(sql, CONTENT + " FROM plan_snapshot ORDER BY id");
                assertThat(avant).hasSize(6);

                migrate(sql);

                assertThat(rows(sql, CONTENT + " FROM version_plan ORDER BY id"))
                        .as("count and content, row for row")
                        .isEqualTo(avant);
                assertThat(rows(sql, "SELECT libelle || '|' || origine FROM version_plan ORDER BY id"))
                        .containsExactly(
                                "manuelle|MANUELLE",
                                "auto 1|AVANT_CALCUL",
                                "auto 2|AVANT_CALCUL",
                                "publiee 1|PUBLIEE",
                                "publiee 2|PUBLIEE",
                                "auto publiee|PUBLIEE");
                assertThat(text(sql, "SELECT to_regclass('plan_snapshot')")).isNull();
                assertThat(text(
                                sql,
                                "SELECT (SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace"
                                        + " WHERE n.nspname = '" + SCHEMA + "' AND c.relname LIKE 'plan\\_snapshot%')"
                                        + " + (SELECT count(*) FROM pg_constraint k JOIN pg_namespace n"
                                        + " ON n.oid = k.connamespace WHERE n.nspname = '" + SCHEMA + "'"
                                        + " AND k.conname LIKE 'plan\\_snapshot%')"))
                        .as("no index, sequence or constraint still named after the old table")
                        .isEqualTo("0");
                assertThat(text(
                                sql,
                                "SELECT count(*) FROM information_schema.columns WHERE table_schema = '" + SCHEMA
                                        + "' AND table_name = 'version_plan' AND column_name = 'automatique'"))
                        .isEqualTo("0");

                // The trace of a publication still points at it, and still goes with it.
                assertThat(text(sql, "SELECT count(*) FROM publication_destinataire"))
                        .isEqualTo("1");
                // The sequence carried on: a new version does not collide with the old ones.
                sql.execute("INSERT INTO version_plan (edition_id, libelle, contenu) VALUES ('" + edition
                        + "', 'nouvelle', '[]')");
                assertThat(text(sql, "SELECT origine FROM version_plan WHERE libelle = 'nouvelle'"))
                        .isEqualTo("MANUELLE");

                assertThatThrownBy(() -> sql.execute(
                                "UPDATE version_plan SET origine = 'MANUELLE'" + " WHERE libelle = 'publiee 2'"))
                        .isInstanceOf(SQLException.class)
                        .hasMessageContaining("version_plan_origine_connue");
            } finally {
                connection.rollback();
            }
        }
    }

    private static void migrate(Statement sql) throws Exception {
        try (InputStream file = Thread.currentThread().getContextClassLoader().getResourceAsStream(V143)) {
            assertThat(file).as(V143).isNotNull();
            sql.execute(new String(file.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    private static String text(Statement sql, String query) throws SQLException {
        try (ResultSet rs = sql.executeQuery(query)) {
            rs.next();
            return rs.getString(1);
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
