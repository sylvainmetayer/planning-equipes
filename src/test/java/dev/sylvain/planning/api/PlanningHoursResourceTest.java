package dev.sylvain.planning.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.service.solve.PlanSnapshotService;
import dev.sylvain.planning.service.solve.PlanningPersistenceService;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@code GET /api/planning/hours} reads a plan the server holds — the persisted
 * one or the publication in force — and never one a client sends.
 */
@QuarkusTest
class PlanningHoursResourceTest {

    /** Saturday 11 July 2026. */
    private static final LocalDate SAMEDI = LocalDate.of(2026, 7, 11);

    private static final Animateur ALICE = new Animateur("HR-A", "Alice", "Martin", LocalDate.of(1990, 1, 1), false);
    private static final Animateur BRUNO = new Animateur("HR-B", "Bruno", "Petit", LocalDate.of(1992, 2, 2), false);
    private static final Stand STAND = new Stand("HR-S1", "Stand des heures", Set.of(), 1, 1, false);
    private static final Creneau MATIN = new Creneau(9701L, 1, SAMEDI, LocalTime.of(9, 0), LocalTime.of(12, 0));

    @Inject
    PlanningPersistenceService persistence;

    @Inject
    PlanSnapshotService snapshots;

    @Inject
    DataSource dataSource;

    /**
     * Snapshots survive a reset — that is what they are for — so a publication
     * another class left behind would pass for this one's. The suite shares one
     * database: cleared on both sides.
     */
    @BeforeEach
    void seed() {
        forgetSnapshots();
        persistence.clearDatabase();
    }

    @AfterEach
    void cleanUp() {
        forgetSnapshots();
        persistence.clearDatabase();
    }

    @Test
    void beforeAnyPublicationTheReportReadsThePersistedPlanAndSaysSo() {
        persistHeldBy(ALICE);

        given().header("X-Edition-Id", "E1")
                .when()
                .get("/api/planning/hours")
                .then()
                .statusCode(200)
                .body("source", equalTo("persiste"))
                .body("publicationAvailable", equalTo(false))
                .body("planDate", notNullValue())
                .body("report.animateurs.find { it.animateurId == 'HR-A' }.total", equalTo(3.0f));
    }

    @Test
    void thePublishedSourceIsRefusedWhileNothingWasPublished() {
        persistHeldBy(ALICE);

        given().header("X-Edition-Id", "E1")
                .when()
                .get("/api/planning/hours?source=publie")
                .then()
                .statusCode(409)
                .body(containsString("Aucun planning n'a encore été publié"));
        given().header("X-Edition-Id", "E1")
                .when()
                .get("/api/planning/hours/export?source=publie")
                .then()
                .statusCode(409);
    }

    @Test
    void anUnknownSourceIsRefusedRatherThanReadAsTheDefault() {
        given().header("X-Edition-Id", "E1")
                .when()
                .get("/api/planning/hours?source=navigateur")
                .then()
                .statusCode(400)
                .body(containsString("navigateur"));
    }

    /**
     * The acceptance criterion: the published report does not move between two
     * calls while nobody publishes, whatever happens to the working plan.
     */
    @Test
    void thePublishedReportStaysTheSameUntilTheNextPublication() {
        persistHeldBy(ALICE);
        snapshots.capturePubliee("Publication des heures");
        String premier = publishedReport();

        // The working plan moves on: Bruno now holds the seat Alice was sent.
        persistHeldBy(BRUNO);

        assertThat(publishedReport()).isEqualTo(premier);
        given().header("X-Edition-Id", "E1")
                .when()
                .get("/api/planning/hours")
                .then()
                .statusCode(200)
                .body("source", equalTo("publie"))
                .body("publicationAvailable", equalTo(true))
                .body("report.animateurs.find { it.animateurId == 'HR-A' }.total", equalTo(3.0f))
                .body("report.animateurs.find { it.animateurId == 'HR-B' }.total", equalTo(0.0f));
        given().header("X-Edition-Id", "E1")
                .when()
                .get("/api/planning/hours?source=persiste")
                .then()
                .statusCode(200)
                .body("source", equalTo("persiste"))
                .body("publicationAvailable", equalTo(true))
                .body("report.animateurs.find { it.animateurId == 'HR-A' }.total", equalTo(0.0f))
                .body("report.animateurs.find { it.animateurId == 'HR-B' }.total", equalTo(3.0f));

        // A new publication is what moves it.
        snapshots.capturePubliee("Publication suivante");
        assertThat(publishedReport()).isNotEqualTo(premier);
    }

    @Test
    void theCsvNamesItsSourceAndItsDateOnItsFirstLine() {
        persistHeldBy(ALICE);

        String enregistre = csv("/api/planning/hours/export?source=persiste");
        assertThat(enregistre.lines().findFirst().orElseThrow())
                .matches("Source : plan enregistré, résolu le \\d{2}/\\d{2}/\\d{4} à \\d{2}:\\d{2}");
        assertThat(enregistre.lines().skip(1).findFirst().orElseThrow()).startsWith("animateur;2026-W28;total;");

        snapshots.capturePubliee("Publication des heures");
        String publie = csv("/api/planning/hours/export");
        assertThat(publie.lines().findFirst().orElseThrow())
                .matches("Source : plan publié le \\d{2}/\\d{2}/\\d{4} à \\d{2}:\\d{2}");
        assertThat(publie).contains("\nAlice Martin;3,00;3,00;");
    }

    @Test
    void theRemovedPostRoutesNoLongerComputeOnAPlanTheClientSends() {
        given().header("X-Edition-Id", "E1")
                .contentType("application/json")
                .body("{}")
                .when()
                .post("/api/planning/hours")
                .then()
                .statusCode(405);
    }

    @Test
    void anEditionNeverSolvedSaysSoInsteadOfInventingADate() {
        given().header("X-Edition-Id", "E1")
                .when()
                .get("/api/planning/hours?source=persiste")
                .then()
                .statusCode(200)
                .body("planDate", nullValue());
        assertThat(csv("/api/planning/hours/export?source=persiste"))
                .startsWith("Source : plan enregistré, jamais résolu\n");
    }

    private void persistHeldBy(Animateur titulaire) {
        PosteAffectation poste = new PosteAffectation("HR-P1", STAND, MATIN);
        poste.setAnimateur(titulaire);
        persistence.persist(new PlanningEvenement(SAMEDI, List.of(ALICE, BRUNO), List.of(poste)));
    }

    private static String publishedReport() {
        return given().header("X-Edition-Id", "E1")
                .when()
                .get("/api/planning/hours?source=publie")
                .then()
                .statusCode(200)
                .body("source", equalTo("publie"))
                .body("planDate", notNullValue())
                .extract()
                .asString();
    }

    /** The CSV as text, its byte order mark taken off. */
    private static String csv(String path) {
        byte[] corps = given().header("X-Edition-Id", "E1")
                .when()
                .get(path)
                .then()
                .statusCode(200)
                .header("Content-Disposition", equalTo("attachment; filename=\"heures-planning.csv\""))
                .extract()
                .asByteArray();
        assertThat(corps).startsWith((byte) 0xEF, (byte) 0xBB, (byte) 0xBF);
        return new String(corps, StandardCharsets.UTF_8).substring(1);
    }

    private void forgetSnapshots() {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM plan_snapshot");
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to clear the plan snapshots", e);
        }
    }
}
