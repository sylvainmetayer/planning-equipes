package dev.sylvain.planning.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * « Le passé est figé » (ADR 0044), rehearsed the way a staging server would:
 * the freeze on, the clock frozen inside the event, and three solves in a
 * row — a full one, an incremental one naming a past day, and the pair a
 * consigne makes (laid on tomorrow, then lifted). The days already worked
 * come out of every solve exactly as they went in, are reported as
 * {@code postesPasses}, and never cost a hard point — a holder declared
 * unavailable after the fact included.
 *
 * <p>The scenario is the twelfth rung of the ladder, a whole civil week from
 * Monday 12 to Sunday 18 July 2027 with a morning and an afternoon a day;
 * the clock is set on the Wednesday at 13:30, so the two first days and the
 * Wednesday morning are behind, the Wednesday afternoon and the rest of the
 * week ahead. The event is entirely in the future of the real clock, which
 * is why the nominal solve — frozen on 1 July — finds nothing past.</p>
 */
@QuarkusTest
@TestProfile(FrozenPastAcceptanceTest.PastFrozenOnAStagingServer.class)
class FrozenPastAcceptanceTest {

    /** The freeze on, and the clock allowed to be frozen — a staging server's configuration. */
    public static class PastFrozenOnAStagingServer implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("planning.solver.passe-fige", "true", "planning.horloge-simulee.autorisee", "true");
        }
    }

    private static final String SCENARIO = "gamme-12-7j-10stands-24animateurs-semaine-complete-ferie.yaml";
    private static final String AVANT_L_EVENEMENT = "2027-07-01";
    private static final String J1 = "2027-07-12";
    private static final String J2 = "2027-07-13";
    private static final String J3 = "2027-07-14";
    private static final String J4 = "2027-07-15";
    private static final String MATIN = "10:00:00";
    private static final int MAX_POLLS = 240;
    private static final long POLL_INTERVAL_MS = 250;

    @BeforeEach
    void anEditionFrozenBeforeItsFirstDay() {
        given().when().post("/api/planning/reset").then().statusCode(200);
        given().when()
                .post("/api/reference-data/import-scenario?name=" + SCENARIO)
                .then()
                .statusCode(200);
        freezeClock(AVANT_L_EVENEMENT, null);
    }

    @AfterEach
    void handTheClockBack() throws InterruptedException {
        attendreSolveurLibre();
        given().contentType(ContentType.JSON)
                .body("{\"dateDuJour\":null}")
                .when()
                .put("/api/debug/date-du-jour")
                .then()
                .statusCode(200);
        given().when().post("/api/planning/reset").then().statusCode(200);
    }

    @Test
    void theDaysAlreadyWorkedComeOutOfEverySolveAsTheyWentIn() throws InterruptedException {
        // 1. Nominal: the whole week ahead, nothing past, zero hard.
        JsonPath nominal = solveComplet();
        assertThat(nominal.getInt("result.diagnostic.hardScore")).isZero();
        assertThat(nominal.getInt("result.reamorcage.postesPasses")).isZero();
        Map<String, List<String>> plan0 = seatsByDay();
        Map<String, List<String>> passeAttendu = pastSeats(plan0);
        int postesPasses = passeAttendu.values().stream().mapToInt(List::size).sum();
        assertThat(postesPasses).isPositive();
        String temoin = firstHolderOn(J1);

        // 2. Wednesday 13:30, and a holder of the Monday declares that day off
        //    after the fact: they were there all the same.
        freezeClock(J3, "13:30");
        rendreIndisponible(temoin, J1);

        // 3. A full solve: the past is reported, identical, and costs nothing —
        //    to the solve, and to the analysis the Contraintes screen reads.
        JsonPath complet = solveComplet();
        assertThat(complet.getInt("result.diagnostic.hardScore")).isZero();
        assertThat(complet.getInt("result.reamorcage.postesPasses")).isEqualTo(postesPasses);
        assertThat(pastSeats(seatsByDay())).isEqualTo(passeAttendu);
        assertThat(persistedHardScore()).isZero();

        // 4. An incremental solve re-opening the Monday: nothing to re-open.
        JsonPath incremental = solveIncremental("{\"animateurIds\":[],\"jours\":[\"" + J1 + "\"],\"standIds\":[]}");
        assertThat(incremental.getInt("result.statistiques.postesPasses")).isEqualTo(postesPasses);
        assertThat(incremental.getInt("result.statistiques.postesLiberesManuellement"))
                .isZero();
        assertThat(incremental.getList("result.changements")).isEmpty();
        assertThat(pastSeats(seatsByDay())).isEqualTo(passeAttendu);

        // 5. A consigne on Thursday, solved: the past still identical, the
        //    future re-solved to zero hard on the new grid.
        List<String> jeudiNominal = seatShapes(J4);
        given().contentType(ContentType.JSON)
                .body(consigne())
                .when()
                .post("/api/consignes")
                .then()
                .statusCode(200);
        JsonPath sousConsigne = solveComplet();
        assertThat(sousConsigne.getInt("result.diagnostic.hardScore")).isZero();
        assertThat(sousConsigne.getInt("result.reamorcage.postesPasses")).isEqualTo(postesPasses);
        assertThat(pastSeats(seatsByDay())).isEqualTo(passeAttendu);
        assertThat(seatShapes(J4)).isNotEqualTo(jeudiNominal);

        // 6. « Demain on réouvre en nominal »: lifted, solved, the Thursday
        //    back to its nominal shape, the past byte-identical across all three.
        given().contentType(ContentType.JSON)
                .body(Map.of("dates", List.of(J4)))
                .when()
                .post("/api/consignes/levee")
                .then()
                .statusCode(204);
        JsonPath leve = solveComplet();
        assertThat(leve.getInt("result.diagnostic.hardScore")).isZero();
        assertThat(pastSeats(seatsByDay())).isEqualTo(passeAttendu);
        assertThat(seatShapes(J4)).isEqualTo(jeudiNominal);
        assertThat(persistedHardScore()).isZero();
    }

    /* ------------------------------- Helpers ------------------------------- */

    private static void freezeClock(String date, String heure) {
        String corps = heure == null
                ? "{\"dateDuJour\":\"" + date + "\"}"
                : "{\"dateDuJour\":\"" + date + "\",\"heureDuJour\":\"" + heure + "\"}";
        given().contentType(ContentType.JSON)
                .body(corps)
                .when()
                .put("/api/debug/date-du-jour")
                .then()
                .statusCode(200);
    }

    /** A consigne on the Thursday: 12h-16h closed for everybody, one stand reopened 18h-20h. */
    private static Map<String, Object> consigne() {
        Map<String, Object> ouverture = new HashMap<>();
        ouverture.put("standId", "STAND-CULTURE");
        ouverture.put("debut", "18:00");
        ouverture.put("fin", "20:00");
        Map<String, Object> corps = new HashMap<>();
        corps.put("dates", List.of(J4));
        corps.put("fermetureDebut", "12:00");
        corps.put("fermetureFin", "16:00");
        corps.put("motif", "Arrêté préfectoral canicule");
        corps.put("fenetres", List.of(Map.of("debut", "18:00", "fin", "20:00")));
        corps.put("ouvertures", List.of(ouverture));
        return corps;
    }

    /**
     * The seats of the days already worked at the frozen moment — the two
     * first days whole, and the Wednesday morning — each day's seats sorted,
     * so two plans compare as the multisets they are.
     */
    private static Map<String, List<String>> pastSeats(Map<String, List<String>> parJour) {
        Map<String, List<String>> passe = new TreeMap<>();
        passe.put(J1, parJour.getOrDefault(J1, List.of()));
        passe.put(J2, parJour.getOrDefault(J2, List.of()));
        passe.put(
                J3 + " " + MATIN,
                parJour.getOrDefault(J3, List.of()).stream()
                        .filter(siege -> siege.contains("@" + MATIN + "|"))
                        .toList());
        return passe;
    }

    /** Every persisted seat as « stand@début|fin|animateur », grouped by day and sorted. */
    private static Map<String, List<String>> seatsByDay() {
        Map<String, List<String>> parJour = new TreeMap<>();
        for (Map<String, Object> poste : affectationsPersistees()) {
            Map<String, Object> creneau = creneauOf(poste);
            String date = String.valueOf(creneau.get("date"));
            parJour.computeIfAbsent(date, ignored -> new ArrayList<>()).add(siege(poste));
        }
        parJour.values().forEach(sieges -> sieges.sort(String::compareTo));
        return parJour;
    }

    /** The shape of a day's seats — stand and hours, whoever sits — sorted. */
    private static List<String> seatShapes(String date) {
        List<String> formes = new ArrayList<>();
        for (Map<String, Object> poste : affectationsPersistees()) {
            Map<String, Object> creneau = creneauOf(poste);
            if (date.equals(String.valueOf(creneau.get("date")))) {
                formes.add(standOf(poste) + "@" + heureDebut(poste) + "-" + heureFin(poste));
            }
        }
        formes.sort(String::compareTo);
        return formes;
    }

    @SuppressWarnings("unchecked")
    private static String siege(Map<String, Object> poste) {
        Map<String, Object> animateur = (Map<String, Object>) poste.get("animateur");
        String tenant = animateur == null ? "-" : String.valueOf(animateur.get("id"));
        return standOf(poste) + "@" + heureDebut(poste) + "|" + heureFin(poste) + "|" + tenant;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> creneauOf(Map<String, Object> poste) {
        return (Map<String, Object>) poste.get("creneau");
    }

    @SuppressWarnings("unchecked")
    private static String standOf(Map<String, Object> poste) {
        return String.valueOf(((Map<String, Object>) poste.get("stand")).get("id"));
    }

    private static String heureDebut(Map<String, Object> poste) {
        Object effective = poste.get("heureDebutEffective");
        return String.valueOf(effective != null ? effective : creneauOf(poste).get("heureDebut"));
    }

    private static String heureFin(Map<String, Object> poste) {
        Object effective = poste.get("heureFinEffective");
        return String.valueOf(effective != null ? effective : creneauOf(poste).get("heureFin"));
    }

    @SuppressWarnings("unchecked")
    private static String firstHolderOn(String date) {
        for (Map<String, Object> poste : affectationsPersistees()) {
            Map<String, Object> animateur = (Map<String, Object>) poste.get("animateur");
            if (animateur != null && date.equals(String.valueOf(creneauOf(poste).get("date")))) {
                return String.valueOf(animateur.get("id"));
            }
        }
        throw new AssertionError("Nobody seated on " + date);
    }

    private static List<Map<String, Object>> affectationsPersistees() {
        return given().when()
                .get("/api/planning/persisted")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getList("postes");
    }

    /** Adds a day off to an animateur, keeping everything else the referential holds. */
    @SuppressWarnings("unchecked")
    private static void rendreIndisponible(String animateurId, String jour) {
        Map<String, Object> animateur = given()
                .when()
                .get("/api/animateurs")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .<Map<String, Object>>getList("$")
                .stream()
                .filter(candidat -> animateurId.equals(candidat.get("id")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Animateur introuvable : " + animateurId));
        List<String> jours = new ArrayList<>((List<String>) animateur.getOrDefault("joursIndisponibles", List.of()));
        jours.add(jour);
        animateur.put("joursIndisponibles", jours);
        given().contentType(ContentType.JSON)
                .body(animateur)
                .when()
                .put("/api/animateurs/" + animateurId)
                .then()
                .statusCode(200);
    }

    /** Hard level of the analysis the Contraintes screen reads — the persisted plan, re-scored. */
    private static int persistedHardScore() {
        Integer hardScore = given().when()
                .get("/api/constraints")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getObject("hardScore", Integer.class);
        assertThat(hardScore).isNotNull();
        return hardScore;
    }

    private JsonPath solveComplet() throws InterruptedException {
        attendreSolveurLibre();
        String jobId = given().when()
                .post("/api/solve/async/reference-data?seconds=5")
                .then()
                .statusCode(202)
                .extract()
                .path("id");
        JsonPath job = pollUntilFinished(jobId);
        assertThat(job.getString("status")).as(job.getString("error")).isEqualTo("COMPLETED");
        return job;
    }

    private JsonPath solveIncremental(String corps) throws InterruptedException {
        attendreSolveurLibre();
        String jobId = given().contentType(ContentType.JSON)
                .body(corps)
                .when()
                .post("/api/solve/incremental/async?seconds=5")
                .then()
                .statusCode(202)
                .extract()
                .path("id");
        JsonPath job = pollUntilFinished(jobId);
        assertThat(job.getString("status")).as(job.getString("error")).isEqualTo("COMPLETED");
        return job;
    }

    private static void attendreSolveurLibre() throws InterruptedException {
        for (int i = 0; i < MAX_POLLS; i++) {
            if (given().when().get("/api/jobs/active").then().extract().statusCode() == 204) {
                return;
            }
            Thread.sleep(POLL_INTERVAL_MS);
        }
        throw new AssertionError("Solver still busy");
    }

    private static JsonPath pollUntilFinished(String jobId) throws InterruptedException {
        for (int i = 0; i < MAX_POLLS; i++) {
            JsonPath job = given().when()
                    .get("/api/jobs/" + jobId)
                    .then()
                    .statusCode(200)
                    .extract()
                    .jsonPath();
            if (List.of("COMPLETED", "FAILED", "CANCELLED").contains(job.getString("status"))) {
                return job;
            }
            Thread.sleep(POLL_INTERVAL_MS);
        }
        throw new AssertionError("Job " + jobId + " did not finish in time");
    }
}
