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
                         "ouvertures":[{"date":"2026-07-12","heureDebut":"09:00","heureFin":"11:00"}]}""".formatted(rules))
                .when()
                .post("/api/stands")
                .then()
                .statusCode(200)
                .body("stand.horaires.size()", org.hamcrest.Matchers.is(2))
                .extract()
                .path("stand.id");

        assertThat(readWindows(edition, stand, "ouvertures"))
                .containsExactlyInAnyOrder(
                        "2026-07-10 10:00-18:00 x2", "2026-07-12 09:00-11:00 x-", "2026-07-13 10:00-18:00 x2");
        assertThat(readWindows(edition, stand, "indisponibilites")).containsExactly("2026-07-11 14:00-16:00 Démo");
    }

    /**
     * « Modèle gardé, déplié ensuite »: with no timeslot there is no day to lay a rule on, so it
     * stays the stand's model (read back with its id), and every timeslot that opens a new day
     * unfolds it there — once: a second timeslot on the same day adds nothing.
     */
    @Test
    void aRuleSentOnAnEditionWithoutTimeslotIsKeptThenUnfoldedOnTheDaysItGains() {
        String edition = createEdition("Modèle sans créneau");
        createTypologie(edition);

        String stand = given().header(HEADER, edition)
                .contentType("application/json")
                .body("""
                        {"nom":"Avec modèle","typologiesProposees":["TYPO"],"effectifMin":1,"effectifMax":2,
                         "horaires":[{"mode":"OUVERTURE","jours":"JOURS_SEMAINE","joursSemaine":["SATURDAY","SUNDAY"],
                                      "fenetres":[{"heureDebut":"10:00","heureFin":"18:00"}]}]}""")
                .when()
                .post("/api/stands")
                .then()
                .statusCode(200)
                .body("stand.horaires.size()", org.hamcrest.Matchers.is(1))
                .extract()
                .path("stand.id");
        assertThat(readWindows(edition, stand, "ouvertures")).isEmpty();

        // 2026-07-11 is a Saturday, 2026-07-13 a Monday: the model opens the stand on the first only.
        createCreneau(edition, "2026-07-11", "10:00", "12:00");
        createCreneau(edition, "2026-07-11", "14:00", "16:00");
        createCreneau(edition, "2026-07-13", "10:00", "12:00");

        assertThat(readWindows(edition, stand, "ouvertures")).containsExactly("2026-07-11 10:00-18:00 x-");
        assertThat(readWindows(edition, stand, "indisponibilites"))
                .containsExactly("2026-07-13 00:00- Jour non déclaré par le stand");
        given().header(HEADER, edition)
                .when()
                .get("/api/stands")
                .then()
                .body("find { it.id == '" + stand + "' }.horaires.size()", org.hamcrest.Matchers.is(1));
    }

    /**
     * The generator creates its timeslots through the same door (ADR 0126): a recurrence that opens
     * new days unfolds the stands' model on them, as a timeslot typed by hand does.
     */
    @Test
    void theGeneratorUnfoldsTheModelOnTheDaysItOpens() {
        String edition = createEdition("Modèle et générateur");
        createTypologie(edition);
        String stand = given().header(HEADER, edition)
                .contentType("application/json")
                .body("""
                        {"nom":"Avec modèle","typologiesProposees":["TYPO"],"effectifMin":1,"effectifMax":2,
                         "horaires":[{"mode":"OUVERTURE","jours":"JOURS_SEMAINE","joursSemaine":["SATURDAY","SUNDAY"],
                                      "fenetres":[{"heureDebut":"10:00","heureFin":"18:00"}]}]}""")
                .when()
                .post("/api/stands")
                .then()
                .statusCode(200)
                .extract()
                .path("stand.id");

        given().header(HEADER, edition)
                .contentType("application/json")
                .body("""
                        {"source":"RECURRENCE","recurrence":{"jours":"PLAGE","dateDebut":"2026-07-11",
                         "dateFin":"2026-07-13","fenetres":[{"heureDebut":"10:00","heureFin":"12:00"}]}}""")
                .when()
                .post("/api/generateur-creneaux/application")
                .then()
                .statusCode(200)
                .body("nombreGeneres", org.hamcrest.Matchers.is(3));

        assertThat(readWindows(edition, stand, "ouvertures"))
                .containsExactly("2026-07-11 10:00-18:00 x-", "2026-07-12 10:00-18:00 x-");
        assertThat(readWindows(edition, stand, "indisponibilites"))
                .containsExactly("2026-07-13 00:00- Jour non déclaré par le stand");
    }

    /**
     * In an edition that has days the rule is unfolded and also kept (the editor reads it back); editing
     * it withdraws what the previous one unfolded and unfolds the new one, a plage of the day's own staying.
     */
    @Test
    void editingTheModelOfADatedEditionReplacesWhatItHadUnfolded() {
        String edition = createEdition("Modèle modifié");
        createTypologie(edition);
        createCreneau(edition, "2026-07-10", "10:00", "12:00");
        createCreneau(edition, "2026-07-11", "10:00", "12:00");
        String stand = given().header(HEADER, edition)
                .contentType("application/json")
                .body("""
                        {"nom":"Modifié","typologiesProposees":["TYPO"],"effectifMin":1,"effectifMax":2,
                         "horaires":[{"mode":"OUVERTURE","jours":"TOUS",
                                      "fenetres":[{"heureDebut":"10:00","heureFin":"18:00"}]}],
                         "ouvertures":[{"date":"2026-07-11","heureDebut":"09:00","heureFin":"10:00","motif":"Main"}]}""")
                .when()
                .post("/api/stands")
                .then()
                .statusCode(200)
                .body("stand.horaires.size()", org.hamcrest.Matchers.is(1))
                .extract()
                .path("stand.id");
        assertThat(readWindows(edition, stand, "ouvertures"))
                .containsExactlyInAnyOrder("2026-07-10 10:00-18:00 x-", "2026-07-11 09:00-10:00 x-");

        // The editor sends the stand as it read it, with the rule changed to 14:00-16:00.
        List<java.util.Map<String, Object>> lues = given().header(HEADER, edition)
                .when()
                .get("/api/stands")
                .then()
                .extract()
                .jsonPath()
                .getList("find { it.id == '" + stand + "' }.ouvertures");
        given().header(HEADER, edition)
                .contentType("application/json")
                .body(java.util.Map.of(
                        "nom",
                        "Modifié",
                        "typologiesProposees",
                        List.of("TYPO"),
                        "effectifMin",
                        1,
                        "effectifMax",
                        2,
                        "horaires",
                        List.of(java.util.Map.of(
                                "mode", "OUVERTURE",
                                "jours", "TOUS",
                                "fenetres", List.of(java.util.Map.of("heureDebut", "14:00", "heureFin", "16:00")))),
                        "ouvertures",
                        lues))
                .when()
                .put("/api/stands/" + stand)
                .then()
                .statusCode(200);

        assertThat(readWindows(edition, stand, "ouvertures"))
                .containsExactlyInAnyOrder("2026-07-10 14:00-16:00 x-", "2026-07-11 09:00-10:00 x-");
    }

    /** Sending the stored model back, as the 3.x editor does on every save, changes nothing. */
    @Test
    void theModelSentBackUnchangedIsLeftAlone() {
        String edition = createEdition("Modèle renvoyé");
        createTypologie(edition);
        String stand = given().header(HEADER, edition)
                .contentType("application/json")
                .body("""
                        {"nom":"Renvoyé","typologiesProposees":["TYPO"],"effectifMin":1,"effectifMax":2,
                         "horaires":[{"mode":"OUVERTURE","jours":"TOUS",
                                      "fenetres":[{"heureDebut":"10:00","heureFin":"18:00"}]}]}""")
                .when()
                .post("/api/stands")
                .then()
                .statusCode(200)
                .extract()
                .path("stand.id");
        createCreneau(edition, "2026-07-11", "10:00", "12:00");
        assertThat(readWindows(edition, stand, "ouvertures")).containsExactly("2026-07-11 10:00-18:00 x-");

        // The stand as the editor read it, its rule and its plages: saved again after the day was
        // unfolded, and after a hand deletion of that day's plage — which must stay deleted.
        given().header(HEADER, edition)
                .contentType("application/json")
                .body("""
                        {"nom":"Renvoyé","typologiesProposees":["TYPO"],"effectifMin":1,"effectifMax":2,
                         "horaires":[{"mode":"OUVERTURE","jours":"TOUS",
                                      "fenetres":[{"heureDebut":"10:00","heureFin":"18:00"}]}],
                         "ouvertures":[]}""")
                .when()
                .put("/api/stands/" + stand)
                .then()
                .statusCode(200);

        assertThat(readWindows(edition, stand, "ouvertures")).isEmpty();
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

    /** The model and the plages are published as such; the old lists stay readable until 5.0. */
    @Test
    void aStandPublishesItsPlagesWithTheirOriginAndStillTheDeprecatedLists() {
        String edition = createEdition("Plages publiées");
        createTypologie(edition);
        String stand = given().header(HEADER, edition)
                .contentType("application/json")
                .body("""
                        {"nom":"Plages","typologiesProposees":["TYPO"],"effectifMin":1,"effectifMax":2,
                         "plages":[{"mode":"OUVERTURE","date":"2026-07-10","heureDebut":"10:00","heureFin":"12:00"},
                                   {"mode":"FERMETURE","date":"2026-07-11","heureDebut":"14:00","heureFin":"16:00"}]}""")
                .when()
                .post("/api/stands")
                .then()
                .statusCode(200)
                .extract()
                .path("stand.id");

        var read =
                given().header(HEADER, edition).when().get("/api/stands").then().statusCode(200);
        read.body("find { it.id == '" + stand + "' }.plages.size()", org.hamcrest.Matchers.is(2))
                .body("find { it.id == '" + stand + "' }.plages[0].depliee", org.hamcrest.Matchers.is(false))
                .body("find { it.id == '" + stand + "' }.ouvertures.size()", org.hamcrest.Matchers.is(1))
                .body("find { it.id == '" + stand + "' }.indisponibilites.size()", org.hamcrest.Matchers.is(1));
    }

    /**
     * A client of the 3.x edits {@code ouvertures} and echoes {@code plages}; a new client edits
     * {@code plages} and echoes {@code ouvertures}: the form that differs from the stored stand is the
     * change, whichever key comes first, and two contradicting changes are a bad request.
     */
    @Test
    void aBodyCarryingBothFormsIsSettledByTheStoredStandWhateverTheKeyOrder() {
        String edition = createEdition("Plages et anciennes listes");
        createTypologie(edition);
        String stand = given().header(HEADER, edition)
                .contentType("application/json")
                .body("""
                        {"nom":"Mixte","typologiesProposees":["TYPO"],"effectifMin":1,"effectifMax":2,
                         "plages":[{"mode":"OUVERTURE","date":"2026-07-10","heureDebut":"10:00","heureFin":"12:00"}]}""")
                .when()
                .post("/api/stands")
                .then()
                .statusCode(200)
                .extract()
                .path("stand.id");

        java.util.concurrent.atomic.AtomicInteger bodyNumber = new java.util.concurrent.atomic.AtomicInteger();
        for (String body : List.of("""
                {"nom":"Mixte","typologiesProposees":["TYPO"],"effectifMin":1,"effectifMax":2,
                 "ouvertures":[{"date":"2026-07-14","heureDebut":"09:00","heureFin":"11:00"}],
                 "plages":[{"mode":"OUVERTURE","date":"2026-07-10","heureDebut":"10:00","heureFin":"12:00"}]}""", """
                {"nom":"Mixte","typologiesProposees":["TYPO"],"effectifMin":1,"effectifMax":2,
                 "plages":[{"mode":"OUVERTURE","date":"2026-07-10","heureDebut":"10:00","heureFin":"12:00"}],
                 "ouvertures":[{"date":"2026-07-14","heureDebut":"09:00","heureFin":"11:00"}]}""")) {
            given().header(HEADER, edition)
                    .contentType("application/json")
                    .body(body)
                    .when()
                    .put("/api/stands/" + stand)
                    .then()
                    .statusCode(200);
            // The first body edits ouvertures (the stored day, 10 July, is only echoed by plages);
            // the second one, sent against the result, edits plages (ouvertures echoes the 14th).
            assertThat(readDates(edition, stand))
                    .containsExactly(bodyNumber.getAndIncrement() == 0 ? "2026-07-14" : "2026-07-10");
        }
    }

    @Test
    void aNewClientEditingPlagesWhileEchoingTheDeprecatedListKeepsItsChange() {
        String edition = createEdition("Plages éditées");
        createTypologie(edition);
        String stand = given().header(HEADER, edition)
                .contentType("application/json")
                .body("""
                        {"nom":"Écho","typologiesProposees":["TYPO"],"effectifMin":1,"effectifMax":2,
                         "plages":[{"mode":"OUVERTURE","date":"2026-07-10","heureDebut":"10:00","heureFin":"12:00"}]}""")
                .when()
                .post("/api/stands")
                .then()
                .statusCode(200)
                .extract()
                .path("stand.id");
        String echoed = "{\"date\":\"2026-07-10\",\"heureDebut\":\"10:00:00\",\"heureFin\":\"12:00:00\"}";
        String edited =
                "{\"mode\":\"OUVERTURE\",\"date\":\"2026-07-16\",\"heureDebut\":\"10:00\",\"heureFin\":\"12:00\"}";

        given().header(HEADER, edition)
                .contentType("application/json")
                .body("{\"nom\":\"Écho\",\"typologiesProposees\":[\"TYPO\"],\"effectifMin\":1,\"effectifMax\":2,"
                        + "\"ouvertures\":[" + echoed + "],\"plages\":[" + edited + "]}")
                .when()
                .put("/api/stands/" + stand)
                .then()
                .statusCode(200);
        assertThat(readDates(edition, stand)).containsExactly("2026-07-16");

        // Both forms changed, and they disagree with each other: nothing says which is meant.
        given().header(HEADER, edition)
                .contentType("application/json")
                .body("{\"nom\":\"Écho\",\"typologiesProposees\":[\"TYPO\"],\"effectifMin\":1,\"effectifMax\":2,"
                        + "\"ouvertures\":[" + echoed + "],\"plages\":["
                        + edited.replace("07-16", "07-17").replace("OUVERTURE", "FERMETURE") + "]}")
                .when()
                .put("/api/stands/" + stand)
                .then()
                .statusCode(400);
    }

    @Test
    void aPlageIsLaidAndRemovedOneByOneOrByDay() {
        String edition = createEdition("Plages une à une");
        createTypologie(edition);
        String stand = given().header(HEADER, edition)
                .contentType("application/json")
                .body("{\"nom\":\"Une à une\",\"typologiesProposees\":[\"TYPO\"],\"effectifMin\":1,\"effectifMax\":2}")
                .when()
                .post("/api/stands")
                .then()
                .statusCode(200)
                .extract()
                .path("stand.id");

        List<Integer> ids = new java.util.ArrayList<>();
        for (String date : List.of("2026-07-10", "2026-07-10", "2026-07-11")) {
            var ecrit = given().header(HEADER, edition)
                    .contentType("application/json")
                    .body("{\"mode\":\"FERMETURE\",\"date\":\"%s\",\"heureDebut\":\"%s\",\"heureFin\":\"%s\"}"
                            .formatted(date, ids.isEmpty() ? "10:00" : "14:00", ids.isEmpty() ? "11:00" : "15:00"))
                    .when()
                    .post("/api/stands/" + stand + "/plages")
                    .then()
                    .statusCode(200);
            ids = ecrit.extract().jsonPath().getList("stand.plages.id", Integer.class);
        }
        assertThat(ids).hasSize(3).doesNotContainNull();

        // One by id…
        given().header(HEADER, edition)
                .when()
                .delete("/api/stands/" + stand + "/plages/" + ids.getFirst())
                .then()
                .statusCode(200)
                .body("stand.plages.size()", org.hamcrest.Matchers.is(2));
        // …unknown: 404, nothing moves…
        given().header(HEADER, edition)
                .when()
                .delete("/api/stands/" + stand + "/plages/999999999")
                .then()
                .statusCode(404);
        // …a day at once…
        given().header(HEADER, edition)
                .when()
                .delete("/api/stands/" + stand + "/plages?date=2026-07-10")
                .then()
                .statusCode(200)
                .body("stand.plages.size()", org.hamcrest.Matchers.is(1))
                .body("stand.plages[0].date", org.hamcrest.Matchers.is("2026-07-11"));
        // …and neither id nor date is a bad request.
        given().header(HEADER, edition)
                .when()
                .delete("/api/stands/" + stand + "/plages")
                .then()
                .statusCode(400);
        // A plage of an unknown stand: 404.
        given().header(HEADER, edition)
                .contentType("application/json")
                .body("{\"mode\":\"FERMETURE\",\"date\":\"2026-07-10\",\"heureDebut\":\"10:00\"}")
                .when()
                .post("/api/stands/INCONNU/plages")
                .then()
                .statusCode(404);
    }

    private static void createTypologie(String edition) {
        given().header(HEADER, edition)
                .contentType("application/json")
                .body("{\"code\":\"TYPO\",\"label\":\"TYPO\",\"polyvalent\":false}")
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
