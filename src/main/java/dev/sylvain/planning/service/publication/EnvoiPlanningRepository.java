package dev.sylvain.planning.service.publication;

import dev.sylvain.planning.service.JdbcEditionScope;
import dev.sylvain.planning.service.publication.PublicationTraceRepository.StatutEnvoi;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The delivery ledger of the planning ({@code envoi_planning}): per person, what
 * left, what failed, what could not leave for lack of an address and what was
 * deferred — for every publication and every individual resend.
 *
 * <p>Dates and states only. What a publication said lives in
 * {@link PublicationTraceRepository}; this table answers « did it reach them? »,
 * which used to be written to the server log and nowhere else — so the home
 * screen said « à jour » over a publication whose every mail had bounced.</p>
 */
@ApplicationScoped
public class EnvoiPlanningRepository {

    /** Why a planning mail went out: a publication, or the resend of one person's planning. */
    public enum NatureEnvoi {
        PUBLICATION,
        RENVOI
    }

    /**
     * Why a send failed, as a short code. Never the mail server's own message:
     * it routinely quotes the address it refused.
     */
    public enum CauseEchec {
        /** The server refused the recipient: the address is wrong, or no longer exists. */
        ADRESSE_REFUSEE,
        /** The recipient's mailbox is over quota. */
        BOITE_PLEINE,
        /** The mail server could not be reached at all. */
        SERVEUR_INJOIGNABLE,
        /** Anything else. */
        AUTRE;

        /**
         * Reads the cause off a failed send, walking the exception chain for
         * the SMTP reply codes and the network failures it can recognise.
         */
        public static CauseEchec of(Throwable echec) {
            for (Throwable courant = echec; courant != null; courant = courant.getCause()) {
                if (courant instanceof java.net.ConnectException
                        || courant instanceof java.net.UnknownHostException
                        || courant instanceof java.net.SocketTimeoutException) {
                    return SERVEUR_INJOIGNABLE;
                }
                String message = courant.getMessage() == null ? "" : courant.getMessage();
                if (message.contains("552") || message.contains("452") || message.contains("5.2.2")) {
                    return BOITE_PLEINE;
                }
                if (message.contains("550")
                        || message.contains("553")
                        || message.contains("5.1.1")
                        || message.contains("5.1.3")) {
                    return ADRESSE_REFUSEE;
                }
                if (message.contains("Connection refused") || message.contains("connection timed out")) {
                    return SERVEUR_INJOIGNABLE;
                }
            }
            return AUTRE;
        }
    }

    /**
     * One delivery.
     *
     * @param snapshotId the published version that was sent, {@code null} when none
     * @param cause      set on a failure only
     */
    public record Envoi(
            String animateurId,
            Long snapshotId,
            NatureEnvoi nature,
            StatutEnvoi statut,
            CauseEchec cause,
            Instant envoyeLe) {}

    private final JdbcEditionScope scope;

    @Inject
    public EnvoiPlanningRepository(JdbcEditionScope scope) {
        this.scope = scope;
    }

    /** Records the deliveries of one publication or of one resend, in one batch. */
    public void recordAll(List<Envoi> envois) {
        if (envois.isEmpty()) {
            return;
        }
        String sql = """
 INSERT INTO envoi_planning (edition_id, animateur_id, snapshot_id, nature, statut, cause, envoye_le)
 VALUES (?, ?, ?, ?, ?, ?, ?)""";
        scope.write("Failed to record the planning deliveries", connection -> {
            try (PreparedStatement ps = scope.prepareScoped(connection, sql)) {
                for (Envoi envoi : envois) {
                    ps.setString(2, envoi.animateurId());
                    ps.setObject(3, envoi.snapshotId(), Types.BIGINT);
                    ps.setString(4, envoi.nature().name());
                    ps.setString(5, envoi.statut().name());
                    ps.setString(6, envoi.cause() == null ? null : envoi.cause().name());
                    ps.setTimestamp(7, Timestamp.from(envoi.envoyeLe()));
                    ps.addBatch();
                }
                ps.executeBatch();
            }
        });
    }

    /**
     * The delivery that stands for each person — what the Diffuser table shows
     * as « état de l'envoi », what the home screen counts as failures and what
     * the espace warns about: their latest, except that a deferral never
     * masks a failure. A person left out of a targeted publication gets an
     * {@code EXCLU} row, which says nothing left, not that the mail that
     * bounced before has since arrived: while the latest delivery that did
     * leave — or could not — is a failure or a missing address, that one is
     * what the person's state is.
     */
    public Map<String, Envoi> latestByAnimateur() {
        // Per person, the latest deferral and the latest of everything else.
        String sql = """
 SELECT DISTINCT ON (animateur_id, statut = 'EXCLU') animateur_id, snapshot_id, nature, statut, cause, envoye_le
 FROM envoi_planning
 WHERE edition_id = ?
 ORDER BY animateur_id, statut = 'EXCLU', envoye_le DESC, id DESC""";
        return scope.read("Failed to read the planning deliveries", connection -> {
            Map<String, Envoi> derniers = new LinkedHashMap<>();
            try (PreparedStatement ps = scope.prepareScoped(connection, sql);
                    ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Envoi envoi = read(rs);
                    derniers.merge(envoi.animateurId(), envoi, EnvoiPlanningRepository::standing);
                }
            }
            return derniers;
        });
    }

    /**
     * The delivery that stands for one person, read the way
     * {@link #latestByAnimateur()} reads everybody's — for a screen that
     * shows one person, which has no reason to read the whole ledger.
     * {@code null} when nothing was ever sent to them.
     */
    public Envoi latestOf(String animateurId) {
        String sql = """
 SELECT DISTINCT ON (statut = 'EXCLU') animateur_id, snapshot_id, nature, statut, cause, envoye_le
 FROM envoi_planning
 WHERE edition_id = ? AND animateur_id = ?
 ORDER BY statut = 'EXCLU', envoye_le DESC, id DESC
 LIMIT 2""";
        return scope.read("Failed to read the planning deliveries", connection -> {
            Envoi retenu = null;
            try (PreparedStatement ps = scope.prepareScoped(connection, sql)) {
                ps.setString(2, animateurId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Envoi envoi = read(rs);
                        retenu = retenu == null ? envoi : standing(retenu, envoi);
                    }
                }
            }
            return retenu;
        });
    }

    /**
     * Of a person's latest deferral and latest other delivery, the one that
     * stands: the failure or the missing address when the other delivery is
     * one, whatever deferral followed it; otherwise the later of the two.
     */
    static Envoi standing(Envoi a, Envoi b) {
        Envoi differe = a.statut() == StatutEnvoi.EXCLU ? a : b;
        Envoi autre = differe == a ? b : a;
        if (autre.statut() == StatutEnvoi.EXCLU) {
            return later(a, b);
        }
        if (autre.statut() == StatutEnvoi.ECHEC || autre.statut() == StatutEnvoi.SANS_EMAIL) {
            return autre;
        }
        return later(a, b);
    }

    private static Envoi later(Envoi a, Envoi b) {
        return b.envoyeLe() != null && (a.envoyeLe() == null || b.envoyeLe().isAfter(a.envoyeLe())) ? b : a;
    }

    /** How many people's latest delivery failed — the home screen's « pas à jour ». */
    public int countLatestFailures() {
        return (int) latestByAnimateur().values().stream()
                .filter(envoi -> envoi.statut() == StatutEnvoi.ECHEC)
                .count();
    }

    private static Envoi read(ResultSet rs) throws SQLException {
        long snapshotId = rs.getLong("snapshot_id");
        Long snapshot = rs.wasNull() ? null : snapshotId;
        String cause = rs.getString("cause");
        Timestamp envoyeLe = rs.getTimestamp("envoye_le");
        return new Envoi(
                rs.getString("animateur_id"),
                snapshot,
                NatureEnvoi.valueOf(rs.getString("nature")),
                StatutEnvoi.valueOf(rs.getString("statut")),
                cause == null ? null : CauseEchec.valueOf(cause),
                envoyeLe == null ? null : envoyeLe.toInstant());
    }
}
