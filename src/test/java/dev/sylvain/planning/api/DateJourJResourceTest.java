package dev.sylvain.planning.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import dev.sylvain.planning.config.DevMode;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;

/**
 * Freezing the server's notion of today (issue #297), and the rule that keeps
 * it out of production.
 *
 * <p>The mock exists because the mode jour J screen can otherwise only be
 * exercised on the day of the event. It is dangerous for exactly the same
 * reason: a deployed instance whose date is frozen shows a schedule of another
 * day as if it were now. So the two halves are tested together — it works where
 * it is allowed, and is refused where it is not.</p>
 *
 * <p>{@code LaunchMode.current()} answers {@code TEST} here, never
 * {@code DEVELOPMENT}, which is why the guard is a bean: the refusal is the
 * default state of this class, and the permission is installed explicitly for
 * the tests that need it.</p>
 */
@QuarkusTest
class DateJourJResourceTest {

    private static final String JOUR = "2026-07-08";

    /** A server launched with {@code quarkus:dev}, as far as the guard can tell. */
    private static final class DevModeActif extends DevMode {
        @Override
        public boolean isActive() {
            return true;
        }
    }

    @AfterEach
    void handTheClockBack() {
        QuarkusMock.installMockForType(new DevModeActif(), DevMode.class);
        given().contentType("application/json").body("{\"dateDuJour\":null}")
                .when().put("/api/debug/date-du-jour").then().statusCode(200);
        given().when().post("/api/planning/reset").then().statusCode(200);
    }

    /* ------------------------------- The guard ----------------------------- */

    /**
     * The test that matters. {@code /debug} is an ordinary admin route, so a
     * deployed instance is one click away from a frozen clock unless the
     * <em>server</em> says no.
     */
    @Test
    void aServerOutsideDevModeRefusesToFreezeTheDate() {
        given().contentType("application/json").body("{\"dateDuJour\":\"" + JOUR + "\"}")
                .when().put("/api/debug/date-du-jour")
                .then().statusCode(400)
                .body("message", containsString("développement"));

        given().when().get("/api/debug/date-du-jour")
                .then().statusCode(200)
                .body("dateDuJour", nullValue())
                .body("modifiable", equalTo(false));
    }

    /**
     * Even clearing it is refused outside dev mode. Not pedantry: an endpoint
     * that accepts half its inputs on a deployed instance is an endpoint whose
     * rule has to be read twice to be believed.
     */
    @Test
    void aServerOutsideDevModeRefusesToClearItEither() {
        given().contentType("application/json").body("{\"dateDuJour\":null}")
                .when().put("/api/debug/date-du-jour")
                .then().statusCode(400);
    }

    @Test
    void devModeAdvertisesThatTheFieldMayBeUsed() {
        QuarkusMock.installMockForType(new DevModeActif(), DevMode.class);

        given().when().get("/api/debug/date-du-jour")
                .then().statusCode(200).body("modifiable", equalTo(true));
    }

    /* ------------------------------ The setting ---------------------------- */

    @Test
    void theFrozenDateIsStoredAndReadBack() {
        QuarkusMock.installMockForType(new DevModeActif(), DevMode.class);

        given().contentType("application/json").body("{\"dateDuJour\":\"" + JOUR + "\"}")
                .when().put("/api/debug/date-du-jour")
                .then().statusCode(200).body("dateDuJour", equalTo(JOUR));

        given().when().get("/api/debug/date-du-jour")
                .then().statusCode(200).body("dateDuJour", equalTo(JOUR));
    }

    /** The empty field is how the clock is handed back, so it cannot be an error. */
    @Test
    void anEmptyValueHandsTheRealClockBack() {
        QuarkusMock.installMockForType(new DevModeActif(), DevMode.class);
        given().contentType("application/json").body("{\"dateDuJour\":\"" + JOUR + "\"}")
                .when().put("/api/debug/date-du-jour").then().statusCode(200);

        given().contentType("application/json").body("{\"dateDuJour\":\"\"}")
                .when().put("/api/debug/date-du-jour")
                .then().statusCode(200).body("dateDuJour", nullValue());

        given().when().get("/api/debug/date-du-jour")
                .then().statusCode(200).body("dateDuJour", nullValue());
    }

    @Test
    void anUnreadableDateIsA400() {
        QuarkusMock.installMockForType(new DevModeActif(), DevMode.class);

        given().contentType("application/json").body("{\"dateDuJour\":\"le 8 juillet\"}")
                .when().put("/api/debug/date-du-jour")
                .then().statusCode(400).body("message", notNullValue());
    }

    /* ---------------------- What the mock actually moves -------------------- */

    /**
     * The whole point: with the date frozen, the mode jour J screen answers on
     * that day without being told which one — the same call the interface
     * makes, with no parameters.
     */
    @Test
    void theFrozenDateIsWhatTheEventDayScreenReads() {
        solveScenario();
        QuarkusMock.installMockForType(new DevModeActif(), DevMode.class);
        given().contentType("application/json").body("{\"dateDuJour\":\"" + JOUR + "\"}")
                .when().put("/api/debug/date-du-jour").then().statusCode(200);

        JsonPath etat = given().when().get("/api/jour-j")
                .then().statusCode(200).extract().jsonPath();

        assertThat(etat.getString("date")).isEqualTo(JOUR);
        assertThat(etat.getInt("creneauxDuJour")).isEqualTo(2);
    }

    /**
     * And it really drives the scope, not just the label: which timeslots count
     * as remaining follows the frozen date, and an absence marked with no
     * parameters lands on them.
     */
    @Test
    void theFrozenDateDrivesWhichTimeslotsCountAsRemaining() {
        solveScenario();
        QuarkusMock.installMockForType(new DevModeActif(), DevMode.class);
        given().contentType("application/json").body("{\"dateDuJour\":\"" + JOUR + "\"}")
                .when().put("/api/debug/date-du-jour").then().statusCode(200);

        // The scenario's only two timeslots are on that day: on it, at least one
        // is still ahead unless the wall clock is past 18:00 — so the assertion
        // is made on the day itself, where the mock is what put us.
        JsonPath etat = given().when().get("/api/jour-j?heure=13:30")
                .then().statusCode(200).extract().jsonPath();
        List<String> restants = etat.getList("creneauxRestants.heureDebut", String.class);
        assertThat(restants).singleElement().asString().startsWith("14:00");

        String absent = etat.getList("animateursDeService.animateurId", String.class).getFirst();
        JsonPath marquee = given().contentType("application/json")
                .body("{\"animateurId\":\"" + absent + "\"}")
                .when().post("/api/jour-j/absences?heure=13:30")
                .then().statusCode(200).extract().jsonPath();

        // One unavailability, on the afternoon timeslot of the frozen day.
        assertThat(marquee.getList("entrees.creneauId", Integer.class)).hasSize(1);
        assertThat(marquee.getList("entrees.heureDebut", String.class))
                .singleElement().asString().startsWith("14:00");
    }

    /**
     * Handing the clock back really hands it back: the screen answers on the
     * machine's date again, which the scenario has no timeslots on.
     */
    @Test
    void clearingTheFieldReturnsToTheRealDate() {
        solveScenario();
        QuarkusMock.installMockForType(new DevModeActif(), DevMode.class);
        given().contentType("application/json").body("{\"dateDuJour\":\"" + JOUR + "\"}")
                .when().put("/api/debug/date-du-jour").then().statusCode(200);
        given().contentType("application/json").body("{\"dateDuJour\":null}")
                .when().put("/api/debug/date-du-jour").then().statusCode(200);

        JsonPath etat = given().when().get("/api/jour-j").then().statusCode(200).extract().jsonPath();

        // Not "equals today": this suite may straddle midnight, and a test that
        // fails once a night is a test nobody believes.
        LocalDate lue = LocalDate.parse(etat.getString("date"));
        assertThat(lue).isNotEqualTo(LocalDate.parse(JOUR));
        assertThat(lue).isBetween(LocalDate.now().minusDays(1), LocalDate.now().plusDays(1));
    }

    /**
     * The mock moves the date and nothing else: the trace of what was written
     * still says when it really was. A record that lies about its own timestamp
     * is worse than no record.
     */
    @Test
    void theTraceOfAnAbsenceKeepsTheRealTimestamp() {
        solveScenario();
        QuarkusMock.installMockForType(new DevModeActif(), DevMode.class);
        given().contentType("application/json").body("{\"dateDuJour\":\"" + JOUR + "\"}")
                .when().put("/api/debug/date-du-jour").then().statusCode(200);
        String absent = given().when().get("/api/jour-j?heure=13:30").then().statusCode(200)
                .extract().jsonPath().getList("animateursDeService.animateurId", String.class).getFirst();
        given().contentType("application/json").body("{\"animateurId\":\"" + absent + "\"}")
                .when().post("/api/jour-j/absences?heure=13:30").then().statusCode(200);

        String creeLe = given().when().get("/api/contraintes-ad-hoc").then().statusCode(200)
                .extract().jsonPath()
                .getString("find { it.type == 'INDISPONIBILITE_FORCEE' }.creeLe");

        // Compared as an instant, not as a date prefix: creeLe is UTC and the
        // server's day may already have turned over locally.
        assertThat(Instant.parse(creeLe)).as("written now, not on the frozen day")
                .isCloseTo(Instant.now(), within(10, ChronoUnit.MINUTES));
    }

    private static void solveScenario() {
        String sample = given().when().get("/api/planning/sample?name=scenario.yml")
                .then().statusCode(200).extract().asString();
        given().contentType("application/json").body(sample)
                .when().post("/api/solve?seconds=3").then().statusCode(200);
    }
}
