package dev.sylvain.planning.service.analyse;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.ContrainteAdHoc;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.Edition;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.domain.TypeContrainteAdHoc;
import dev.sylvain.planning.service.EditionContext;
import dev.sylvain.planning.service.analyse.RealisedVsPlanned.GapCounts;
import dev.sylvain.planning.service.analyse.RealisedVsPlanned.PreviousEdition;
import dev.sylvain.planning.service.analyse.RealisedVsPlanned.RealisedCell;
import dev.sylvain.planning.service.analyse.RealisedVsPlanned.RealisedDay;
import dev.sylvain.planning.service.edition.EditionService;
import dev.sylvain.planning.service.journal.EntreeJournal;
import dev.sylvain.planning.service.journal.HistoryFilter;
import dev.sylvain.planning.service.journal.JournalActionRepository;
import dev.sylvain.planning.service.publication.PlanPublicationService;
import dev.sylvain.planning.service.referentiel.ReferenceDataService;
import dev.sylvain.planning.service.solve.PlanningPersistenceService;
import io.quarkus.test.junit.QuarkusTest;
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
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Réalisé vs planifié end to end, on the database: a plan published, an
 * absence recorded and its seat refilled, then the report, the CSV, the
 * refusal to delete a publication still measuring a day, and the reading of
 * a frozen measure by the next edition — including after the edition it
 * measured was deleted.
 *
 * <p>The publication instants are set by hand: the application stamps them
 * with the real clock, which would put every publication after the event.
 * The service is asked with the moment each test needs; the CSV and the
 * deletion, served over HTTP, read the real clock, for which these days are
 * long elapsed.</p>
 *
 * <p>The past is not frozen under {@code %test}: the nightly job, which
 * refuses to freeze then, is exercised by {@code RealisedFreezeJobTest} under
 * a profile that freezes it.</p>
 */
@QuarkusTest
class RealisedVsPlannedServiceTest {

    private static final LocalDate JOUR1 = LocalDate.of(2026, 7, 10);
    private static final LocalDate JOUR2 = LocalDate.of(2026, 7, 11);
    private static final LocalDate JOUR3 = LocalDate.of(2026, 7, 12);
    private static final Instant AVANT_EVENEMENT = Instant.parse("2026-07-01T10:00:00Z");

    /** The morning after the event: every one of its days is over. */
    private static final LocalDateTime APRES = JOUR3.plusDays(1).atStartOfDay();

    @Inject
    RealisedVsPlannedService service;

    @Inject
    RealisedFreezeJob job;

    @Inject
    RealisedHistoryRepository history;

    @Inject
    PlanningPersistenceService persistence;

    @Inject
    PlanPublicationService publication;

    @Inject
    ReferenceDataService referenceData;

    @Inject
    EditionService editionService;

    @Inject
    EditionContext editionContext;

    @Inject
    JournalActionRepository journal;

    @Inject
    DataSource dataSource;

    /** The editions a test created, deleted with their measure when it ends. */
    private final List<String> creees = new ArrayList<>();

    @BeforeEach
    void seed() {
        String edition = editionContext.editionIdCourant();
        execute("DELETE FROM plan_snapshot WHERE edition_id = ?", edition);
        execute("DELETE FROM kpi_realise WHERE edition_id = ?", edition);
        persistence.clearDatabase();
        persistence.persist(plan("RV-", Set.of("STRATEGIE"), 970_300_000L));
    }

    @AfterEach
    void cleanUp() {
        execute("DELETE FROM kpi_realise WHERE edition_id = ?", editionContext.editionIdCourant());
        for (String edition : creees) {
            execute("DELETE FROM kpi_realise WHERE edition_id = ?", edition);
            if (editionService.listEditions().stream()
                    .anyMatch(each -> each.getId().equals(edition))) {
                editionService.delete(edition);
            }
        }
    }

    @Test
    void beforeTheFirstPublicationTheReportSaysThereIsNoReference() {
        RealisedVsPlanned report = service.report(APRES);

        assertThat(report.referenceAvailable()).isFalse();
        assertThat(report.cells()).isEmpty();
        assertThat(report.days()).isNotEmpty().noneMatch(RealisedDay::counted);
    }

    @Test
    void anAbsenceRecordedAndRefilledShowsOnItsCellAndTodayIsNotCounted() {
        publishAt(AVANT_EVENEMENT);
        markAbsentAndReplace();

        RealisedVsPlanned report = service.report(JOUR3.atTime(9, 0));

        assertThat(report.referenceAvailable()).isTrue();
        assertThat(report.days()).extracting(RealisedDay::date).containsExactly(JOUR1, JOUR2);
        GapCounts jour1 = cell(report, JOUR1);
        assertThat(jour1.publishedSeats()).isEqualTo(1);
        assertThat(jour1.absences()).isEqualTo(1);
        assertThat(jour1.replacements()).isEqualTo(1);
        assertThat(cell(report, JOUR2).absences()).isZero();
        assertThat(report.cells()).extracting(RealisedCell::date).doesNotContain(JOUR3);
        // The stand's one game category, under the edition's own id for it.
        assertThat(report.byTypologie())
                .singleElement()
                .satisfies(total -> assertThat(total.counts().absences()).isEqualTo(1));
    }

    /** A day is elapsed once its last timeslot has ended — 12:00 on the third day — not at midnight. */
    @Test
    void aDayCountsOnceItsLastTimeslotHasEnded() {
        publishAt(AVANT_EVENEMENT);

        assertThat(service.report(JOUR3.atTime(11, 59)).days())
                .extracting(RealisedDay::date)
                .containsExactly(JOUR1, JOUR2);
        assertThat(service.report(JOUR3.atTime(12, 0)).days())
                .extracting(RealisedDay::date)
                .containsExactly(JOUR1, JOUR2, JOUR3);
        assertThat(service.isOver(JOUR3.atTime(11, 59))).isFalse();
        assertThat(service.isOver(JOUR3.atTime(12, 0))).isTrue();
    }

    @Test
    void aRepublicationDuringTheEventLeavesTheDaysAlreadyStartedAsTheyWere() {
        publishAt(AVANT_EVENEMENT);
        markAbsentAndReplace();
        GapCounts avant = cell(service.report(APRES), JOUR1);

        publishAt(JOUR2.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant());
        RealisedVsPlanned apres = service.report(APRES);

        assertThat(cell(apres, JOUR1)).isEqualTo(avant);
        assertThat(apres.days())
                .filteredOn(day -> day.date().equals(JOUR3))
                .singleElement()
                .satisfies(day -> assertThat(day.referencePublishedAt()).isAfter(AVANT_EVENEMENT));
    }

    /** The day starts at its first timeslot, 10:00: a republication at 08:00 is what it was promised. */
    @Test
    void aRepublicationBeforeTheFirstTimeslotOfADayIsItsReference() {
        publishAt(AVANT_EVENEMENT);
        persistence.reaffecterPoste("RV-P2", "RV-C");
        Instant huitHeures = JOUR2.atTime(8, 0).atZone(ZoneId.systemDefault()).toInstant();
        publishAt(huitHeures);

        RealisedVsPlanned report = service.report(APRES);

        assertThat(report.days())
                .filteredOn(day -> day.date().equals(JOUR2))
                .singleElement()
                .satisfies(day -> {
                    assertThat(day.referencePublishedAt()).isEqualTo(huitHeures);
                    assertThat(day.lateReference()).isFalse();
                    assertThat(day.counted()).isTrue();
                });
    }

    /**
     * The first publication still measures the first day once the second
     * replaced it: deleting it would hand that day to the second, which
     * already holds Chloé — the absence would vanish from the measure.
     */
    @Test
    void deletingAPublicationThatMeasuresAnElapsedDayIsRefused() {
        long anterieure = publishAt(AVANT_EVENEMENT.minusSeconds(3600));
        persistence.reaffecterPoste("RV-P3", "RV-C");
        long premiere = publishAt(AVANT_EVENEMENT);
        markAbsentAndReplace();
        publishAt(JOUR2.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant());

        given().header("X-Edition-Id", "E1")
                .when()
                .delete("/api/planning/snapshots/" + premiere)
                .then()
                .statusCode(409)
                .body("message", containsString("10/07/2026"))
                .body("message", containsString("Réalisé vs planifié"));
        // A publication no elapsed day was measured against goes like any snapshot.
        given().header("X-Edition-Id", "E1")
                .when()
                .delete("/api/planning/snapshots/" + anterieure)
                .then()
                .statusCode(204);

        GapCounts jour1 = cell(service.report(APRES), JOUR1);
        assertThat(jour1.absences()).isEqualTo(1);
        assertThat(jour1.replacements()).isEqualTo(1);
    }

    @Test
    void theExportCarriesNoPersonAndIsJournalled() {
        publishAt(AVANT_EVENEMENT);
        markAbsentAndReplace();

        String csv = given().header("X-Edition-Id", "E1")
                .when()
                .get("/api/planning/realise/export")
                .then()
                .statusCode(200)
                .extract()
                .asString();

        assertThat(csv).contains("2026-07-10;Stand réalisé;1;1;1;1;");
        for (String personne : List.of("RV-A", "RV-B", "RV-C", "Alice", "Bruno", "Chloé", "Martin")) {
            assertThat(csv).doesNotContain(personne);
        }
        assertThat(journal.page(new HistoryFilter(List.of("EXPORT_REALISE"), false, null, null), null, 10))
                .extracting(EntreeJournal::action)
                .contains("EXPORT_REALISE");
    }

    /** {@code %test} does not freeze the past: a measure written once must not rest on that. */
    @Test
    void theNightlyJobDoesNotFreezeWhileThePastIsNotFrozen() {
        publishAt(AVANT_EVENEMENT);
        String edition = editionContext.editionIdCourant();

        job.run(APRES);

        assertThat(service.report(APRES).frozenPast()).isFalse();
        assertThat(rows(edition)).isEmpty();
    }

    /**
     * The measure outlives its edition, as the KPI history does, and the next
     * edition reads it as its predecessor's — chosen by the dates of the
     * events, the only order an edition has.
     */
    @Test
    void theMeasureSurvivesTheDeletionOfItsEditionAndTheNextEditionReadsIt() {
        LocalDate fin = JOUR1.minusYears(1).plusDays(2);
        String ancienne = frozenEdition("Édition réalisé passée", fin, 3, 3);
        editionService.delete(ancienne);

        assertThat(rows(ancienne)).hasSize(1);
        PreviousEdition precedente = service.previousEdition();
        assertThat(precedente.available()).isTrue();
        assertThat(precedente.editionId()).isEqualTo(ancienne);
        assertThat(precedente.editionNom()).isEqualTo("Édition réalisé passée");
        assertThat(precedente.lastDay()).isEqualTo(fin);
        assertThat(precedente.event().publishedSeats()).isEqualTo(3);
    }

    /**
     * Two events ending the same day — a variant duplicated from the real
     * edition: the measure covering more days wins, then the one frozen
     * first, whatever order the editions were met in.
     */
    @Test
    void twoEventsEndingTheSameDayAreToldApartByTheirCountedDaysThenTheFirstFrozen() {
        LocalDate fin = JOUR1.minusYears(1).plusDays(2);
        String variante = frozenEdition("Variante publiée tard", fin, 1, 5);
        String reelle = frozenEdition("Édition réelle", fin, 3, 7);

        assertThat(service.previousEdition().editionId()).isEqualTo(reelle);

        execute("UPDATE kpi_realise SET jours_comptes = 3 WHERE edition_id = ?", variante);
        execute("UPDATE kpi_realise SET fige_le = fige_le + interval '1 day' WHERE edition_id = ?", reelle);
        assertThat(service.previousEdition().editionId()).isEqualTo(variante);
    }

    /* ------------------------------- Helpers ------------------------------- */

    private static PlanningEvenement plan(String prefix, Set<String> typologies, long creneauBase) {
        return planOn(prefix, typologies, creneauBase, JOUR1);
    }

    /** Three days from {@code premierJour}, one seat a day, the same stand. */
    static PlanningEvenement planOn(String prefix, Set<String> typologies, long creneauBase, LocalDate premierJour) {
        Animateur alice = new Animateur(prefix + "A", "Alice", "Martin", LocalDate.of(1990, 1, 1), false);
        Animateur bruno = new Animateur(prefix + "B", "Bruno", "Petit", LocalDate.of(1992, 2, 2), false);
        Animateur chloe = new Animateur(prefix + "C", "Chloé", "Roy", LocalDate.of(1993, 3, 3), false);
        Stand stand = new Stand(prefix + "S1", "Stand réalisé", typologies, 1, 1, false);
        List<PosteAffectation> postes = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            Creneau creneau = new Creneau(
                    creneauBase + i, i + 1, premierJour.plusDays(i), LocalTime.of(10, 0), LocalTime.of(12, 0));
            PosteAffectation poste = new PosteAffectation(prefix + "P" + (i + 1), stand, creneau);
            poste.setAnimateur(i < 2 ? alice : bruno);
            postes.add(poste);
        }
        return new PlanningEvenement(premierJour, List.of(alice, bruno, chloe), postes);
    }

    /** Publishes the persisted plan, then dates the publication {@code instant}; returns its snapshot id. */
    private long publishAt(Instant instant) {
        publication.publier();
        // The publication just made is the newest row, whatever instants the
        // others were already given by hand.
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement("""
                        UPDATE plan_snapshot SET publie_le = ?
                        WHERE id = (SELECT max(id) FROM plan_snapshot
                                    WHERE edition_id = ? AND publie_le IS NOT NULL)
                        RETURNING id""")) {
            ps.setTimestamp(1, Timestamp.from(instant));
            ps.setString(2, editionContext.editionIdCourant());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * A finished edition with its measure frozen as the job writes it — one
     * event row of {@code sieges} published seats over {@code joursComptes}
     * days, ending {@code fin}.
     */
    private String frozenEdition(String nom, LocalDate fin, int joursComptes, int sieges) {
        Edition edition = editionService.create(new Edition(null, nom, false, null));
        creees.add(edition.getId());
        GapCounts counts = GapCounts.of(sieges, 0, 0, 0, 0, 0, sieges * 120, sieges * 120, 0);
        List<RealisedDay> jours = new ArrayList<>();
        for (int i = 0; i < joursComptes; i++) {
            jours.add(new RealisedDay(fin.minusDays(i), true, AVANT_EVENEMENT, false));
        }
        RealisedVsPlanned report = new RealisedVsPlanned(
                true,
                RealisedSource.RealisedNature.DECLARED,
                true,
                fin.plusDays(1),
                jours,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                counts);
        editionContext.executeIn(
                edition.getId(), () -> history.insert(nom, fin.minusDays(2), fin, report, Instant.now()));
        return edition.getId();
    }

    /** The mode jour J's two writes: Alice missing on day one, Chloé on her seat. */
    private void markAbsentAndReplace() {
        ContrainteAdHoc absence = new ContrainteAdHoc(null, TypeContrainteAdHoc.INDISPONIBILITE_FORCEE);
        Animateur cible = new Animateur();
        cible.setId("RV-A");
        absence.setAnimateursConcernes(new ArrayList<>(List.of(cible)));
        Creneau creneau = new Creneau();
        creneau.setId(970_300_000L);
        absence.setCreneau(creneau);
        absence.setRaison("Absente (mode jour J)");
        referenceData.createContrainteAdHoc(absence);
        persistence.reaffecterPoste("RV-P1", "RV-C");
    }

    private static GapCounts cell(RealisedVsPlanned report, LocalDate date) {
        return report.cells().stream()
                .filter(cell -> cell.date().equals(date))
                .map(RealisedCell::counts)
                .findFirst()
                .orElseThrow();
    }

    /** The frozen rows of an edition, figures only — the freeze instant left out on purpose. */
    private List<String> rows(String editionId) {
        List<String> rows = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement("""
                        SELECT typologie_id, premier_jour, dernier_jour, jours_comptes, sieges_publies, absences,
                               remplacements, sieges_vides, minutes_perdues
                        FROM kpi_realise WHERE edition_id = ? ORDER BY typologie_id""")) {
            ps.setString(1, editionId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    StringBuilder row = new StringBuilder(rs.getString(1));
                    for (int i = 2; i <= 9; i++) {
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
