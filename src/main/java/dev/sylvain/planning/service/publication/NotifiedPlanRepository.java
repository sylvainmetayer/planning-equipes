package dev.sylvain.planning.service.publication;

import dev.sylvain.planning.service.JdbcEditionScope;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;

/**
 * Which plan each person was actually told about — {@code
 * animateur.plan_notifie_id}, the per-person reference the publication diff is
 * read against (issue #503).
 *
 * <p>Before this, « ce qu'on a annoncé » was one reference for everybody: the
 * last published snapshot. That made deferring one person's message
 * impossible to keep — « on ne prévient pas Untel ce soir, on l'appelle
 * d'abord » — because the next publication compared their schedule to a
 * snapshot they had never received, and their écart vanished without anybody
 * having told them anything.</p>
 *
 * <p>{@code null} means « never told », which is exactly a first delivery. A
 * publication moves the marker for everybody it did not defer, the excluded
 * included in nothing: they stay « à prévenir », with the écart accumulated
 * since their own last message.</p>
 */
@ApplicationScoped
public class NotifiedPlanRepository {

    @Inject
    DataSource dataSource;

    @Inject
    JdbcEditionScope scope;

    /**
     * The marker of every animateur that carries one, by id. An id missing
     * from the map has never been told anything — the caller reads that as a
     * first delivery rather than as an empty schedule.
     */
    public Map<String, Long> byAnimateur() {
        String sql = """
 SELECT id, plan_notifie_id FROM animateur
 WHERE edition_id = ? AND plan_notifie_id IS NOT NULL""";
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = scope.prepareScoped(connection, sql)) {
            Map<String, Long> marqueurs = new LinkedHashMap<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    marqueurs.put(rs.getString("id"), rs.getLong("plan_notifie_id"));
                }
            }
            return marqueurs;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to read the notified plan markers", e);
        }
    }

    /**
     * Moves the marker of the given people onto {@code snapshotId}: from now
     * on, that is the plan they have been told about.
     *
     * <p>Called with everybody a publication did <b>not</b> defer, and not
     * only with the people it wrote to: somebody whose schedule did not move
     * reads the new published plan exactly as they read the old one, so saying
     * they know it is true. A send that failed counts as told too — the count
     * measures what changed, not what was delivered (ADR 0011), and the
     * individual resend is the way back.</p>
     */
    public void mark(Collection<String> animateurIds, long snapshotId) {
        if (animateurIds.isEmpty()) {
            return;
        }
        List<String> ids = List.copyOf(animateurIds);
        String sql = """
 UPDATE animateur SET plan_notifie_id = ?
 WHERE edition_id = ? AND id = ?""";
        scope.write("Failed to move the notified plan markers", connection -> {
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setLong(1, snapshotId);
                ps.setString(2, scope.editionId());
                for (String animateurId : ids) {
                    ps.setString(3, animateurId);
                    ps.addBatch();
                }
                ps.executeBatch();
            }
        });
    }
}
