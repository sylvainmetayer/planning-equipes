package dev.sylvain.planning.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/**
 * Every column that may carry a business id without a foreign key saying so
 * is known, and says what it holds (ADR 0050).
 *
 * <p>A foreign key is what makes a renumbering safe: V100 rewrote the declared
 * ones by their definitions, and nothing about them could be forgotten. The
 * columns that hold an id <em>without</em> one — inside a JSONB, in a key
 * concatenated with a date, in a journal line — are the ones a migration
 * forgets in silence: nothing fails, and weeks later the published plan reads
 * as entirely changed, or every reminder goes out a second time. V100 had to
 * find them by reading the code.</p>
 *
 * <p>This test is what makes the next one loud. A text column named {@code id}
 * or {@code …_id} that is neither a primary key nor covered by a foreign key,
 * and every JSONB column, must be listed below with what it holds — so that
 * whoever next renumbers or moves an id reads the list instead of the code.</p>
 */
@QuarkusTest
class IdColumnsInventoryStructuralTest {

    /** {@code table.column} → what it holds, and why no foreign key protects it. */
    static final Map<String, String> SANS_CLE_ETRANGERE = Map.ofEntries(
            Map.entry(
                    "declaration_disponibilite.souhaits",
                    "typologie ids, one per line — a list, which a foreign key cannot cover"),
            Map.entry(
                    "demande_echange.stand_cible_id",
                    "the stand a directed swap aims at; no key since V46, the swap outliving a deleted stand"),
            Map.entry(
                    "journal_action.acteur_id",
                    "an animateur id when the actor is one, « admin » or « mcp » otherwise"),
            Map.entry(
                    "journal_action.entite_id",
                    "an id of the referential column entite names — any of them, hence no key; "
                            + "a deleted row's line keeps naming it"),
            Map.entry("kpi_historique.edition_id", "an edition id; the curves outlive a deleted edition"),
            Map.entry("kpi_historique.kpi", "figures only, no id"),
            Map.entry("planning_resolution.dosage", "rule names and weights, no id"),
            Map.entry(
                    "notification_planifiee.animateur_id",
                    "the animateur a scheduled send was for; the ledger outlives the fiche"),
            Map.entry(
                    "notification_planifiee.cle",
                    "animateurId|date, animateurId|publicationInstant, or a swap request UUID — the dedup key"),
            Map.entry("plan_snapshot.consignes", "dates, hours and motifs of the consignes, no id"),
            Map.entry(
                    "plan_snapshot.contenu",
                    "standId and animateurId of every seat — what the stability of the published plan joins on"),
            Map.entry("plan_snapshot.kpi", "figures only, no id"),
            Map.entry(
                    "publication_destinataire.animateur_id",
                    "who a publication was sent to; the trace outlives the fiche"),
            Map.entry("publication_destinataire.changements", "sentences, no id"),
            Map.entry("publication_destinataire.demandes", "sentences, no id"),
            Map.entry("solver_job.perimetre", "animateurIds and standIds of a queued solve, replayed at startup"));

    @Inject
    DataSource dataSource;

    @Test
    void everyIdColumnWithoutAForeignKeyIsClassified() throws SQLException {
        List<String> nonClassees = new ArrayList<>();
        for (String colonne : idColumnsWithoutForeignKey()) {
            if (!SANS_CLE_ETRANGERE.containsKey(colonne)) {
                nonClassees.add(colonne);
            }
        }
        assertThat(nonClassees).as("""
                        columns that may hold a business id and that no foreign key covers. \
                        Add each one to SANS_CLE_ETRANGERE saying what it holds — and, if it \
                        holds an id, make sure whatever renumbers or moves ids rewrites it.""").isEmpty();
    }

    /** The mirror image: a name that no longer matches a column is a leftover of a dropped one. */
    @Test
    void noClassifiedColumnHasSinceDisappeared() throws SQLException {
        List<String> vivantes = idColumnsWithoutForeignKey();
        assertThat(new TreeSet<>(SANS_CLE_ETRANGERE.keySet()))
                .as("columns classified here that the schema no longer holds")
                .allMatch(vivantes::contains);
    }

    private List<String> idColumnsWithoutForeignKey() throws SQLException {
        List<String> colonnes = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery("""
                        WITH cles AS (
                            SELECT c.contype, c.conrelid::regclass::text AS table_nom, a.attname AS colonne
                            FROM pg_constraint c
                            JOIN LATERAL unnest(c.conkey) AS k(n) ON TRUE
                            JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = k.n
                            WHERE c.contype IN ('f', 'p'))
                        SELECT c.table_name || '.' || c.column_name
                        FROM information_schema.columns c
                        JOIN information_schema.tables t
                          ON t.table_schema = c.table_schema AND t.table_name = c.table_name
                        WHERE c.table_schema = 'public'
                          AND t.table_type = 'BASE TABLE'
                          AND c.table_name <> 'flyway_schema_history'
                          AND (c.data_type = 'jsonb'
                               OR (c.data_type IN ('text', 'character varying')
                                   AND (c.column_name = 'id' OR c.column_name LIKE '%\\_id'
                                        OR c.column_name IN ('typologie', 'cle', 'souhaits'))))
                          AND NOT EXISTS (SELECT 1 FROM cles k WHERE k.contype = 'f'
                                          AND k.table_nom = c.table_name AND k.colonne = c.column_name)
                          AND NOT (c.column_name = 'id'
                                   AND EXISTS (SELECT 1 FROM cles k WHERE k.contype = 'p'
                                               AND k.table_nom = c.table_name AND k.colonne = 'id'))
                        ORDER BY 1""")) {
            while (rs.next()) {
                colonnes.add(rs.getString(1));
            }
        }
        return colonnes;
    }
}
