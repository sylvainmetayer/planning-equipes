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
 * A stand open one day out of two over a 240-day season carries a hundred and
 * twenty plages. The REST write and read, the edition duplication that copies
 * them, and the SQL dump that writes and replays them all land on the same
 * list; and the recurring rules of 3.x, gone since the plages (ADR 0104), are
 * refused rather than dropped in silence.
 */
@QuarkusTest
class StandPlagesResourceTest {

    private static final String HEADER = "X-Edition-Id";

    private static final List<String> DATES = IntStream.range(0, 120)
            .mapToObj(i -> LocalDate.of(2026, 5, 1).plusDays(2L * i).toString())
            .toList();

    @Test
    void oneHundredAndTwentyPlagesAreSavedAndReadBack() {
        String edition = createEdition("Plages longues");
        String stand = createStandWithPlages(edition);

        assertThat(readDates(edition, stand)).containsExactlyElementsOf(DATES);
    }

    @Test
    void aDuplicatedEditionKeepsEveryPlage() {
        String source = createEdition("Plages longues à dupliquer");
        String stand = createStandWithPlages(source);

        String copy = given().header(HEADER, source)
                .contentType("application/json")
                .body("{\"nom\":\"Copie plages longues\"}")
                .when()
                .post("/api/editions/" + source + "/dupliquer")
                .then()
                .statusCode(200)
                .extract()
                .path("id");

        assertThat(readDates(copy, stand)).containsExactlyElementsOf(DATES);
    }

    @Test
    void theSqlDumpWritesThePlagesAndReplaysThem() {
        String edition = createEdition("Plages longues au dump");
        String stand = createStandWithPlages(edition);

        String dump = given().header(HEADER, edition)
                .when()
                .get("/api/database/export")
                .then()
                .statusCode(200)
                .extract()
                .asString();
        assertThat(dump).contains("stand_ouverture");

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

    @Test
    void aStandCarryingARecurringRuleIsRefusedNotTruncated() {
        String edition = createEdition("Règle refusée");
        createTypologie(edition);

        given().header(HEADER, edition)
                .contentType("application/json")
                .body("""
                        {"nom":"Avec règle","typologiesProposees":["TYPO"],"effectifMin":1,"effectifMax":2,
                         "horaires":[{"mode":"OUVERTURE","jours":"TOUS",
                                      "fenetres":[{"heureDebut":"10:00","heureFin":"18:00"}]}]}""")
                .when()
                .post("/api/stands")
                .then()
                .statusCode(400);
    }

    @Test
    void theCompactionOfTheHorairesHasNothingLeftToDo() {
        String edition = createEdition("Compactage vide");

        given().header(HEADER, edition)
                .contentType("application/json")
                .when()
                .post("/api/stands/compactage-horaires")
                .then()
                .statusCode(200)
                .body("standsCompactes", org.hamcrest.Matchers.is(0))
                .body("stands", org.hamcrest.Matchers.empty());
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

    private static void createTypologie(String edition) {
        given().header(HEADER, edition)
                .contentType("application/json")
                .body("{\"code\":\"TYPO\",\"label\":\"TYPO\",\"ninja\":false}")
                .when()
                .post("/api/typologies")
                .then()
                .statusCode(200);
    }

    private static String createStandWithPlages(String edition) {
        createTypologie(edition);
        String ouvertures = DATES.stream()
                .map(date -> "{\"date\":\"" + date + "\",\"heureDebut\":\"10:00\",\"heureFin\":\"18:00\"}")
                .collect(Collectors.joining(","));
        return given().header(HEADER, edition)
                .contentType("application/json")
                .body("""
                        {"nom":"Ouvert un jour sur deux","typologiesProposees":["TYPO"],
                         "effectifMin":1,"effectifMax":2,"ouvertures":[%s]}""".formatted(ouvertures))
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
                .getList("find { it.id == '" + stand + "' }.ouvertures.date", String.class);
    }
}
