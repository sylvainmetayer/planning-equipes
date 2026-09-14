package dev.sylvain.planning.service.publication;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.sylvain.planning.service.JdbcEditionScope;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

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
     * One line of the trace: the exact sentences that were sent, in order.
     *
     * <p>The two halves of the message are kept apart, as the publication
     * preview has always kept them (issue #532). They do not age the same way:
     * a schedule sentence says what was announced and stays true, where
     * « votre demande est en attente de décision » stops being true the moment
     * the organisation decides, with no publication in between. What the mail
     * carried is their concatenation, in that order.</p>
     *
     * @param changements what moved in this person's own schedule
     * @param demandes    where their échange requests stood
     * @param premiereDiffusion true when that publication was this person's
     *                          first: the sentences then describe a planning,
     *                          not a list of corrections. Stored rather than
     *                          derived — nothing in the sentences themselves
     *                          tells the two apart afterwards
     */
    @Schema(requiredProperties = {"snapshotId", "premiereDiffusion"})
    public record Destinataire(
            long snapshotId,
            String animateurId,
            String nomAffiche,
            String email,
            StatutEnvoi statut,
            Instant envoyeLe,
            List<String> changements,
            List<String> demandes,
            boolean premiereDiffusion) {

        /** Everything the mail carried, in the order it read — what the trace used to store flattened. */
        public List<String> lignes() {
            List<String> lignes = new ArrayList<>(changements);
            lignes.addAll(demandes);
            return List.copyOf(lignes);
        }
    }

    @Inject
    DataSource dataSource;

    @Inject
    JdbcEditionScope scope;

    @Inject
    ObjectMapper objectMapper;

    /** Records everyone a publication addressed, reached or not. */
    public void record(long snapshotId, List<Destinataire> destinataires) {
        if (destinataires.isEmpty()) {
            return;
        }
        String sql = """
 INSERT INTO publication_destinataire (edition_id, snapshot_id, animateur_id, nom_affiche, email,
 statut, envoye_le, changements, demandes, premiere_diffusion)
 VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?)""";
        scope.write("Failed to record the publication recipients", connection -> {
            try (PreparedStatement ps = scope.prepareScoped(connection, sql)) {
                for (Destinataire destinataire : destinataires) {
                    ps.setLong(2, snapshotId);
                    ps.setString(3, destinataire.animateurId());
                    ps.setString(4, destinataire.nomAffiche());
                    ps.setString(5, destinataire.email());
                    ps.setString(6, destinataire.statut().name());
                    ps.setTimestamp(7, Timestamp.from(destinataire.envoyeLe()));
                    ps.setString(8, serialize(destinataire.changements()));
                    ps.setString(9, serialize(destinataire.demandes()));
                    ps.setBoolean(10, destinataire.premiereDiffusion());
                    ps.addBatch();
                }
                ps.executeBatch();
            }
        });
    }

    /** The trace of one publication, in the order the admin reviewed it. */
    public List<Destinataire> bySnapshot(long snapshotId) {
        String sql = """
 SELECT snapshot_id, animateur_id, nom_affiche, email, statut, envoye_le, changements, demandes,
 premiere_diffusion
 FROM publication_destinataire
 WHERE edition_id = ? AND snapshot_id = ?
 ORDER BY nom_affiche""";
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = scope.prepareScoped(connection, sql)) {
            ps.setLong(2, snapshotId);
            return read(ps);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to read the recipients of publication " + snapshotId, e);
        }
    }

    /** Everything one animateur was ever told, newest first — their own history. */
    public List<Destinataire> byAnimateur(String animateurId) {
        String sql = """
 SELECT snapshot_id, animateur_id, nom_affiche, email, statut, envoye_le, changements, demandes,
 premiere_diffusion
 FROM publication_destinataire
 WHERE edition_id = ? AND animateur_id = ?
 ORDER BY envoye_le DESC, id DESC""";
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = scope.prepareScoped(connection, sql)) {
            ps.setString(2, animateurId);
            return read(ps);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to read the publications sent to " + animateurId, e);
        }
    }

    /**
     * The last thing this animateur was told, {@code null} when they were
     * never written to (issue #532). One row, not a filter over
     * {@link #byAnimateur(String)}: the espace reads it on every open, and
     * what it shows is the newest line, never the history.
     *
     * <p>Read whatever the send's outcome was: a line the mail never carried —
     * no address on the fiche, a send that failed — is exactly the one the
     * espace has to show, since nothing else ever will.</p>
     */
    public Destinataire lastSentTo(String animateurId) {
        String sql = """
 SELECT snapshot_id, animateur_id, nom_affiche, email, statut, envoye_le, changements, demandes,
 premiere_diffusion
 FROM publication_destinataire
 WHERE edition_id = ? AND animateur_id = ?
 ORDER BY envoye_le DESC, id DESC
 LIMIT 1""";
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = scope.prepareScoped(connection, sql)) {
            ps.setString(2, animateurId);
            List<Destinataire> lignes = read(ps);
            return lignes.isEmpty() ? null : lignes.get(0);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to read the last publication sent to " + animateurId, e);
        }
    }

    private List<Destinataire> read(PreparedStatement ps) throws SQLException {
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
                        deserialize(rs.getString("changements")),
                        deserialize(rs.getString("demandes")),
                        rs.getBoolean("premiere_diffusion")));
            }
        }
        return destinataires;
    }

    private String serialize(List<String> changements) {
        try {
            return objectMapper.writeValueAsString(changements == null ? List.of() : changements);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialise the announced changes", e);
        }
    }

    /** A payload written by an older format reads as an empty trace, never as an error. */
    private List<String> deserialize(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }
}
