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
 * list; and the recurring rules the 3.x editor still sends are laid down as
 * plages rather than dropped in silence (ADR 0104).
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

    /**
     * The 3.x editor still sends rules until the plages editor ships: the server lays
     * them down over the event's days, with the reading 3.x gave them at every read —
     * the most specific rule wins, a dated window keeps its day, the morning after a
     * timeslot crossing midnight is a day too, a day no créneau carries gets nothing.
     */
    @Test
    void aRuleSentByTheEditorBecomesThePlagesThreeXResolvedItTo() {
        String edition = createEdition("Règle convertie");
        createTypologie(edition);
        createCreneau(edition, "2026-07-10", "10:00", "12:00");
        createCreneau(edition, "2026-07-11", "10:00", "12:00");
        createCreneau(edition, "2026-07-12", "23:00", "01:00");
        String rules = """
                [{"id":7,"mode":"OUVERTURE","jours":"TOUS","joursSemaine":[],"dateDebut":null,"dateFin":null,
                  "dates":[],"motif":null,"fenetres":[{"heureDebut":"10:00","heureFin":"18:00","effectif":2}]},
                 {"id":8,"mode":"FERMETURE","jours":"DATES","joursSemaine":[],"dateDebut":null,"dateFin":null,
                  "dates":["2026-07-11"],"motif":"Démo","fenetres":[{"heureDebut":"14:00","heureFin":"16:00"}]}]""";

        String stand = given().header(HEADER, edition)
                .contentType("application/json")
                .body("""
                        {"nom":"Avec règle","typologiesProposees":["TYPO"],"effectifMin":1,"effectifMax":2,
                         "horaires":%s,
                         "ouvertures":[{"date":"2026-07-12","heureDebut":"09:00","heureFin":"11:00"}]}"""
                        .formatted(rules))
                .when()
                .post("/api/stands")
                .then()
                .statusCode(200)
                .body("stand.horaires", org.hamcrest.Matchers.empty())
                .extract()
                .path("stand.id");

        assertThat(readWindows(edition, stand, "ouvertures"))
                .containsExactlyInAnyOrder(
                        "2026-07-10 10:00-18:00 x2", "2026-07-12 09:00-11:00 x-", "2026-07-13 10:00-18:00 x2");
        assertThat(readWindows(edition, stand, "indisponibilites")).containsExactly("2026-07-11 14:00-16:00 Démo");
    }

    @Test
    void aRuleSentOnAnEditionWithoutTimeslotIsRefusedWithItsReason() {
        String edition = createEdition("Règle sans créneau");
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
                .statusCode(400)
                .body(org.hamcrest.Matchers.containsString("aucun créneau"));
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

    private static void createCreneau(String edition, String date, String debut, String fin) {
        given().header(HEADER, edition)
                .contentType("application/json")
                .body("{\"date\":\"%s\",\"heureDebut\":\"%s\",\"heureFin\":\"%s\"}".formatted(date, debut, fin))
                .when()
                .post("/api/creneaux")
                .then()
                .statusCode(200);
    }

    /** The stand's windows of one kind, each as « date début-fin » plus its headcount or its reason. */
    private static List<String> readWindows(String edition, String stand, String kind) {
        List<java.util.Map<String, Object>> fenetres = given().header(HEADER, edition)
                .when()
                .get("/api/stands")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getList("find { it.id == '" + stand + "' }." + kind);
        return fenetres.stream()
                .map(f -> f.get("date") + " " + hour(f.get("heureDebut")) + "-" + hour(f.get("heureFin"))
                        + ("ouvertures".equals(kind)
                                ? " x" + (f.get("effectif") == null ? "-" : f.get("effectif"))
                                : " " + f.get("motif")))
                .toList();
    }

    private static String hour(Object value) {
        return value == null ? "" : value.toString().substring(0, 5);
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
