package dev.sylvain.planning.service.analyse;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.Edition;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.service.EditionContext;
import dev.sylvain.planning.service.analyse.RealisedVsPlanned.GapCounts;
import dev.sylvain.planning.service.analyse.RealisedVsPlanned.RealisedCell;
import dev.sylvain.planning.service.edition.EditionRepository;
import dev.sylvain.planning.service.edition.EditionService;
import dev.sylvain.planning.service.espace.JourJClock;
import dev.sylvain.planning.service.journal.HistoryFilter;
import dev.sylvain.planning.service.journal.JournalActionRepository;
import dev.sylvain.planning.service.publication.PlanPublicationService;
import dev.sylvain.planning.service.publication.PlanPublieService;
import dev.sylvain.planning.service.referentiel.ReferenceDataService;
import dev.sylvain.planning.service.solve.PlanSnapshotService;
import dev.sylvain.planning.service.solve.PlanningPersistenceService;
import dev.sylvain.planning.service.solve.PlanningService;
import dev.sylvain.planning.testing.ActiveEdition;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The nightly freeze of the Réalisé vs planifié measure, and the mode jour
 * J's split carried into that measure — both under the profile of a staging
 * server: the past frozen (the job refuses to freeze otherwise) and the clock
 * allowed to be set (the split cuts a seat at « now »).
 *
 * <p>The events are a year ahead of the real clock, so the frozen past never
 * pins a seat this class writes unless it sets the clock on purpose.</p>
 */
@QuarkusTest
@TestProfile(RealisedFreezeJobTest.PastFrozenOnAStagingServer.class)
class RealisedFreezeJobTest {

    /** The freeze on, and the clock allowed to be set — as {@code FrozenPastAcceptanceTest} configures it. */
    public static class PastFrozenOnAStagingServer implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("planning.solver.passe-fige", "true", "planning.horloge-simulee.autorisee", "true");
        }
    }

    private static final LocalDate JOUR1 = LocalDate.of(2027, 8, 9);
    private static final LocalDate JOUR3 = JOUR1.plusDays(2);
    private static final Instant AVANT_EVENEMENT = Instant.parse("2027-08-01T10:00:00Z");

    @Inject
    RealisedFreezeJob job;

    @Inject
    RealisedVsPlannedService service;

    @Inject
    RealisedSource source;

    @Inject
    PlanSnapshotService snapshotService;

    @Inject
    PlanPublieService planPublieService;

    @Inject
    ReferenceDataService referenceData;

    @Inject
    PlanningService planningService;

    @Inject
    JourJClock clock;

    @Inject
    RealisedHistoryRepository history;

    @Inject
    PlanningPersistenceService persistence;

    @Inject
    PlanPublicationService publication;

    @Inject
    EditionService editionService;

    @Inject
    EditionRepository editionRepository;

    @Inject
    EditionContext editionContext;

    @Inject
    JournalActionRepository journal;

    @Inject
    DataSource dataSource;

    /** The editions a test created, deleted when it ends. */
    private final List<String> creees = new ArrayList<>();

    /** When the test started: the rows the job wrote since are this test's, whichever edition they measure. */
    private Instant debut;

    @BeforeEach
    void seed() {
        debut = Instant.now();
        String edition = editionContext.editionIdCourant();
        execute("DELETE FROM plan_snapshot WHERE edition_id = ?", edition);
        execute("DELETE FROM kpi_realise WHERE edition_id = ?", edition);
        persistence.clearDatabase();
    }

    @AfterEach
    void cleanUp() {
        clock.setMocked(null, null);
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement("DELETE FROM kpi_realise WHERE fige_le >= ?")) {
            ps.setTimestamp(1, Timestamp.from(debut));
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        execute("DELETE FROM kpi_realise WHERE edition_id = ?", editionContext.editionIdCourant());
        for (String edition : creees) {
            editionService.delete(edition);
        }
        persistence.clearDatabase();
    }

    /**
     * Written the first night the event is over, and never again: the change
     * made the morning after leaves the frozen figures — and the instant they
     * were frozen — as they were.
     */
    @Test
    void theJobFreezesAFinishedEventOnceAndNeverRewritesIt() {
        persistence.persist(RealisedVsPlannedServiceTest.planOn("RVJ-", Set.of("STRATEGIE"), 970_340_000L, JOUR1));
        publishAt(AVANT_EVENEMENT);
        String edition = editionContext.editionIdCourant();
        int journalAvant = journal.page(new HistoryFilter(List.of("REALISE_FIGE"), false, null, null), null, 1000)
                .size();

        job.run(JOUR3.atTime(11, 0));
        assertThat(rows(edition)).as("the last timeslot runs until 12:00").isEmpty();

        job.run(JOUR3.atTime(12, 0));
        List<String> premiere = rows(edition);
        persistence.reaffecterPoste("RVJ-P2", "RVJ-C");
        job.run(JOUR3.plusDays(1).atTime(3, 30));

        assertThat(premiere).hasSize(2).anyMatch(row -> row.startsWith("|")).anyMatch(row -> !row.startsWith("|"));
        assertThat(rows(edition)).isEqualTo(premiere);
        assertThat(journal.page(new HistoryFilter(List.of("REALISE_FIGE"), false, null, null), null, 1000))
                .hasSize(journalAvant + 1);
    }

    @Test
    void anEditionNeverPublishedIsNotFrozen() {
        persistence.persist(RealisedVsPlannedServiceTest.planOn("RVJ-", Set.of(), 970_340_000L, JOUR1));
        String edition = editionContext.editionIdCourant();

        job.run(JOUR3.plusDays(1).atStartOfDay());

        assertThat(rows(edition)).isEmpty();
    }

    /**
     * The edition the job meets first fails: the next one is frozen all the
     * same, each edition being entered in its own {@code try/catch}.
     */
    @Test
    void oneEditionsFailureDoesNotCostTheOthersTheirMeasure() {
        persistence.persist(RealisedVsPlannedServiceTest.planOn("RVJ-", Set.of(), 970_340_000L, JOUR1));
        publishAt(AVANT_EVENEMENT);
        Edition autre = editionService.create(new Edition(null, "Édition réalisé job", false, null));
        creees.add(autre.getId());
        // Publishing reaches outside: only the active edition does it (ADR 0072).
        // The job itself then walks every edition, active or not.
        ActiveEdition.during(
                autre.getId(),
                () -> editionContext.executeIn(autre.getId(), () -> {
                    persistence.persist(RealisedVsPlannedServiceTest.planOn("RVK-", Set.of(), 970_350_000L, JOUR1));
                    publishAt(AVANT_EVENEMENT);
                }));
        List<String> nos = List.of(editionContext.editionIdCourant(), autre.getId());
        String enEchec = editionRepository.listEditions().stream()
                .map(Edition::getId)
                .filter(nos::contains)
                .findFirst()
                .orElseThrow();
        String suivante =
                nos.stream().filter(id -> !id.equals(enEchec)).findFirst().orElseThrow();
        QuarkusMock.installMockForType(new FailingIn(enEchec), RealisedVsPlannedService.class);

        job.run(JOUR3.plusDays(1).atStartOfDay());

        assertThat(rows(enEchec)).isEmpty();
        assertThat(rows(suivante)).isNotEmpty();
    }

    /**
     * The mode jour J end to end into the measure (ADR 0066): Camille holds
     * the morning and the afternoon of the stand and is marked absent at
     * 11:00. The morning seat is split — she keeps 10-11 —, its remainder and
     * the afternoon come back empty: two absences, two seats left empty,
     * nothing removed, nothing added, one hour held, five lost.
     */
    @Test
    void anAbsenceMarkedOnTheDaySplitsASeatAndCountsTwiceInTheMeasure() {
        Animateur camille = new Animateur("RVJ-CAM", "Camille", "Durand", LocalDate.of(1990, 1, 1), false);
        Animateur sasha = new Animateur("RVJ-SAS", "Sasha", "Roy", LocalDate.of(1992, 3, 3), false);
        Stand cirque = new Stand("RVJ-CIRQUE", "Cirque réalisé", Set.of(), 1, 1, false);
        PosteAffectation matin = new PosteAffectation(
                "RVJ-MATIN", cirque, new Creneau(970_360_000L, 1, JOUR1, LocalTime.of(10, 0), LocalTime.of(12, 0)));
        PosteAffectation apresMidi = new PosteAffectation(
                "RVJ-APREM", cirque, new Creneau(970_360_001L, 1, JOUR1, LocalTime.of(14, 0), LocalTime.of(18, 0)));
        matin.setAnimateur(camille);
        apresMidi.setAnimateur(camille);
        persistence.persist(new PlanningEvenement(JOUR1, List.of(camille, sasha), List.of(matin, apresMidi)));
        publishAt(AVANT_EVENEMENT);
        clock.setMocked(JOUR1, LocalTime.of(11, 0));

        given().header("X-Edition-Id", "E1")
                .contentType(ContentType.JSON)
                .body(Map.of("animateurId", "RVJ-CAM", "raison", "Malade"))
                .when()
                .post("/api/jour-j/absences?date=" + JOUR1 + "&maintenant=" + JOUR1 + "T11:00")
                .then()
                .statusCode(200);
        GapCounts counts = cell(service.report(JOUR1.atTime(19, 0)), JOUR1);

        assertThat(counts.publishedSeats()).isEqualTo(2);
        assertThat(counts.absences()).isEqualTo(2);
        assertThat(counts.emptySeats()).isEqualTo(2);
        assertThat(counts.removedSeats()).isZero();
        assertThat(counts.addedSeats()).isZero();
        assertThat(counts.replacements()).isZero();
        assertThat(counts.realisedMinutes()).isEqualTo(60);
        assertThat(counts.lostMinutes()).isEqualTo(60 + 240);
    }

    /* ------------------------------- Helpers ------------------------------- */

    /** The real service, refusing the one edition it is told to fail on. */
    private final class FailingIn extends RealisedVsPlannedService {

        private final String editionId;

        FailingIn(String editionId) {
            super(source, snapshotService, planPublieService, referenceData, planningService, clock, history);
            this.editionId = editionId;
        }

        @Override
        public RealisedVsPlanned report(LocalDateTime now) {
            if (editionId.equals(editionContext.editionIdCourant())) {
                throw new IllegalStateException("Simulated failure of edition " + editionId);
            }
            return super.report(now);
        }
    }

    /** Publishes the persisted plan, then dates the publication {@code instant}. */
    private void publishAt(Instant instant) {
        publication.publier();
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement("""
                        UPDATE plan_snapshot SET publie_le = ?
                        WHERE id = (SELECT max(id) FROM plan_snapshot
                                    WHERE edition_id = ? AND publie_le IS NOT NULL)""")) {
            ps.setTimestamp(1, Timestamp.from(instant));
            ps.setString(2, editionContext.editionIdCourant());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static GapCounts cell(RealisedVsPlanned report, LocalDate date) {
        return report.cells().stream()
                .filter(cell -> cell.date().equals(date))
                .map(RealisedCell::counts)
                .findFirst()
                .orElseThrow();
    }

    /** The frozen rows of an edition, the freeze instant included: written once, it never moves. */
    private List<String> rows(String editionId) {
        List<String> rows = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement("""
                        SELECT typologie_id, premier_jour, dernier_jour, jours_comptes, sieges_publies, absences,
                               remplacements, sieges_vides, minutes_perdues, fige_le
                        FROM kpi_realise WHERE edition_id = ? ORDER BY typologie_id""")) {
            ps.setString(1, editionId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    StringBuilder row = new StringBuilder(rs.getString(1));
                    for (int i = 2; i <= 10; i++) {
                        row.append('|').append(rs.getString(i));
                    }
                    rows.add(row.toString());
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return rows;
    }

    private void execute(String sql, String editionId) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, editionId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to run " + sql, e);
        }
    }
}
