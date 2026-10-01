package dev.sylvain.planning.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.service.EditionContext;
import dev.sylvain.planning.service.analyse.RealisedVsPlanned;
import dev.sylvain.planning.service.publication.PlanPublicationService;
import dev.sylvain.planning.service.solve.PlanSnapshotService;
import dev.sylvain.planning.service.solve.PlanningPersistenceService;
import io.quarkiverse.mcp.server.ToolCallException;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/**
 * {@code realise_vs_planifie}: the screen's report, counts by stand and day,
 * and never a person — neither a name nor an animateur id leaves over MCP.
 */
@QuarkusTest
class RealiseMcpToolsTest {

    private static final LocalDate JOUR = LocalDate.of(2026, 8, 14);

    @Inject
    RealiseMcpTools tools;

    @Inject
    InstantaneMcpTools instantanes;

    @Inject
    EditionContext editionContext;

    @Inject
    PlanningPersistenceService persistence;

    @Inject
    PlanPublicationService publication;

    @Inject
    PlanSnapshotService snapshots;

    @Inject
    DataSource dataSource;

    @Inject
    ObjectMapper objectMapper;

    @Test
    void theToolCountsAReplacedSeatAndNamesNobody() throws Exception {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement("DELETE FROM plan_snapshot")) {
            ps.executeUpdate();
        }
        persistence.clearDatabase();
        Animateur alice = new Animateur("RVM-A", "Alice", "Martin", LocalDate.of(1990, 1, 1), false);
        Animateur bruno = new Animateur("RVM-B", "Bruno", "Petit", LocalDate.of(1992, 2, 2), false);
        Stand stand = new Stand("RVM-S1", "Stand outil", Set.of(), 1, 1, false);
        PosteAffectation poste = new PosteAffectation(
                "RVM-P1", stand, new Creneau(970_320_000L, 1, JOUR, LocalTime.of(10, 0), LocalTime.of(12, 0)));
        poste.setAnimateur(alice);
        persistence.persist(new PlanningEvenement(JOUR, List.of(alice, bruno), List.of(poste)));
        publication.publier();
        backdateLastPublication(Instant.parse("2026-08-01T10:00:00Z"));
        persistence.reaffecterPoste("RVM-P1", "RVM-B");

        RealisedVsPlanned report = tools.realisedVsPlanned(null);
        String json = objectMapper.writeValueAsString(report);

        assertThat(report.referenceAvailable()).isTrue();
        assertThat(report.event().publishedSeats()).isEqualTo(1);
        assertThat(report.event().replacements()).isEqualTo(1);
        assertThat(json)
                .contains("Stand outil")
                .doesNotContain("RVM-A")
                .doesNotContain("RVM-B")
                .doesNotContain("Alice")
                .doesNotContain("Bruno");
    }

    /**
     * {@code supprimer_instantane} refuses, as the REST route does, the
     * publication still measuring an elapsed day — with its sentence, not an
     * internal error.
     */
    @Test
    void theDeletionToolRefusesAPublicationThatMeasuresAnElapsedDay() throws Exception {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement("DELETE FROM plan_snapshot WHERE edition_id = ?")) {
            ps.setString(1, editionContext.editionIdCourant());
            ps.executeUpdate();
        }
        persistence.clearDatabase();
        Animateur alice = new Animateur("RVM-A", "Alice", "Martin", LocalDate.of(1990, 1, 1), false);
        Animateur bruno = new Animateur("RVM-B", "Bruno", "Petit", LocalDate.of(1992, 2, 2), false);
        Stand stand = new Stand("RVM-S1", "Stand outil", Set.of(), 1, 1, false);
        PosteAffectation poste = new PosteAffectation(
                "RVM-P1", stand, new Creneau(970_320_000L, 1, JOUR, LocalTime.of(10, 0), LocalTime.of(12, 0)));
        poste.setAnimateur(alice);
        persistence.persist(new PlanningEvenement(JOUR, List.of(alice, bruno), List.of(poste)));
        publication.publier();
        backdateLastPublication(Instant.parse("2026-08-01T10:00:00Z"));
        long reference = snapshots.lastPublication().id();
        persistence.reaffecterPoste("RVM-P1", "RVM-B");
        publication.publier();

        assertThatThrownBy(() -> instantanes.deleteSnapshot(reference, null))
                .isInstanceOf(ToolCallException.class)
                .hasMessageContaining("14/08/2026")
                .hasMessageContaining("Réalisé vs planifié");
        assertThat(snapshots.list())
                .extracting(PlanSnapshotService.SnapshotMeta::id)
                .contains(reference);
    }

    private void backdateLastPublication(Instant instant) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps =
                        connection.prepareStatement("UPDATE plan_snapshot SET publie_le = ? WHERE id = ?")) {
            ps.setTimestamp(1, Timestamp.from(instant));
            ps.setLong(2, snapshots.lastPublication().id());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}
