package dev.sylvain.planning.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/**
 * Every foreign key of the migrated schema has an index on its referencing
 * side (ADR 0083), with <b>no exception list</b>: an unindexed key is never a
 * choice, only an oversight, and a review does not see it — six keys added
 * after the audit that found the first batch were all missing theirs.
 *
 * <p>PostgreSQL indexes the referenced side for free and never the referencing
 * one, so deleting a parent row scans the whole child table once per deleted
 * row. A key is served by a primary key, a unique index or a plain index whose
 * leading columns are exactly the key's, in the key's order; a partial index
 * serves it only when its predicate is nothing but {@code col IS NOT NULL} on
 * the key's columns, which is all the integrity lookup ({@code col = $1})
 * implies. The same columns in another order, or a partial index with any
 * other predicate (the uniques guarding a pending state), do not.</p>
 */
@QuarkusTest
class ForeignKeyIndexStructuralTest {

    private static final Pattern NOT_NULL_ONLY =
            Pattern.compile("[(\\s]*\\w+ IS NOT NULL[)\\s]*(AND[(\\s]*\\w+ IS NOT NULL[)\\s]*)*");

    @Inject
    DataSource dataSource;

    @Test
    void everyForeignKeyHasASupportingIndex() throws SQLException {
        List<String> sansIndex = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet cles = statement.executeQuery("""
                        SELECT c.oid, c.conrelid::regclass::text, c.conname,
                               (SELECT array_agg(a.attname::text ORDER BY k.ord)
                                  FROM unnest(c.conkey) WITH ORDINALITY k(n, ord)
                                  JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = k.n)
                          FROM pg_constraint c
                         WHERE c.contype = 'f'
                         ORDER BY 2, 3""")) {
            while (cles.next()) {
                List<String> colonnes =
                        Arrays.asList((String[]) cles.getArray(4).getArray());
                if (!hasSupportingIndex(connection, cles.getString(2), colonnes)) {
                    sansIndex.add(cles.getString(2) + "." + cles.getString(3) + " " + colonnes);
                }
            }
        }
        assertThat(sansIndex).as("""
                        foreign keys without an index on their referencing side — add \
                        CREATE INDEX on the key's columns, in order, in the migration that adds the key \
                        (no exception list exists, by design)""").isEmpty();
    }

    private static boolean hasSupportingIndex(Connection connection, String table, List<String> colonnes)
            throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet index = statement.executeQuery("""
                        SELECT (SELECT array_agg(a.attname::text ORDER BY k.ord)
                                  FROM unnest(i.indkey::int2[]) WITH ORDINALITY k(n, ord)
                                  JOIN pg_attribute a ON a.attrelid = i.indrelid AND a.attnum = k.n
                                 WHERE k.ord <= %d AND k.ord <= i.indnkeyatts),
                               pg_get_expr(i.indpred, i.indrelid)
                          FROM pg_index i
                         WHERE i.indrelid = '%s'::regclass AND i.indisvalid""".formatted(colonnes.size(), table))) {
            while (index.next()) {
                if (index.getArray(1) == null) {
                    continue;
                }
                List<String> tete = Arrays.asList((String[]) index.getArray(1).getArray());
                String predicat = index.getString(2);
                if (tete.equals(colonnes) && (predicat == null || partialPredicateIsNotNullOnly(predicat, colonnes))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean partialPredicateIsNotNullOnly(String predicat, List<String> colonnes) {
        return NOT_NULL_ONLY.matcher(predicat).matches()
                && Pattern.compile("(\\w+) IS NOT NULL")
                        .matcher(predicat)
                        .results()
                        .allMatch(m -> colonnes.contains(m.group(1)));
    }
}
