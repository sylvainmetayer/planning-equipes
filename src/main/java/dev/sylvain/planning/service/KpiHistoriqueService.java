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

import com.fasterxml.jackson.databind.ObjectMapper;

import dev.sylvain.planning.domain.Edition;
import dev.sylvain.planning.service.PlanningKpiService.PlanningKpi;
import org.jboss.logging.Logger;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * KPI history (issue #89): one row per completed solve, kept forever so the
 * editions can be compared year over year.
 *
 * <p>The table is deliberately free of any foreign key, and the edition
 * <em>name</em> is denormalised onto each row: deleting an edition must not
 * erase its history — that history is precisely what outlives the edition.
 * For the same reason the listing is <b>not</b> edition-scoped, unlike every
 * other repository: a year-over-year comparison needs all editions side by
 * side.</p>
 *
 * <p>{@link #delete(long)} is not scoped either, and deliberately so: the
 * screen it serves lists every edition's rows, edition column included, so the
 * row deleted is the row the operator picked rather than one guessed at; the
 * id is a server-wide {@code BIGSERIAL}, which names one row on its own; and a
 * row orphaned by the deletion of its edition could never be cleaned up by an
 * edition-scoped {@code DELETE}. {@code IsolationEditionStructurelleTest}
 * carries the same reason in {@code TABLES_HORS_EDITION}.</p>
 */
@ApplicationScoped
public class KpiHistoriqueService {

    private static final Logger LOG = Logger.getLogger(KpiHistoriqueService.class);

    @Inject
    DataSource dataSource;

    @Inject
    EditionContext editionContext;

    @Inject
    EditionRepository editionRepository;

    @Inject
    PlanningKpiService kpiService;

    /**
     * The CDI-managed mapper, not a bare {@code new ObjectMapper()}: it carries
     * the modules Quarkus registers (JSR-310 in particular), so a field of a
     * persisted record may be a {@code LocalDate}/{@code Instant} instead of
     * having to be flattened to a {@code String} to keep a bare mapper happy.
     */
    @Inject
    ObjectMapper objectMapper;

    /** One history row: where it came from (labels survive deletions) and the KPI. */
    public record KpiHistoriqueEntry(
            long id,
            String editionId,
            String editionNom,
            PlanningKpi kpi,
            Instant creeLe) {
    }

    /**
     * Records the KPI of the plan just persisted by a solve. Never fails the
     * solve: a KPI row that could not be written must not cost the user their
     * run — same contract as the automatic snapshot capture.
     */
    public void recordAfterSolve(Long dureeSolveSecondes) {
        try {
            record(kpiService.computeCurrent(dureeSolveSecondes));
        } catch (RuntimeException e) {
            LOG.warn("KPI history row could not be written; the solve result is unaffected", e);
        }
    }

    void record(PlanningKpi kpi) {
        String editionId = editionContext.editionIdCourant();
        String editionNom = editionRepository.listEditions().stream()
                .filter(edition -> editionId.equals(edition.getId()))
                .map(Edition::getNom)
                .findFirst()
                .orElse(editionId);
        String sql = """
 INSERT INTO kpi_historique (edition_id, edition_nom, kpi, cree_le)
 VALUES (?, ?, ?::jsonb, ?)""";
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, editionId);
            ps.setString(2, editionNom);
            ps.setString(3, objectMapper.writeValueAsString(kpi));
            ps.setTimestamp(4, Timestamp.from(Instant.now()));
            ps.executeUpdate();
        } catch (SQLException | com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Failed to record KPI history", e);
        }
    }

    /** Every edition's history, newest first — see the class javadoc for why it is unscoped. */
    public List<KpiHistoriqueEntry> list() {
        String sql = """
 SELECT id, edition_id, edition_nom, kpi, cree_le
 FROM kpi_historique
 ORDER BY cree_le DESC, id DESC""";
        List<KpiHistoriqueEntry> entries = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement(sql);
                ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                Timestamp creeLe = rs.getTimestamp("cree_le");
                entries.add(new KpiHistoriqueEntry(
                        rs.getLong("id"),
                        rs.getString("edition_id"),
                        rs.getString("edition_nom"),
                        readKpi(rs.getString("kpi")),
                        creeLe == null ? null : creeLe.toInstant()));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to list KPI history", e);
        }
        return entries;
    }

    /** @return true when a row was actually deleted. */
    public boolean delete(long id) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement("DELETE FROM kpi_historique WHERE id = ?")) {
            ps.setLong(1, id);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to delete KPI history row " + id, e);
        }
    }

    private PlanningKpi readKpi(String json) {
        try {
            return objectMapper.readValue(json, PlanningKpi.class);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to read a KPI history row", e);
        }
    }
}
