package dev.sylvain.planning.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.config.EncoderConfig;
import io.restassured.http.ContentType;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * A recurring rule scoped to precise dates holds as many dates as an event has
 * days. V37 kept them as comma-separated text in a {@code VARCHAR(512)}, which
 * refused the 47th date with a 500; V122 made the column a {@code date[]}.
 * Every write of a rule — the stand form, MCP, the scenario import, the
 * compaction — lands in the same repository method, so the round trips below
 * are about storage: the REST write and read, the edition duplication that
 * copies the column as is, and the SQL dump that writes it as a literal.
 */
@QuarkusTest
class StandHoraireDatesResourceTest {

    private static final String HEADER = "X-Edition-Id";

    /** A stand open one day out of two over a 240-day season: well past the 46 dates V37 could hold. */
    private static final List<String> DATES = IntStream.range(0, 120)
            .mapToObj(i -> LocalDate.of(2026, 5, 1).plusDays(2L * i).toString())
            .toList();

    @Test
    void aRuleOfOneHundredAndTwentyDatesIsSavedAndReadBack() {
        String edition = createEdition("Horaires longs");
        String stand = createStandWithDatesRule(edition);

        assertThat(readDates(edition, stand)).containsExactlyElementsOf(DATES);
    }

    @Test
    void aDuplicatedEditionKeepsEveryDateOfTheRule() {
        String source = createEdition("Horaires longs à dupliquer");
        String stand = createStandWithDatesRule(source);

        String copy = given().header(HEADER, source)
                .contentType("application/json")
                .body("{\"nom\":\"Copie horaires longs\"}")
                .when()
                .post("/api/editions/" + source + "/dupliquer")
                .then()
                .statusCode(200)
                .extract()
                .path("id");

        assertThat(readDates(copy, stand)).containsExactlyElementsOf(DATES);
    }

    @Test
    void theSqlDumpWritesTheDatesAndReplaysThem() {
        String edition = createEdition("Horaires longs au dump");
        String stand = createStandWithDatesRule(edition);

        String dump = given().header(HEADER, edition)
                .when()
                .get("/api/database/export")
                .then()
                .statusCode(200)
                .extract()
                .asString();
        assertThat(dump).contains("'{" + String.join(",", DATES) + "}'");

        given().header(HEADER, edition)
                .config(RestAssured.config()
                        .encoderConfig(
                                EncoderConfig.encoderConfig().encodeContentTypeAs("application/sql", ContentType.TEXT)))
                .contentType("application/sql")
                .body(dump)
                .when()
                .post("/api/database/import")
                .then()
                .statusCode(200);

        assertThat(readDates(edition, stand)).containsExactlyElementsOf(DATES);
    }

    private static String createEdition(String name) {
        return given().contentType("application/json")
                .body("{\"nom\":\"" + name + "\"}")
                .when()
                .post("/api/editions")
                .then()
                .statusCode(200)
                .extract()
                .path("id");
    }

    private static String createStandWithDatesRule(String edition) {
        given().header(HEADER, edition)
                .contentType("application/json")
                .body("{\"code\":\"TYPO\",\"label\":\"TYPO\",\"ninja\":false}")
                .when()
                .post("/api/typologies")
                .then()
                .statusCode(200);
        String dates = DATES.stream().map(date -> "\"" + date + "\"").collect(Collectors.joining(","));
        return given().header(HEADER, edition)
                .contentType("application/json")
                .body("""
                        {"nom":"Ouvert un jour sur deux","typologiesProposees":["TYPO"],
                         "effectifMin":1,"effectifMax":2,
                         "horaires":[{"mode":"OUVERTURE","jours":"DATES","dates":[%s],
                                      "fenetres":[{"heureDebut":"10:00","heureFin":"18:00"}]}]}""".formatted(dates))
                .when()
                .post("/api/stands")
                .then()
                .statusCode(200)
                .extract()
                .path("stand.id");
    }

    private static List<String> readDates(String edition, String stand) {
        return given().header(HEADER, edition)
                .when()
                .get("/api/stands")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getList("find { it.id == '" + stand + "' }.horaires[0].dates", String.class);
    }
}
