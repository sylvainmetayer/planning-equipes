package dev.sylvain.planning.service.journal;

import dev.sylvain.planning.service.JdbcEditionScope;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.sql.Array;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The {@code journal_action} table: append-only, scoped to its edition, read
 * newest first.
 *
 * <p>Plain JDBC through {@link JdbcEditionScope} like every other repository
 * here — the project ships no ORM — and the field is named {@code scope}
 * because the isolation scan anchors on {@code scope.prepareScoped(}.</p>
 */
@ApplicationScoped
public class JournalActionRepository {

    /** Hard ceiling on one page of history: a screen, not an export. */
    static final int LIMITE_MAX = 500;

    private static final String SQL_VARCHAR = "varchar";

    /**
     * One page of the history, every filter optional: a {@code null} bound or
     * selection is no filter at all, so one literal statement serves every
     * combination the screen asks for — an {@code IN} list or a {@code WHERE}
     * assembled by hand would be SQL the isolation scan cannot read.
     *
     * <p>The period is {@code ]since ; until]}: « since » is exclusive, as
     * {@link #countSince} reads it, so a page asked « since the last solve »
     * holds exactly the lines that count reported. The cursor is the
     * {@code (survenu_le, id)} pair of the last line already shown, in the
     * order the page is read in.</p>
     */
    private static final String SELECT_PAGE = """
            SELECT id, survenu_le, acteur, acteur_id, action, entite, entite_id,
            champs, resultat, statut
            FROM journal_action
            WHERE edition_id = ?
            AND (?::varchar[] IS NULL OR action = ANY(?::varchar[]))
            AND (NOT ?::boolean OR resultat = 'SUCCES')
            AND survenu_le > COALESCE(?::timestamptz, '-infinity')
            AND survenu_le <= COALESCE(?::timestamptz, 'infinity')
            AND (survenu_le, id) < (COALESCE(?::timestamptz, 'infinity'), COALESCE(?::bigint, 9223372036854775807))
            ORDER BY survenu_le DESC, id DESC
            LIMIT ?""";

    /** Where the line a cursor names sits in time; nothing when it has aged out. */
    private static final String SELECT_CURSOR = """
            SELECT survenu_le FROM journal_action WHERE edition_id = ? AND id = ?""";

    private static final String SELECT_SINCE = """
            SELECT id, survenu_le, acteur, acteur_id, action, entite, entite_id,
            champs, resultat, statut
            FROM journal_action
            WHERE edition_id = ? AND survenu_le > ? AND resultat = 'SUCCES' AND action = ANY(?)
            ORDER BY survenu_le DESC, id DESC
            LIMIT ?""";

    private final JdbcEditionScope scope;

    @Inject
    public JournalActionRepository(JdbcEditionScope scope) {
        this.scope = scope;
    }

    /**
     * Appends one line. Never fails the caller: an action that happened must
     * not be undone because its trace could not be written — the same contract
     * as the KPI row and the automatic snapshot. The exception surfaces in the
     * log, where an operator can see the journal is failing.
     */
    public void append(EntreeJournal entree) {
        scope.write("Failed to record an action in the history", connection -> {
            try (PreparedStatement ps = scope.prepareScoped(connection, """
                    INSERT INTO journal_action (edition_id, survenu_le, acteur, acteur_id, action,
                    entite, entite_id, champs, resultat, statut)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")) {
                ps.setTimestamp(2, Timestamp.from(entree.survenuLe()));
                ps.setString(3, entree.acteur().name());
                ps.setString(4, entree.acteurId());
                ps.setString(5, entree.action());
                ps.setString(6, entree.entite());
                ps.setString(7, entree.entiteId());
                ps.setString(8, entree.champs().isEmpty() ? null : String.join(",", entree.champs()));
                ps.setString(9, entree.resultat().name());
                if (entree.statut() == null) {
                    ps.setNull(10, Types.INTEGER);
                } else {
                    ps.setInt(10, entree.statut());
                }
                ps.executeUpdate();
            }
        });
    }

    /** The most recent lines of the current edition, newest first. */
    public List<EntreeJournal> list(int limite) {
        return page(HistoryFilter.ALL, null, limite);
    }

    /**
     * One page of the lines {@code filter} keeps, newest first, starting just
     * after the line {@code before} — a keyset cursor, so a page is as cheap
     * at the end of the retention as at its start, and a line written while
     * the reader pages is never shown twice.
     *
     * <p>A cursor naming no line of this edition answers an empty page rather
     * than a refusal: the line it named can only have left through the
     * nightly purge, which drops by date — so everything older went with it,
     * and « nothing more » is the exact answer.</p>
     */
    public List<EntreeJournal> page(HistoryFilter filter, Long before, int limite) {
        if (filter.codes() != null && filter.codes().isEmpty()) {
            return List.of();
        }
        int plafond = Math.clamp(limite, 1, LIMITE_MAX);
        return scope.read("Failed to read the history", connection -> {
            Timestamp cursor = null;
            if (before != null) {
                try (PreparedStatement ps = scope.prepareScoped(connection, SELECT_CURSOR)) {
                    ps.setLong(2, before);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) {
                            return List.of();
                        }
                        cursor = rs.getTimestamp("survenu_le");
                    }
                }
            }
            Array codes = filter.codes() == null
                    ? null
                    : connection.createArrayOf(SQL_VARCHAR, filter.codes().toArray());
            try (PreparedStatement ps = scope.prepareScoped(connection, SELECT_PAGE)) {
                setArray(ps, 2, codes);
                setArray(ps, 3, codes);
                ps.setBoolean(4, filter.successOnly());
                setInstant(ps, 5, filter.since());
                setInstant(ps, 6, filter.until());
                ps.setTimestamp(7, cursor);
                if (cursor == null) {
                    ps.setNull(8, Types.BIGINT);
                } else {
                    ps.setLong(8, before);
                }
                ps.setInt(9, plafond);
                try (ResultSet rs = ps.executeQuery()) {
                    List<EntreeJournal> entrees = new ArrayList<>();
                    while (rs.next()) {
                        entrees.add(read(rs));
                    }
                    return entrees;
                }
            }
        });
    }

    private static void setArray(PreparedStatement ps, int index, Array value) throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.ARRAY);
        } else {
            ps.setArray(index, value);
        }
    }

    private static void setInstant(PreparedStatement ps, int index, Instant value) throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.TIMESTAMP_WITH_TIMEZONE);
        } else {
            ps.setTimestamp(index, Timestamp.from(value));
        }
    }

    /**
     * How many times each of {@code codes} succeeded since {@code depuis}, by
     * action code. Counted by the database rather than by paging through the
     * lines: the figure the solver screen shows is exact, whatever the volume.
     *
     * <p>The selection travels as one array parameter ({@code = ANY(?)}) like
     * {@code ReferenceUsageRepository} does, so the statement stays a literal
     * — an {@code IN} list assembled by hand would be SQL the isolation scan
     * cannot read.</p>
     */
    public Map<String, Integer> countSince(Instant depuis, Collection<String> codes) {
        if (codes.isEmpty()) {
            return Map.of();
        }
        return scope.read("Failed to count the changes since the last solve", connection -> {
            Array bound = connection.createArrayOf(SQL_VARCHAR, codes.toArray());
            try (PreparedStatement ps = scope.prepareScoped(connection, """
                    SELECT action, COUNT(*) AS nombre
                    FROM journal_action
                    WHERE edition_id = ? AND survenu_le > ? AND resultat = 'SUCCES' AND action = ANY(?)
                    GROUP BY action""")) {
                ps.setTimestamp(2, Timestamp.from(depuis));
                ps.setArray(3, bound);
                try (ResultSet rs = ps.executeQuery()) {
                    Map<String, Integer> comptes = new LinkedHashMap<>();
                    while (rs.next()) {
                        comptes.put(rs.getString("action"), rs.getInt("nombre"));
                    }
                    return comptes;
                }
            }
        });
    }

    /** The most recent of those same lines, newest first. */
    public List<EntreeJournal> listSince(Instant depuis, Collection<String> codes, int limite) {
        if (codes.isEmpty()) {
            return List.of();
        }
        int plafond = Math.clamp(limite, 1, LIMITE_MAX);
        return scope.read("Failed to read the changes since the last solve", connection -> {
            Array bound = connection.createArrayOf(SQL_VARCHAR, codes.toArray());
            try (PreparedStatement ps = scope.prepareScoped(connection, SELECT_SINCE)) {
                ps.setTimestamp(2, Timestamp.from(depuis));
                ps.setArray(3, bound);
                ps.setInt(4, plafond);
                try (ResultSet rs = ps.executeQuery()) {
                    List<EntreeJournal> entrees = new ArrayList<>();
                    while (rs.next()) {
                        entrees.add(read(rs));
                    }
                    return entrees;
                }
            }
        });
    }

    /**
     * Drops what is older than {@code cutoff}, across every edition.
     *
     * <p>Deliberately not edition-scoped: the purge is a housekeeping sweep
     * run by the nightly job, which has no edition of its own, and scoping it
     * would leave the editions nobody visits growing forever. Run through a
     * plain statement rather than {@link JdbcEditionScope#prepareScoped}, and
     * named in {@code EXCEPTIONS_ASSUMEES} for that reason.
     *
     * <p><b>It scans.</b> {@code idx_journal_action_recent} leads on
     * {@code edition_id} — it serves the screen's read, not this sweep, and
     * V70's comment claiming otherwise is wrong (a migration already applied
     * is not rewritten, so the correction lives here). That is a deliberate
     * trade rather than an oversight: a second index would be maintained on
     * every write, and writes are this table's steady state, while the sweep
     * runs once a night over ninety days at most.
     *
     * @return how many lines were dropped
     */
    public int purgeBefore(Instant cutoff) {
        return scope.writeAndReturn("Failed to purge the history", connection -> {
            try (PreparedStatement ps =
                    connection.prepareStatement("DELETE FROM journal_action WHERE survenu_le < ?")) {
                ps.setTimestamp(1, Timestamp.from(cutoff));
                return ps.executeUpdate();
            }
        });
    }

    private static EntreeJournal read(ResultSet rs) throws SQLException {
        String champs = rs.getString("champs");
        int statut = rs.getInt("statut");
        return new EntreeJournal(
                rs.getLong("id"),
                rs.getTimestamp("survenu_le").toInstant(),
                Acteur.valueOf(rs.getString("acteur")),
                rs.getString("acteur_id"),
                rs.getString("action"),
                rs.getString("entite"),
                rs.getString("entite_id"),
                champs == null || champs.isBlank() ? List.of() : Arrays.asList(champs.split(",")),
                EntreeJournal.Resultat.valueOf(rs.getString("resultat")),
                rs.wasNull() ? null : statut);
    }
}
