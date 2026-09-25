package dev.sylvain.planning.service.solve;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.sylvain.planning.service.JdbcEditionScope;
import dev.sylvain.planning.service.analyse.Dosage;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import org.jboss.logging.Logger;

/**
 * The dosage the edition's persisted plan was solved under, next to the other
 * facts of that solve in {@code planning_resolution}. Read by the KPI of the
 * current plan — hence by every snapshot captured of it — and rewritten by a
 * restore, which puts back a plan together with the dosage it was computed
 * under.
 */
@ApplicationScoped
public class PlanDosageRepository {

    private static final Logger LOG = Logger.getLogger(PlanDosageRepository.class);

    private final JdbcEditionScope scope;

    private final ObjectMapper objectMapper;

    @Inject
    public PlanDosageRepository(JdbcEditionScope scope, ObjectMapper objectMapper) {
        this.scope = scope;
        this.objectMapper = objectMapper;
    }

    /** Stamps the persisted plan with {@code dosage}; {@code null} says « unknown ». */
    public void record(Dosage dosage) {
        String json;
        try {
            json = dosage == null ? null : objectMapper.writeValueAsString(dosage);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to write the plan's dosage", e);
        }
        scope.write("Failed to record the plan's dosage", connection -> {
            try (PreparedStatement ps = scope.prepareScoped(
                    connection, "UPDATE planning_resolution SET dosage = ?::jsonb WHERE edition_id = ?")) {
                // prepareScoped binds the edition to the first placeholder:
                // rebind both in the order the statement reads them.
                ps.setString(1, json);
                ps.setString(2, scope.editionId());
                ps.executeUpdate();
            }
        });
    }

    /** The dosage of the persisted plan, {@code null} when unknown — a plan older than the column, or none. */
    public Dosage current() {
        String json = scope.read("Failed to read the plan's dosage", connection -> {
            try (PreparedStatement ps = scope.prepareScoped(
                            connection, "SELECT dosage::text AS dosage FROM planning_resolution WHERE edition_id = ?");
                    ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString("dosage") : null;
            }
        });
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readValue(json, Dosage.class);
        } catch (JsonProcessingException e) {
            LOG.warn("Unreadable dosage on the persisted plan; read as unknown", e);
            return null;
        }
    }
}
