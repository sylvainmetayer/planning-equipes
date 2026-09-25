package dev.sylvain.planning.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.regex.Pattern;

/**
 * The one place a business id is drawn from (ADR 0050): the application
 * generates every id, the user chooses none.
 *
 * <p>Each referential numbers its rows <b>per edition</b> — {@code A1} is the
 * first animateur of every edition — from a counter row of
 * {@code compteur_identifiant} incremented by an {@code UPSERT … RETURNING}.
 * The row lock that statement takes is what keeps two concurrent creations
 * from drawing the same number, without the retry loop a {@code MAX + 1} would
 * need; and since the counter only ever grows, a number freed by a deletion
 * is never handed out again — a journal line or a snapshot naming it keeps
 * naming the row it named.</p>
 *
 * <p>Editions themselves draw from one global sequence
 * ({@link #nextEditionId}), since they are what the other counters are
 * partitioned by.</p>
 */
@ApplicationScoped
public class IdGenerator {

    /** The referentials numbered per edition, and the letter their ids start with. */
    public enum Kind {
        ANIMATEUR("A"),
        STAND("S"),
        TYPOLOGIE("T"),
        EMPLACEMENT("L"),
        CONTRAINTE("C");

        private final String prefix;
        private final Pattern shape;

        Kind(String prefix) {
            this.prefix = prefix;
            // At most 18 digits, the numbers a long holds — the bound V100
            // renumbered by, so an id too long to count is never « generated ».
            this.shape = Pattern.compile(prefix + "[1-9][0-9]{0,17}");
        }

        public String prefix() {
            return prefix;
        }

        /** Whether {@code id} has the shape of an id this kind generates — {@code S12} for a stand. */
        public boolean hasGeneratedShape(String id) {
            return id != null && shape.matcher(id).matches();
        }
    }

    /** Prefix of every edition id. */
    public static final String EDITION_PREFIX = "E";

    private static final Pattern EDITION_SHAPE = Pattern.compile("[Ee][1-9][0-9]*");

    private final JdbcEditionScope scope;

    @Inject
    public IdGenerator(JdbcEditionScope scope) {
        this.scope = scope;
    }

    /** Draws the next id of {@code kind} in the current edition, in a transaction of its own. */
    public String next(Kind kind) {
        return scope.writeAndReturn("Failed to draw a new " + kind + " id", connection -> next(connection, kind));
    }

    /**
     * Draws the next id of {@code kind} in the current edition, inside the
     * caller's transaction: the counter row stays locked until it commits, and
     * a rollback gives the number back.
     */
    public String next(Connection connection, Kind kind) throws SQLException {
        try (PreparedStatement ps = scope.prepareScoped(connection, """
                INSERT INTO compteur_identifiant (edition_id, entite, dernier)
                VALUES (?, ?, 1)
                ON CONFLICT (edition_id, entite)
                DO UPDATE SET dernier = compteur_identifiant.dernier + 1
                RETURNING dernier""")) {
            ps.setString(2, kind.name());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return kind.prefix() + rs.getLong(1);
            }
        }
    }

    /** The last number {@code kind} handed out in the current edition, 0 when none yet. */
    public long lastNumber(Kind kind) {
        return scope.read("Failed to read the " + kind + " counter", connection -> {
            try (PreparedStatement ps = scope.prepareScoped(
                    connection, "SELECT dernier FROM compteur_identifiant WHERE edition_id = ? AND entite = ?")) {
                ps.setString(2, kind.name());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getLong(1) : 0L;
                }
            }
        });
    }

    /**
     * Marks every number up to {@code number} as handed out in the current
     * edition — for an import that keeps ids its file already carries, which
     * the counter must never draw again. Never lowers the counter.
     */
    public void raise(Kind kind, long number) {
        scope.write("Failed to raise the " + kind + " counter", connection -> {
            try (PreparedStatement ps = scope.prepareScoped(connection, """
                    INSERT INTO compteur_identifiant (edition_id, entite, dernier)
                    VALUES (?, ?, ?)
                    ON CONFLICT (edition_id, entite)
                    DO UPDATE SET dernier = GREATEST(compteur_identifiant.dernier, EXCLUDED.dernier)""")) {
                ps.setString(2, kind.name());
                ps.setLong(3, number);
                ps.executeUpdate();
            }
        });
    }

    /** The number of an id of {@code kind}'s shape — 12 for {@code S12}; -1 for any other id. */
    public static long numberOf(Kind kind, String id) {
        if (!kind.hasGeneratedShape(id)) {
            return -1;
        }
        return Long.parseLong(id.substring(kind.prefix().length()));
    }

    /** Draws the next edition id, {@code E} followed by a number. */
    public String nextEditionId() {
        return scope.read("Failed to draw a new edition id", connection -> {
            try (PreparedStatement ps = connection.prepareStatement("SELECT nextval('edition_numero_seq')");
                    ResultSet rs = ps.executeQuery()) {
                rs.next();
                return EDITION_PREFIX + rs.getLong(1);
            }
        });
    }

    /**
     * Whether {@code text} could be mistaken for an edition id, case aside.
     * An edition may not be <em>named</em> that way: the MCP {@code edition}
     * argument accepts an id or a name, and tries the id first.
     */
    public static boolean looksLikeEditionId(String text) {
        return text != null && EDITION_SHAPE.matcher(text.trim()).matches();
    }
}
