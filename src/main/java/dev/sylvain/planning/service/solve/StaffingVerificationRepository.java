package dev.sylvain.planning.service.solve;

import dev.sylvain.planning.service.JdbcEditionScope;
import dev.sylvain.planning.service.solve.StaffingVerificationService.StaffingVerification;
import dev.sylvain.planning.service.solve.StaffingVerificationService.VerificationState;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.sql.Array;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The {@code verification_besoin} table: one row per staffing check, born
 * {@code EN_COURS} and completed once when its solve ends.
 *
 * <p>Plain JDBC through {@link JdbcEditionScope}, like every repository here.
 * The completion runs on the check's own thread, which designates no edition:
 * the service enters the edition through {@code EditionContext.executeIn}
 * before calling it, so the predicate binds the right one.</p>
 */
@ApplicationScoped
public class StaffingVerificationRepository {

    private static final String COLUMNS = """
            id, etat, majeurs, mineurs, sieges, lancee_le, terminee_le, duree_secondes,
            plafond_secondes, realisable, sieges_non_pourvus, score_dur, regles_en_defaut, erreur""";

    private static final String SELECT_LATEST = "SELECT " + COLUMNS + """
             FROM verification_besoin
            WHERE edition_id = ?
            ORDER BY lancee_le DESC, id DESC
            LIMIT 1""";

    private static final String SELECT_AMONG = "SELECT " + COLUMNS + """
             FROM verification_besoin
            WHERE edition_id = ? AND id = ANY(?)""";

    private final JdbcEditionScope scope;

    @Inject
    public StaffingVerificationRepository(JdbcEditionScope scope) {
        this.scope = scope;
    }

    /** Records a check that has just started, and returns it with its id. */
    public StaffingVerification insert(StaffingVerification started) {
        long id = scope.writeAndReturn("Failed to record a staffing check", connection -> {
            try (PreparedStatement ps = scope.prepareScoped(connection, """
                    INSERT INTO verification_besoin (edition_id, lancee_le, etat, majeurs, mineurs,
                    sieges, plafond_secondes)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    RETURNING id""")) {
                ps.setTimestamp(2, Timestamp.from(started.lanceeLe()));
                ps.setString(3, started.etat().name());
                ps.setInt(4, started.majeurs());
                ps.setInt(5, started.mineurs());
                ps.setInt(6, started.sieges());
                ps.setLong(7, started.plafondSecondes());
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getLong(1);
                }
            }
        });
        return started.withId(id);
    }

    /** Writes the outcome of a check. Called once, when its solve ends. */
    public void complete(StaffingVerification done) {
        scope.write("Failed to record the outcome of a staffing check", connection -> {
            // Prepared unscoped: the SET clause comes before the predicate, so
            // the edition is not the first placeholder and is bound by hand.
            try (PreparedStatement ps = connection.prepareStatement("""
                    UPDATE verification_besoin
                    SET etat = ?, terminee_le = ?, duree_secondes = ?, realisable = ?,
                    sieges_non_pourvus = ?, score_dur = ?, regles_en_defaut = ?, erreur = ?
                    WHERE edition_id = ? AND id = ?""")) {
                ps.setString(1, done.etat().name());
                ps.setTimestamp(2, done.termineeLe() == null ? null : Timestamp.from(done.termineeLe()));
                setLong(ps, 3, done.dureeSecondes());
                if (done.realisable() == null) {
                    ps.setNull(4, Types.BOOLEAN);
                } else {
                    ps.setBoolean(4, done.realisable());
                }
                if (done.siegesNonPourvus() == null) {
                    ps.setNull(5, Types.INTEGER);
                } else {
                    ps.setInt(5, done.siegesNonPourvus());
                }
                setLong(ps, 6, done.scoreDur());
                ps.setString(7, done.reglesEnDefaut().isEmpty() ? null : String.join(",", done.reglesEnDefaut()));
                ps.setString(8, done.erreur());
                ps.setString(9, scope.editionId());
                ps.setLong(10, done.id());
                ps.executeUpdate();
            }
        });
    }

    /**
     * Closes every check left {@code EN_COURS}, across every edition at once.
     *
     * <p>Deliberately not edition-scoped: it runs once at startup, which has
     * no edition of its own, and a check is interrupted by the stop whatever
     * edition started it. Run through a plain statement rather than
     * {@link JdbcEditionScope#prepareScoped}, and named in
     * {@code EXCEPTIONS_ASSUMEES} for that reason.</p>
     *
     * @return how many checks were closed
     */
    public int closeInterrupted(String erreur) {
        return scope.writeAndReturn("Failed to close the interrupted staffing checks", connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "UPDATE verification_besoin SET etat = 'ECHEC', terminee_le = now(), erreur = ? WHERE etat = 'EN_COURS'")) {
                ps.setString(1, erreur);
                return ps.executeUpdate();
            }
        });
    }

    /** The most recent check of the current edition, running or finished. */
    public Optional<StaffingVerification> latest() {
        return scope.read("Failed to read the last staffing check", connection -> {
            try (PreparedStatement ps = scope.prepareScoped(connection, SELECT_LATEST);
                    ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(read(rs)) : Optional.empty();
            }
        });
    }

    /** The checks of the current edition among {@code ids}, by id — what the history joins. */
    public Map<Long, StaffingVerification> findAll(Collection<Long> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return scope.read("Failed to read staffing checks", connection -> {
            Array bound = connection.createArrayOf("bigint", ids.toArray());
            try (PreparedStatement ps = scope.prepareScoped(connection, SELECT_AMONG)) {
                ps.setArray(2, bound);
                try (ResultSet rs = ps.executeQuery()) {
                    Map<Long, StaffingVerification> found = new LinkedHashMap<>();
                    while (rs.next()) {
                        StaffingVerification verification = read(rs);
                        found.put(verification.id(), verification);
                    }
                    return found;
                }
            }
        });
    }

    private static void setLong(PreparedStatement ps, int index, Long value) throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.BIGINT);
        } else {
            ps.setLong(index, value);
        }
    }

    private static Long longOrNull(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static StaffingVerification read(ResultSet rs) throws SQLException {
        Timestamp terminee = rs.getTimestamp("terminee_le");
        boolean realisable = rs.getBoolean("realisable");
        Boolean realisableOuNull = rs.wasNull() ? null : realisable;
        int nonPourvus = rs.getInt("sieges_non_pourvus");
        Integer nonPourvusOuNull = rs.wasNull() ? null : nonPourvus;
        String regles = rs.getString("regles_en_defaut");
        int majeurs = rs.getInt("majeurs");
        int mineurs = rs.getInt("mineurs");
        return new StaffingVerification(
                rs.getLong("id"),
                VerificationState.valueOf(rs.getString("etat")),
                majeurs + mineurs,
                majeurs,
                mineurs,
                rs.getInt("sieges"),
                rs.getTimestamp("lancee_le").toInstant(),
                terminee == null ? null : terminee.toInstant(),
                longOrNull(rs, "duree_secondes"),
                rs.getLong("plafond_secondes"),
                realisableOuNull,
                nonPourvusOuNull,
                longOrNull(rs, "score_dur"),
                regles == null || regles.isBlank() ? List.of() : Arrays.asList(regles.split(",")),
                rs.getString("erreur"));
    }
}
