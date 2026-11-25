package dev.sylvain.planning.service.mcp;

import dev.sylvain.planning.config.ConfigMcp;
import dev.sylvain.planning.service.BusinessError;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;

/**
 * The MCP API key, rotated from the screen instead of the server (ADR 0097).
 *
 * <p>Two sources, one rule. The key of the configuration
 * ({@code PLANNING_MCP_API_KEY}) works as long as no key was ever generated;
 * the first generation replaces it, the old key staying valid for the overlap
 * the administrator chose (zero: at once). Each generation retires the previous
 * generated key the same way. Only the SHA-256 of a generated key is stored:
 * the key itself is shown once, in the answer of {@link #rotate}.</p>
 *
 * <p>The state is read on every {@code /mcp} request, so it is kept in memory
 * and dropped on every write - one instance, as everywhere else in this
 * application. Plain JDBC, instance-wide: not routed through
 * {@code JdbcEditionScope}, like {@code webhook}.</p>
 */
@ApplicationScoped
public class McpKeyService {

    /** The longest overlap an administrator can leave the old key: a week. */
    public static final long MAX_OVERLAP_MINUTES = 7 * 24 * 60;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final DataSource dataSource;

    private final ConfigMcp config;

    private volatile State state;

    @Inject
    public McpKeyService(DataSource dataSource, ConfigMcp config) {
        this.dataSource = dataSource;
        this.config = config;
    }

    /** Where the key in force comes from, for the screen. */
    public enum Origin {
        NONE,
        CONFIG,
        GENERATED
    }

    /** What the screen shows: the origin, and when the key being retired stops working. */
    public record Status(Origin origin, Instant generatedAt, Instant previousExpiresAt) {}

    /**
     * What a rotation returns, once.
     *
     * @param key the new key, in clear - never stored, never returned again
     * @param previousExpiresAt when the previous key stops working, {@code null} when there was none
     */
    public record Rotation(String key, Instant previousExpiresAt) {}

    private record Row(String origin, Instant createdAt, Instant expiresAt) {}

    private record State(Map<String, Row> byFingerprint, boolean anyGenerated) {}

    /** Whether {@code presented} opens the MCP transport right now. Blocking: reads the state on first use. */
    public boolean accepts(String presented) {
        if (presented == null || presented.isEmpty()) {
            return false;
        }
        State current = state();
        Row row = current.byFingerprint.get(fingerprint(presented));
        if (row != null) {
            return row.expiresAt == null || row.expiresAt.isAfter(Instant.now());
        }
        // Not a known fingerprint: only the configured key can still be right, and
        // only while nothing generated has replaced it.
        return !current.anyGenerated
                && configured().filter(key -> equal(key, presented)).isPresent();
    }

    public Status status() {
        State current = state();
        Instant now = Instant.now();
        Instant generatedAt = null;
        Instant previous = null;
        for (Row row : current.byFingerprint.values()) {
            if (row.origin.equals("GENEREE") && row.expiresAt == null) {
                generatedAt = row.createdAt;
            } else if (row.expiresAt != null
                    && row.expiresAt.isAfter(now)
                    && (previous == null || row.expiresAt.isAfter(previous))) {
                previous = row.expiresAt;
            }
        }
        if (generatedAt != null) {
            return new Status(Origin.GENERATED, generatedAt, previous);
        }
        return new Status(configured().isPresent() ? Origin.CONFIG : Origin.NONE, null, null);
    }

    /** Generates a key, retiring the one in force after {@code overlapMinutes} (zero: now). */
    public synchronized Rotation rotate(long overlapMinutes) {
        if (overlapMinutes < 0 || overlapMinutes > MAX_OVERLAP_MINUTES) {
            throw new BusinessError.Invalid(
                    "Le recouvrement doit être compris entre 0 et " + MAX_OVERLAP_MINUTES + " minutes.");
        }
        Instant now = Instant.now();
        Instant limit = now.plus(Duration.ofMinutes(overlapMinutes));
        byte[] random = new byte[32];
        RANDOM.nextBytes(random);
        String key = "mcp_" + Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        Optional<Instant> previousExpiry;
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                previousExpiry = retire(connection, limit);
                insert(connection, "GENEREE", fingerprint(key), now, null);
                connection.commit();
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to rotate the MCP key", e);
        } finally {
            state = null;
        }
        return new Rotation(key, previousExpiry.orElse(null));
    }

    /** Retires every key in force: the generated ones, and the configured one on the first generation. */
    private Optional<Instant> retire(Connection connection, Instant limit) throws SQLException {
        Optional<Instant> expiry = Optional.empty();
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE mcp_cle SET expire_le = ? WHERE expire_le IS NULL OR expire_le > ?")) {
            ps.setTimestamp(1, Timestamp.from(limit));
            ps.setTimestamp(2, Timestamp.from(limit));
            if (ps.executeUpdate() > 0) {
                expiry = Optional.of(limit);
            }
        }
        Optional<String> configured = configured();
        if (configured.isPresent() && !anyGenerated(connection)) {
            insert(connection, "CONFIG", fingerprint(configured.get()), Instant.now(), limit);
            expiry = Optional.of(limit);
        }
        return expiry;
    }

    private static boolean anyGenerated(Connection connection) throws SQLException {
        try (PreparedStatement ps =
                        connection.prepareStatement("SELECT 1 FROM mcp_cle WHERE origine = 'GENEREE' LIMIT 1");
                ResultSet rs = ps.executeQuery()) {
            return rs.next();
        }
    }

    private static void insert(Connection connection, String origin, String fingerprint, Instant now, Instant expiry)
            throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO mcp_cle (id, origine, empreinte, cree_le, expire_le) VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (empreinte) DO UPDATE SET expire_le = EXCLUDED.expire_le""")) {
            ps.setString(1, UUID.randomUUID().toString());
            ps.setString(2, origin);
            ps.setString(3, fingerprint);
            ps.setTimestamp(4, Timestamp.from(now));
            ps.setTimestamp(5, expiry == null ? null : Timestamp.from(expiry));
            ps.executeUpdate();
        }
    }

    /** Forgets what was read, for a test (or an operator's SQL repair) that rewrites the table behind the service's back. */
    public void invalidate() {
        state = null;
    }

    private State state() {
        State current = state;
        if (current == null) {
            current = load();
            state = current;
        }
        return current;
    }

    private State load() {
        Map<String, Row> rows = new HashMap<>();
        boolean anyGenerated = false;
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps =
                        connection.prepareStatement("SELECT origine, empreinte, cree_le, expire_le FROM mcp_cle");
                ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                Timestamp expiry = rs.getTimestamp("expire_le");
                rows.put(
                        rs.getString("empreinte"),
                        new Row(
                                rs.getString("origine"),
                                rs.getTimestamp("cree_le").toInstant(),
                                expiry == null ? null : expiry.toInstant()));
                anyGenerated |= rs.getString("origine").equals("GENEREE");
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to read the MCP keys", e);
        }
        return new State(rows, anyGenerated);
    }

    private Optional<String> configured() {
        return config.apiKey().filter(key -> !key.isBlank());
    }

    private static String fingerprint(String key) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Constant-time, so a mistyped key cannot be brute-forced by timing. */
    private static boolean equal(String expected, String presented) {
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8), presented.getBytes(StandardCharsets.UTF_8));
    }
}
