package dev.sylvain.planning.service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import javax.sql.DataSource;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.sylvain.planning.service.SolverJobService.JobStatus;
import dev.sylvain.planning.service.SolverJobService.JobType;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Persistence of the solver queue and job journal (see {@code V51__solver_job.sql}).
 *
 * <p>Stores the <b>intention</b> of a job — type, budget, target edition,
 * incremental perimeter — never a Timefold solver state and never the result
 * payload. That is enough to rebuild the queue after a restart, because a
 * queued job already builds its problem when it starts rather than when it was
 * submitted (see {@link SolverJobService}).</p>
 *
 * <p>Plain JDBC like {@link PlanSnapshotService} and {@link KpiHistoriqueService}:
 * the project ships no ORM.</p>
 */
@ApplicationScoped
public class SolverJobRepository {

    @Inject
    DataSource dataSource;

    /**
     * The CDI-managed mapper, not a bare {@code new ObjectMapper()}: the
     * perimeter carries {@code LocalDate}s, which need the JSR-310 module
     * Quarkus already registers on this one.
     */
    @Inject
    ObjectMapper objectMapper;

    /**
     * One persisted job. Mirrors the in-memory
     * {@link SolverJobService.SolverJob} minus what a restart cannot restore
     * (the result payload) and plus what only the database knows (nothing —
     * {@code ordre} stays internal, it only drives the read order).
     */
    public record LigneJob(
            String id,
            String editionId,
            String editionNom,
            JobType type,
            JobStatus statut,
            Long secondsLimit,
            PerimetreReplanification perimetre,
            boolean rejouable,
            String erreur,
            Instant soumisLe,
            Instant demarreLe,
            Instant termineLe) {
    }

    /**
     * Writes the job's current state, insert or update. Called on every status
     * transition, so the table always mirrors what the service holds.
     *
     * <p>{@code ordre} is left to the sequence on insert and never touched on
     * update: a job keeps the queue position it was given when submitted.</p>
     */
    public void enregistrer(LigneJob ligne) {
        String sql = "INSERT INTO solver_job (id, edition_id, edition_nom, type, statut, seconds_limit, "
                + "perimetre, rejouable, erreur, soumis_le, demarre_le, termine_le) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?) "
                + "ON CONFLICT (id) DO UPDATE SET statut = EXCLUDED.statut, erreur = EXCLUDED.erreur, "
                + "demarre_le = EXCLUDED.demarre_le, termine_le = EXCLUDED.termine_le";
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, ligne.id());
            ps.setString(2, ligne.editionId());
            ps.setString(3, ligne.editionNom());
            ps.setString(4, ligne.type().name());
            ps.setString(5, ligne.statut().name());
            setLong(ps, 6, ligne.secondsLimit());
            ps.setString(7, ecrirePerimetre(ligne.perimetre()));
            ps.setBoolean(8, ligne.rejouable());
            ps.setString(9, ligne.erreur());
            ps.setTimestamp(10, horodatage(ligne.soumisLe()));
            ps.setTimestamp(11, horodatage(ligne.demarreLe()));
            ps.setTimestamp(12, horodatage(ligne.termineLe()));
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to persist solver job " + ligne.id(), e);
        }
    }

    /**
     * Every known job, <b>in submission order</b> — which is the order the
     * queue must be replayed in.
     */
    public List<LigneJob> lister() {
        String sql = "SELECT id, edition_id, edition_nom, type, statut, seconds_limit, perimetre::text AS perimetre, "
                + "rejouable, erreur, soumis_le, demarre_le, termine_le FROM solver_job ORDER BY ordre";
        List<LigneJob> lignes = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement(sql);
                ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                lignes.add(lire(rs));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to list solver jobs", e);
        }
        return lignes;
    }

    public void supprimer(String id) {
        executer("DELETE FROM solver_job WHERE id = ?", ps -> ps.setString(1, id),
                "Failed to delete solver job " + id);
    }

    /** Drops the finished jobs older than {@code cutoff}, mirroring the in-memory retention. */
    public void purgerTerminesAvant(Instant cutoff) {
        executer("DELETE FROM solver_job WHERE termine_le IS NOT NULL AND termine_le < ?",
                ps -> ps.setTimestamp(1, horodatage(cutoff)), "Failed to purge solver jobs");
    }

    private LigneJob lire(ResultSet rs) throws SQLException {
        // Read (and null-checked) before anything else: wasNull() reports on
        // the last column read, so any getString() in between would break it.
        long valeurSecondsLimit = rs.getLong("seconds_limit");
        Long secondsLimit = rs.wasNull() ? null : valeurSecondsLimit;
        return new LigneJob(
                rs.getString("id"),
                rs.getString("edition_id"),
                rs.getString("edition_nom"),
                JobType.valueOf(rs.getString("type")),
                JobStatus.valueOf(rs.getString("statut")),
                secondsLimit,
                lirePerimetre(rs.getString("perimetre")),
                rs.getBoolean("rejouable"),
                rs.getString("erreur"),
                instant(rs.getTimestamp("soumis_le")),
                instant(rs.getTimestamp("demarre_le")),
                instant(rs.getTimestamp("termine_le")));
    }

    private String ecrirePerimetre(PerimetreReplanification perimetre) {
        if (perimetre == null || perimetre.estVide()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(perimetre);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize the replanning perimeter", e);
        }
    }

    /**
     * A perimeter that cannot be re-read leaves the job with none rather than
     * failing the whole restore: the automatic perimeter is a valid fallback,
     * and losing the whole queue over one unreadable column would be worse.
     */
    private PerimetreReplanification lirePerimetre(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, PerimetreReplanification.class);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private interface Parametrage {
        void appliquer(PreparedStatement ps) throws SQLException;
    }

    private void executer(String sql, Parametrage parametrage, String message) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement(sql)) {
            parametrage.appliquer(ps);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(message, e);
        }
    }

    private static void setLong(PreparedStatement ps, int index, Long valeur) throws SQLException {
        if (valeur == null) {
            ps.setNull(index, Types.BIGINT);
        } else {
            ps.setLong(index, valeur);
        }
    }

    private static Timestamp horodatage(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
