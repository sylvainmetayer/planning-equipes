package dev.sylvain.planning.service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import javax.sql.DataSource;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Who was told what, and when (issue #245). One row per person per
 * publication — including the ones that could not be reached, because
 * « prévenu » and « à prévenir » must not read the same afterwards.
 *
 * <p>The rows carry the sentences the animateur read, not identifiers to
 * resolve again: a trace that changes meaning when a stand is renamed is not a
 * trace.</p>
 */
@ApplicationScoped
public class PublicationTraceRepository {

    /** Outcome of one delivery, as stored in {@code publication_destinataire.statut}. */
    public enum StatutEnvoi {

        /** The mail left. */
        ENVOYE,

        /** Concerned, but no address on their fiche: the admin has to reach them another way. */
        SANS_EMAIL,

        /** The send itself failed; the individual resend button is the way back. */
        ECHEC
    }

    /**
     * One line of the trace.
     *
     * @param changements the exact sentences that were sent, in order
     */
    public record Destinataire(long snapshotId, String animateurId, String nomAffiche, String email,
            StatutEnvoi statut, Instant envoyeLe, List<String> changements) {
    }

    @Inject
    DataSource dataSource;

    @Inject
    JdbcEditionScope scope;

    @Inject
    ObjectMapper objectMapper;

    /** Records everyone a publication addressed, reached or not. */
    public void enregistrer(long snapshotId, List<Destinataire> destinataires) {
        if (destinataires.isEmpty()) {
            return;
        }
        String sql = """
 INSERT INTO publication_destinataire (edition_id, snapshot_id, animateur_id, nom_affiche, email,
 statut, envoye_le, changements)
 VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb)""";
        scope.write("Failed to record the publication recipients", connection -> {
            try (PreparedStatement ps = scope.prepareScoped(connection, sql)) {
                for (Destinataire destinataire : destinataires) {
                    ps.setLong(2, snapshotId);
                    ps.setString(3, destinataire.animateurId());
                    ps.setString(4, destinataire.nomAffiche());
                    ps.setString(5, destinataire.email());
                    ps.setString(6, destinataire.statut().name());
                    ps.setTimestamp(7, Timestamp.from(destinataire.envoyeLe()));
                    ps.setString(8, ecrire(destinataire.changements()));
                    ps.addBatch();
                }
                ps.executeBatch();
            }
        });
    }

    /** The trace of one publication, in the order the admin reviewed it. */
    public List<Destinataire> parSnapshot(long snapshotId) {
        String sql = """
 SELECT snapshot_id, animateur_id, nom_affiche, email, statut, envoye_le, changements
 FROM publication_destinataire
 WHERE edition_id = ? AND snapshot_id = ?
 ORDER BY nom_affiche""";
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = scope.prepareScoped(connection, sql)) {
            ps.setLong(2, snapshotId);
            return lire(ps);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to read the recipients of publication " + snapshotId, e);
        }
    }

    /** Everything one animateur was ever told, newest first — their own history. */
    public List<Destinataire> parAnimateur(String animateurId) {
        String sql = """
 SELECT snapshot_id, animateur_id, nom_affiche, email, statut, envoye_le, changements
 FROM publication_destinataire
 WHERE edition_id = ? AND animateur_id = ?
 ORDER BY envoye_le DESC, id DESC""";
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = scope.prepareScoped(connection, sql)) {
            ps.setString(2, animateurId);
            return lire(ps);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to read the publications sent to " + animateurId, e);
        }
    }

    private List<Destinataire> lire(PreparedStatement ps) throws SQLException {
        List<Destinataire> destinataires = new ArrayList<>();
        try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                Timestamp envoyeLe = rs.getTimestamp("envoye_le");
                destinataires.add(new Destinataire(
                        rs.getLong("snapshot_id"),
                        rs.getString("animateur_id"),
                        rs.getString("nom_affiche"),
                        rs.getString("email"),
                        StatutEnvoi.valueOf(rs.getString("statut")),
                        envoyeLe == null ? null : envoyeLe.toInstant(),
                        relire(rs.getString("changements"))));
            }
        }
        return destinataires;
    }

    private String ecrire(List<String> changements) {
        try {
            return objectMapper.writeValueAsString(changements == null ? List.of() : changements);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialise the announced changes", e);
        }
    }

    /** A payload written by an older format reads as an empty trace, never as an error. */
    private List<String> relire(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {
            });
        } catch (Exception e) {
            return List.of();
        }
    }
}
