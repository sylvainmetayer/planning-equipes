package dev.sylvain.planning.service.validation;

import dev.sylvain.planning.domain.ValidationJournee;
import dev.sylvain.planning.service.JdbcEditionScope;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import javax.sql.DataSource;

/** The review marks of an edition: which days somebody accepted. */
@ApplicationScoped
public class ValidationJourneeRepository {

    @Inject
    DataSource dataSource;

    @Inject
    JdbcEditionScope scope;

    private static final String SELECT_VALIDATION_SQL = """
            SELECT id, jour, valide_le, valide_par, commentaire
            FROM validation_journee
            WHERE edition_id = ?""";

    /** Every validation of the current edition, most recently reviewed first. */
    public List<ValidationJournee> list() {
        List<ValidationJournee> validations = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = scope.prepareScoped(
                        connection, SELECT_VALIDATION_SQL + " ORDER BY valide_le DESC, jour DESC, id")) {
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    validations.add(read(rs));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to list day validations", e);
        }
        return validations;
    }

    private static ValidationJournee read(ResultSet rs) throws SQLException {
        Timestamp valideLe = rs.getTimestamp("valide_le");
        return new ValidationJournee(
                rs.getString("id"),
                rs.getObject("jour", LocalDate.class),
                valideLe == null ? null : valideLe.toInstant(),
                rs.getString("valide_par"),
                rs.getString("commentaire"));
    }

    /**
     * Writes the validation, replacing the one that day already carried
     * (see {@code uq_validation_journee_jour}). Re-reading a day is a new
     * reading, not a second row: the date, the author and the comment are the
     * ones of the reading that stands.
     */
    public void save(ValidationJournee validation) {
        String sql = """
                INSERT INTO validation_journee (edition_id, id, jour, valide_le, valide_par, commentaire)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (edition_id, jour) DO UPDATE SET
                    id = EXCLUDED.id,
                    valide_le = EXCLUDED.valide_le,
                    valide_par = EXCLUDED.valide_par,
                    commentaire = EXCLUDED.commentaire""";
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = scope.prepareScoped(connection, sql)) {
            ps.setString(2, validation.id());
            ps.setObject(3, validation.jour());
            ps.setTimestamp(4, Timestamp.from(validation.valideLe() != null ? validation.valideLe() : Instant.now()));
            ps.setString(5, validation.validePar());
            ps.setString(6, validation.commentaire());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to save day validation " + validation.id(), e);
        }
    }

    /** Removes one validation by id; an unknown id changes nothing. */
    public int delete(String id) {
        return scope.writeAndReturn("Failed to delete day validation " + id, connection -> {
            try (PreparedStatement ps =
                    scope.prepareScoped(connection, "DELETE FROM validation_journee WHERE edition_id = ? AND id = ?")) {
                ps.setString(2, id);
                return ps.executeUpdate();
            }
        });
    }

    /**
     * Removes the validations of {@code jours}: a seat that moved was read by
     * nobody.
     *
     * @return how many rows were withdrawn
     */
    public int deleteJours(Collection<LocalDate> jours) {
        if (jours.isEmpty()) {
            return 0;
        }
        return scope.writeAndReturn("Failed to withdraw the validations of the days a solve moved", connection -> {
            int retirees = 0;
            try (PreparedStatement ps = scope.prepareScoped(
                    connection, "DELETE FROM validation_journee WHERE edition_id = ? AND jour = ?")) {
                for (LocalDate jour : jours) {
                    ps.setObject(2, jour);
                    retirees += ps.executeUpdate();
                }
            }
            return retirees;
        });
    }
}
