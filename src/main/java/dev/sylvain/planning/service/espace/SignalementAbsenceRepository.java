package dev.sylvain.planning.service.espace;

import dev.sylvain.planning.domain.SignalementAbsence;
import dev.sylvain.planning.domain.SignalementAbsence.Motif;
import dev.sylvain.planning.domain.SignalementAbsence.Portee;
import dev.sylvain.planning.domain.SignalementAbsence.Statut;
import dev.sylvain.planning.service.BusinessError;
import dev.sylvain.planning.service.JdbcEditionScope;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * SQL of the absences reported from the espace ({@code signalement_absence}),
 * every statement scoped through {@link JdbcEditionScope}.
 *
 * <p>« One open report per person and per object » is the database's rule, a
 * partial unique index: a second identical report is refused by the insert
 * itself, not by a read that two concurrent clicks would both pass.</p>
 */
@ApplicationScoped
public class SignalementAbsenceRepository {

    private static final String SELECT_EDITION = """
            SELECT id, animateur_id, portee, jour, creneau_id, stand_id, motif, statut, signale_le, traite_le
            FROM signalement_absence
            WHERE edition_id = ?
            ORDER BY jour, signale_le, id""";

    private static final String SELECT_ANIMATEUR = """
            SELECT id, animateur_id, portee, jour, creneau_id, stand_id, motif, statut, signale_le, traite_le
            FROM signalement_absence
            WHERE edition_id = ? AND animateur_id = ?
            ORDER BY jour, signale_le, id""";

    private static final String SELECT_ID = """
            SELECT id, animateur_id, portee, jour, creneau_id, stand_id, motif, statut, signale_le, traite_le
            FROM signalement_absence
            WHERE edition_id = ? AND id = ?""";

    private static final String INSERT = """
            INSERT INTO signalement_absence (edition_id, animateur_id, portee, jour, creneau_id, stand_id, motif)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT DO NOTHING
            RETURNING id""";

    private final JdbcEditionScope scope;

    @Inject
    public SignalementAbsenceRepository(JdbcEditionScope scope) {
        this.scope = scope;
    }

    /** Every report of the edition, by day then by arrival. */
    public List<SignalementAbsence> list() {
        return scope.read("Failed to read the absence reports", connection -> {
            try (PreparedStatement ps = scope.prepareScoped(connection, SELECT_EDITION)) {
                return read(ps);
            }
        });
    }

    /** One animateur's reports, what their espace shows back. */
    public List<SignalementAbsence> listForAnimateur(String animateurId) {
        return scope.read("Failed to read the absence reports of an animateur", connection -> {
            try (PreparedStatement ps = scope.prepareScoped(connection, SELECT_ANIMATEUR)) {
                ps.setString(2, animateurId);
                return read(ps);
            }
        });
    }

    public Optional<SignalementAbsence> byId(long id) {
        return scope.read("Failed to read an absence report", connection -> {
            try (PreparedStatement ps = scope.prepareScoped(connection, SELECT_ID)) {
                ps.setLong(2, id);
                return read(ps).stream().findFirst();
            }
        });
    }

    /**
     * Records a report.
     *
     * @throws BusinessError.Conflict when the same person already has an open
     *         report on the same object — the unique index says so
     */
    public long insert(
            String animateurId, Portee portee, java.time.LocalDate jour, Long creneauId, String standId, Motif motif) {
        Long id = scope.writeAndReturn("Failed to record an absence report", connection -> {
            try (PreparedStatement ps = scope.prepareScoped(connection, INSERT)) {
                ps.setString(2, animateurId);
                ps.setString(3, portee.name());
                ps.setDate(4, Date.valueOf(jour));
                ps.setObject(5, creneauId, Types.BIGINT);
                ps.setString(6, standId);
                ps.setString(7, motif == null ? null : motif.name());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getLong(1) : null;
                }
            }
        });
        if (id == null) {
            throw new BusinessError.Conflict("Vous avez déjà signalé cet empêchement : l'organisation l'a reçu.");
        }
        return id;
    }

    /**
     * Moves an open report to {@code statut}, and only an open one: two
     * decisions a second apart, or a withdrawal racing a decision, leave one
     * winner and a {@code false} for the other.
     *
     * @return whether the report was still open and is now settled
     */
    public boolean settle(long id, Statut statut, Instant le) {
        // Not prepareScoped: the SET clause claims the first placeholders.
        String sql = """
                UPDATE signalement_absence SET statut = ?, traite_le = ?
                WHERE edition_id = ? AND id = ? AND statut = 'SIGNALE'""";
        return scope.writeAndReturn("Failed to settle an absence report", connection -> {
                    try (PreparedStatement ps = connection.prepareStatement(sql)) {
                        ps.setString(1, statut.name());
                        ps.setTimestamp(2, Timestamp.from(le));
                        ps.setString(3, scope.editionId());
                        ps.setLong(4, id);
                        return ps.executeUpdate();
                    }
                })
                > 0;
    }

    private static List<SignalementAbsence> read(PreparedStatement ps) throws SQLException {
        List<SignalementAbsence> signalements = new ArrayList<>();
        try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                long creneauId = rs.getLong("creneau_id");
                Long creneau = rs.wasNull() ? null : creneauId;
                String motif = rs.getString("motif");
                Timestamp traiteLe = rs.getTimestamp("traite_le");
                signalements.add(new SignalementAbsence(
                        rs.getLong("id"),
                        rs.getString("animateur_id"),
                        Portee.valueOf(rs.getString("portee")),
                        rs.getDate("jour").toLocalDate(),
                        creneau,
                        rs.getString("stand_id"),
                        motif == null ? null : Motif.valueOf(motif),
                        Statut.valueOf(rs.getString("statut")),
                        rs.getTimestamp("signale_le").toInstant(),
                        traiteLe == null ? null : traiteLe.toInstant()));
            }
        }
        return signalements;
    }
}
