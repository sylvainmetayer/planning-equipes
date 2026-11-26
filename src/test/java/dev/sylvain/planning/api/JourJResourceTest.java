package dev.sylvain.planning.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The event-day screen (issue #297) end to end, over {@code scenario.yml}: one
 * day (2026-07-08), a morning slot 09:00-13:00 and an afternoon one 14:00-18:00.
 *
 * <p>Every test states the moment it reads the day from ({@code maintenant=…})
 * rather than depending on the wall clock: the whole feature is "what is still
 * ahead", so a test whose reference time is the machine's would assert
 * something different every afternoon.</p>
 */
@QuarkusTest
class JourJResourceTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String JOUR = "2026-07-08";

    /** Between the two slots: the morning is over, the afternoon has not started. */
    private static final String ENTRE_LES_DEUX = "13:30";

    /**
     * Same reason as {@code AffectationExplanationResourceTest}: the scenario's
     * explicit timeslot ids collide with the sequence a later test class relies
     * on, so this class hands the database back empty.
     */
    @AfterEach
    void resetDatabase() {
        given().header("X-Edition-Id", "E1")
                .when()
                .post("/api/planning/reset")
                .then()
                .statusCode(200);
    }

    /* --------------------------------- State ------------------------------- */

    @Test
    void etatKeepsOnlyTheTimeslotsStillAhead() {
        solveScenario();

        JsonPath etat = etat(ENTRE_LES_DEUX);

        assertThat(etat.getString("date")).isEqualTo(JOUR);
        assertThat(etat.getString("maintenant")).isEqualTo(JOUR + "T" + ENTRE_LES_DEUX + ":00");
        assertThat(etat.getInt("creneauxDuJour")).isEqualTo(2);
        assertThat(etat.getList("creneauxRestants.heureDebut", String.class))
                .singleElement()
                .asString()
                .startsWith("14:00");
    }

    /** The slot that has started but not ended is the one nobody is standing at. */
    @Test
    void aTimeslotUnderWayCountsAsStillAhead() {
        solveScenario();

        JsonPath etat = etat("15:00");

        assertThat(etat.getList("creneauxRestants.heureDebut", String.class)).hasSize(1);
        assertThat(etat.getBoolean("creneauxRestants[0].enCours")).isTrue();
    }

    @Test
    void etatListsWhoIsOnDutyOverTheRemainingTimeslots() {
        solveScenario();

        JsonPath etat = etat(ENTRE_LES_DEUX);

        List<String> ids = etat.getList("animateursDeService.animateurId", String.class);
        assertThat(ids).isNotEmpty();
        assertThat(etat.getList("animateursDeService.nomAffiche", String.class))
                .allSatisfy(nom -> assertThat(nom).isNotBlank());
        assertThat(etat.getList("animateursDeService.absent", Boolean.class)).containsOnly(false);
    }

    /* ------------------------------- Absence ------------------------------- */

    @Test
    void markingAbsentLeavesPastTimeslotsUntouched() {
        solveScenario();
        String absent = firstAnimateurOnDuty();
        Map<String, String> avant = persistedOccupants();
        List<String> postesDuMatin = seatsStartingAt(avant.keySet(), "09:00");
        assertThat(postesDuMatin).isNotEmpty();

        JsonPath marquee = markAbsent(absent, "Pas au point de rendez-vous", ENTRE_LES_DEUX, 200);

        // The day off is the whole day (ADR 0117): both timeslots are listed, only the afternoon seats are freed.
        assertThat(marquee.getList("entrees.heureDebut", String.class))
                .hasSize(2)
                .anyMatch(heure -> heure.startsWith("14:00"));
        // The morning seats keep the person that really held them.
        Map<String, String> apres = persistedOccupants();
        for (String poste : postesDuMatin) {
            assertThat(apres).as("poste %s du matin", poste).containsEntry(poste, avant.get(poste));
        }
        // And the afternoon ones they held are now empty.
        List<String> liberes = marquee.getList("postesLiberes.posteId", String.class);
        assertThat(liberes).isNotEmpty().allSatisfy(poste -> assertThat(apres).doesNotContainKey(poste));
    }

    /** The absence is nothing but the day off of the fiche: no other record, no ad hoc exception. */
    @Test
    void markingAbsentPutsTheDayOffOnTheFiche() {
        solveScenario();
        String absent = firstAnimateurOnDuty();

        markAbsent(absent, "Malade", ENTRE_LES_DEUX, 200);

        assertThat(daysOff(absent)).contains(JOUR);
        assertThat(given().header("X-Edition-Id", "E1")
                        .when()
                        .get("/api/contraintes-ad-hoc")
                        .then()
                        .statusCode(200)
                        .extract()
                        .jsonPath()
                        .getList("id"))
                .isEmpty();
    }

    /**
     * The whole point of the screen: the plan changes without a solver ever
     * starting. Both witnesses are checked — no job was queued, and the
     * "last solved at" stamp (written by {@code persist} alone) did not move.
     */
    @Test
    void markingAbsentStartsNoSolve() {
        solveScenario();
        String absent = firstAnimateurOnDuty();
        int jobsAvant = jobCount();
        String solvedAtBefore = lastSolvedAt();

        markAbsent(absent, null, ENTRE_LES_DEUX, 200);

        assertThat(jobCount()).isEqualTo(jobsAvant);
        assertThat(lastSolvedAt()).isEqualTo(solvedAtBefore);
    }

    @Test
    void markingAbsentTwiceOverwritesInsteadOfPilingUp() {
        solveScenario();
        String absent = firstAnimateurOnDuty();

        markAbsent(absent, null, ENTRE_LES_DEUX, 200);
        markAbsent(absent, null, ENTRE_LES_DEUX, 200);

        assertThat(daysOff(absent)).containsOnlyOnce(JOUR);
    }

    @Test
    void markingAbsentRefusesAnUnknownAnimateur() {
        solveScenario();

        given().header("X-Edition-Id", "E1")
                .contentType("application/json")
                .body("{\"animateurId\":\"ANIMATEUR-INEXISTANT\"}")
                .when()
                .post("/api/jour-j/absences?date=" + JOUR + "&maintenant=" + JOUR + "T" + ENTRE_LES_DEUX)
                .then()
                .statusCode(400)
                .body("message", notNullValue());
    }

    /** A lock is the operator saying "this one does not move" — refused whole, nothing written. */
    @Test
    void markingAbsentIsRefusedWhenASeatToFreeIsLocked() {
        solveScenario();
        String absent = firstAnimateurOnDuty();
        String standVerrouille = standHeldInTheAfternoon(absent);
        given().header("X-Edition-Id", "E1")
                .contentType("application/json")
                .body("{\"type\":\"STAND_JOUR\",\"standId\":\"" + standVerrouille + "\",\"jour\":\"" + JOUR + "\"}")
                .when()
                .post("/api/verrouillages")
                .then()
                .statusCode(200);
        Map<String, String> avant = persistedOccupants();

        given().header("X-Edition-Id", "E1")
                .contentType("application/json")
                .body("{\"animateurId\":\"" + absent + "\"}")
                .when()
                .post("/api/jour-j/absences?date=" + JOUR + "&maintenant=" + JOUR + "T" + ENTRE_LES_DEUX)
                .then()
                .statusCode(400)
                .body("message", containsString(standVerrouille));

        assertThat(daysOff(absent)).doesNotContain(JOUR);
        assertThat(persistedOccupants()).isEqualTo(avant);
    }

    /* ------------------------------ Annulation ----------------------------- */

    @Test
    void cancellingAnAbsenceRemovesTheDayOff() {
        solveScenario();
        String absent = firstAnimateurOnDuty();
        markAbsent(absent, null, ENTRE_LES_DEUX, 200);
        assertThat(daysOff(absent)).contains(JOUR);

        given().header("X-Edition-Id", "E1")
                .when()
                .delete("/api/jour-j/absences/" + absent + "?date=" + JOUR)
                .then()
                .statusCode(200)
                .body("supprimees", equalTo(1));

        assertThat(daysOff(absent)).doesNotContain(JOUR);
        assertThat(etat(ENTRE_LES_DEUX).getList("absences")).isEmpty();
    }

    /** A day off is a whole day: naming a timeslot when cancelling is read for compatibility and lifts all of it. */
    @Test
    void cancellingByTimeslotStillLiftsTheWholeDay() {
        solveScenario();
        String absent = firstAnimateurOnDuty();
        markAbsent(absent, null, "00:00", 200);
        assertThat(daysOff(absent)).contains(JOUR);

        given().header("X-Edition-Id", "E1")
                .when()
                .delete("/api/jour-j/absences/" + absent + "?date=" + JOUR + "&creneauId=" + afternoonCreneauId())
                .then()
                .statusCode(200)
                .body("supprimees", equalTo(1));

        assertThat(daysOff(absent)).doesNotContain(JOUR);
    }

    @Test
    void cancellingWhatWasNeverMarkedIsA404() {
        solveScenario();
        String absent = firstAnimateurOnDuty();

        given().header("X-Edition-Id", "E1")
                .when()
                .delete("/api/jour-j/absences/" + absent + "?date=" + JOUR)
                .then()
                .statusCode(404)
                .body("message", notNullValue());
    }

    /* ------------------------------ Suggestions ---------------------------- */

    /**
     * The seats freed by an absence are exactly what the screen then asks
     * replacements for — over the persisted plan, without uploading it.
     */
    @Test
    void suggestionsAnswerOnASeatTheAbsenceJustFreed() {
        solveScenario();
        String absent = firstAnimateurOnDuty();
        JsonPath marquee = markAbsent(absent, null, ENTRE_LES_DEUX, 200);
        String poste = marquee.getList("postesLiberes.posteId", String.class).getFirst();

        JsonPath suggestions = given().header("X-Edition-Id", "E1")
                .when()
                .post("/api/jour-j/postes/" + poste + "/suggestions")
                .then()
                .statusCode(200)
                .body("posteId", equalTo(poste))
                .body("animateurActuelId", org.hamcrest.Matchers.nullValue())
                .extract()
                .jsonPath();

        assertThat(suggestions.getInt("plafond")).isEqualTo(20);
        // The person just marked absent is never proposed back: the day off
        // makes every simulation of them lose hard points.
        assertThat(suggestions.getList("suggestions.animateurId", String.class)).doesNotContain(absent);
    }

    @Test
    void suggestionsRefuseAnUnknownSeat() {
        solveScenario();

        given().header("X-Edition-Id", "E1")
                .when()
                .post("/api/jour-j/postes/POSTE-INEXISTANT/suggestions")
                .then()
                .statusCode(404)
                .body("message", notNullValue());
    }

    @Test
    void aMalformedReferenceTimeIsA400() {
        given().header("X-Edition-Id", "E1")
                .when()
                .get("/api/jour-j?date=" + JOUR + "&maintenant=midi")
                .then()
                .statusCode(400)
                .body("message", notNullValue());
    }

    /* ----------------------- Regressions of the review ---------------------- */

    /**
     * With nothing published there is no plan to be missing from: the day off an
     * absence writes is listed all the same, so it is seen and can be cancelled.
     */
    @Test
    void anAbsenceMarkedBeforeAnyPublicationIsListedAndCancellable() {
        solveScenario();
        String absent = firstAnimateurOnDuty();

        markAbsent(absent, null, ENTRE_LES_DEUX, 200);

        JsonPath etat = etat(ENTRE_LES_DEUX);
        assertThat(etat.getList("absences.animateurId", String.class)).containsExactly(absent);
        assertThat(etat.getList("absences[0].entrees.annulable", Boolean.class)).containsOnly(true);

        given().header("X-Edition-Id", "E1")
                .when()
                .delete("/api/jour-j/absences/" + absent + "?date=" + JOUR)
                .then()
                .statusCode(200)
                .body("supprimees", equalTo(1));
        assertThat(etat(ENTRE_LES_DEUX).getList("absences")).isEmpty();
    }

    /**
     * A day off on the fiche is a missing person only for the screen that asks
     * « who is missing today »: somebody the published plan expected that day.
     * The animateur is listed and flagged on the on-duty list. Written by
     * hand on the fiche rather than through this screen, because marking
     * somebody absent frees their seats — they then hold none and leave the
     * on-duty list altogether, where the flag could not be seen.
     */
    @Test
    void aDayOffOnTheFicheCountsAsAbsentOnceThePlanIsPublished() {
        solveScenario();
        String surPlace = firstAnimateurOnDuty();
        putDaysOff(surPlace, List.of(JOUR));

        publish();

        JsonPath etat = etat(ENTRE_LES_DEUX);
        assertThat(etat.getList("absences.animateurId", String.class)).containsExactly(surPlace);
        assertThat(etat.getBoolean("animateursDeService.find { it.animateurId == '" + surPlace + "' }.absent"))
                .isTrue();
    }

    /**
     * A shift opened at 22:00 and closing at 02:00 carries the previous day's
     * date. At one in the morning it is the one running — and the one somebody
     * may have failed to show up for. Keyed on the date column it was invisible
     * to this screen entirely.
     *
     * <p>A journée starts at its first timeslot. At one in the morning the journée
     * under way is therefore still the one that opened the previous morning, and
     * the screen names it without being told which day to look at.</p>
     */
    @Test
    void atOneInTheMorningTheJourneeIsStillTheOneThatOpenedYesterday() {
        solveScenarioWithNightShift();

        JsonPath etat = given().header("X-Edition-Id", "E1")
                .when()
                .get("/api/jour-j?maintenant=2026-07-09T01:00")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath();

        assertThat(etat.getString("date"))
                .as("the journée that opened at 09:00 yesterday")
                .isEqualTo(JOUR);
        assertThat(etat.getList("creneauxRestants.id", Integer.class))
                .as("the night shift is what is running at 01:00")
                .containsExactly(CRENEAU_NUIT);
        assertThat(etat.getBoolean("creneauxRestants[0].enCours")).isTrue();
    }

    /**
     * The night shift belongs to the evening that opens it and to that evening
     * alone: it does not show up again on the next calendar day.
     */
    @Test
    void aShiftCrossingMidnightDoesNotSplitOverTwoJournees() {
        solveScenarioWithNightShift();

        JsonPath etat = given().header("X-Edition-Id", "E1")
                .when()
                .get("/api/jour-j?date=2026-07-09")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath();

        assertThat(etat.getInt("creneauxDuJour")).isZero();
        assertThat(etat.getList("creneauxRestants")).isEmpty();
    }

    /** Once the last timeslot has ended, no journée is under way: the calendar date answers. */
    @Test
    void onceTheLastTimeslotHasEndedTheCalendarDateAnswers() {
        solveScenarioWithNightShift();

        JsonPath etat = given().header("X-Edition-Id", "E1")
                .when()
                .get("/api/jour-j?maintenant=2026-07-09T03:00")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath();

        assertThat(etat.getString("date")).isEqualTo("2026-07-09");
        assertThat(etat.getInt("creneauxDuJour")).isZero();
    }

    /** Before the first timeslot opens, nothing is under way either. */
    @Test
    void beforeTheFirstTimeslotOpensTheCalendarDateAnswers() {
        solveScenarioWithNightShift();

        JsonPath etat = given().header("X-Edition-Id", "E1")
                .when()
                .get("/api/jour-j?maintenant=" + JOUR + "T06:00")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath();

        assertThat(etat.getString("date")).isEqualTo(JOUR);
        assertThat(etat.getInt("creneauxDuJour")).isEqualTo(3);
        assertThat(etat.getList("creneauxRestants")).hasSize(3);
    }

    /** It is still the journée's own timeslot, so an absence marked at 01:00 lists it with the rest of the day. */
    @Test
    void anAbsenceAtOneInTheMorningListsTheRunningNightShiftWithTheWholeDay() {
        solveScenarioWithNightShift();

        JsonPath marquee = given().header("X-Edition-Id", "E1")
                .contentType("application/json")
                .body("{\"animateurId\":\"A1\"}")
                .when()
                .post("/api/jour-j/absences?maintenant=2026-07-09T01:00")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath();

        assertThat(marquee.getList("entrees.creneauId", Integer.class))
                .hasSize(3)
                .contains(CRENEAU_NUIT);
    }

    /** On the evening it opens, it is the last timeslot still ahead. */
    @Test
    void aShiftCrossingMidnightIsTheLastOneAheadOnItsOwnEvening() {
        solveScenarioWithNightShift();

        JsonPath etat = given().header("X-Edition-Id", "E1")
                .when()
                .get("/api/jour-j?maintenant=" + JOUR + "T23:00")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath();

        assertThat(etat.getString("date")).isEqualTo(JOUR);
        assertThat(etat.getList("creneauxRestants.id", Integer.class)).containsExactly(CRENEAU_NUIT);
        assertThat(etat.getInt("creneauxDuJour")).isEqualTo(3);
    }

    /**
     * A date carrying no timeslot has no first one, so no journée. The screen
     * answers an empty day rather than failing, and marking somebody absent on it
     * is refused with a sentence saying why.
     */
    @Test
    void aDateWithNoTimeslotIsAnEmptyJournee() {
        solveScenario();

        JsonPath etat = given().header("X-Edition-Id", "E1")
                .when()
                .get("/api/jour-j?date=2026-12-25")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath();

        assertThat(etat.getString("date")).isEqualTo("2026-12-25");
        assertThat(etat.getInt("creneauxDuJour")).isZero();
        assertThat(etat.getList("creneauxRestants")).isEmpty();
        assertThat(etat.getList("animateursDeService")).isEmpty();
        assertThat(etat.getList("postesAPourvoir")).isEmpty();

        given().header("X-Edition-Id", "E1")
                .contentType("application/json")
                .body("{\"animateurId\":\"A1\"}")
                .when()
                .post("/api/jour-j/absences?date=2026-12-25")
                .then()
                .statusCode(400)
                .body("message", containsString("Aucun créneau n'est programmé"));
    }

    /** Freeing several seats at once is one gesture, and it frees all of them. */
    @Test
    void markingAbsentFromTheStartOfTheDayFreesEverySeatHeld() {
        solveScenario();
        String absent = firstAnimateurOnDuty();
        Map<String, String> avant = persistedOccupants();
        List<String> tenus = avant.entrySet().stream()
                .filter(siege -> siege.getValue().equals(absent))
                .map(Map.Entry::getKey)
                .toList();
        assertThat(tenus).isNotEmpty();

        JsonPath marquee = markAbsent(absent, null, "00:00", 200);

        assertThat(marquee.getList("postesLiberes.posteId", String.class)).containsExactlyInAnyOrderElementsOf(tenus);
        Map<String, String> apres = persistedOccupants();
        assertThat(tenus).allSatisfy(poste -> assertThat(apres).doesNotContainKey(poste));
    }

    /**
     * The suggestions name people. The best replacement is by definition
     * somebody <em>not</em> on duty, so the screen cannot name them from the
     * on-duty list — the roster travels with the state for that.
     */
    @Test
    void theStateCarriesTheWholeRosterSoCandidatesCanBeNamed() {
        solveScenario();

        JsonPath etat = etat(ENTRE_LES_DEUX);

        List<String> ids = etat.getList("animateurs.animateurId", String.class);
        assertThat(ids).contains("A1", "A2", "A3");
        assertThat(etat.getString("animateurs.find { it.animateurId == 'A1' }.nomAffiche"))
                .isEqualTo("Alice Referente");
    }

    /* ------------------------------- Fixtures ------------------------------ */

    private static void solveScenario() {
        String sample = given().header("X-Edition-Id", "E1")
                .when()
                .get("/api/planning/sample?name=scenario.yml")
                .then()
                .statusCode(200)
                .extract()
                .asString();
        given().header("X-Edition-Id", "E1")
                .contentType("application/json")
                .body(sample)
                .when()
                .post("/api/solve?seconds=3")
                .then()
                .statusCode(200);
    }

    /** Reads the journée of {@link #JOUR} at that wall-clock time on it. */
    private static JsonPath etat(String heure) {
        return given().header("X-Edition-Id", "E1")
                .when()
                .get("/api/jour-j?date=" + JOUR + "&maintenant=" + JOUR + "T" + heure)
                .then()
                .statusCode(200)
                .extract()
                .jsonPath();
    }

    private static JsonPath markAbsent(String animateurId, String raison, String heure, int statut) {
        String body = raison == null
                ? "{\"animateurId\":\"" + animateurId + "\"}"
                : "{\"animateurId\":\"" + animateurId + "\",\"raison\":\"" + raison + "\"}";
        return given().header("X-Edition-Id", "E1")
                .contentType("application/json")
                .body(body)
                .when()
                .post("/api/jour-j/absences?date=" + JOUR + "&maintenant=" + JOUR + "T" + heure)
                .then()
                .statusCode(statut)
                .extract()
                .jsonPath();
    }

    private static String firstAnimateurOnDuty() {
        List<String> ids = etat(ENTRE_LES_DEUX).getList("animateursDeService.animateurId", String.class);
        assertThat(ids)
                .as("somebody must be on duty for this test to mean anything")
                .isNotEmpty();
        return ids.getFirst();
    }

    private static long afternoonCreneauId() {
        return etat(ENTRE_LES_DEUX).getLong("creneauxRestants[0].id");
    }

    /** Explicit id of the seeded night shift — see {@link #solveScenarioWithNightShift()}. */
    private static final int CRENEAU_NUIT = 3;

    /**
     * The scenario plus a 22:00-02:00 shift on the same day.
     *
     * <p>Seeded <b>through the solve payload</b>, with an explicit id, rather
     * than through {@code POST /api/creneaux}: the scenario inserts its own
     * timeslots under explicit ids 1 and 2 without advancing the table's
     * sequence, so a generated id collides with them. Adding one seat carrying a
     * new timeslot is how the scenario itself gets its referential in.</p>
     */
    private static void solveScenarioWithNightShift() {
        String sample = given().header("X-Edition-Id", "E1")
                .when()
                .get("/api/planning/sample?name=scenario.yml")
                .then()
                .statusCode(200)
                .extract()
                .asString();
        String avecNuit = withNightShift(sample);
        given().header("X-Edition-Id", "E1")
                .contentType("application/json")
                .body(avecNuit)
                .when()
                .post("/api/solve?seconds=3")
                .then()
                .statusCode(200);
    }

    /** Copies the first seat onto a new timeslot that runs from 22:00 to 02:00. */
    private static String withNightShift(String planningJson) {
        try {
            ObjectNode plan = (ObjectNode) JSON.readTree(planningJson);
            ArrayNode postes = (ArrayNode) plan.get("postes");
            ObjectNode nuit = ((ObjectNode) postes.get(0)).deepCopy();
            nuit.put("id", "P-NUIT");
            nuit.putNull("animateur");
            ObjectNode creneau = (ObjectNode) nuit.get("creneau");
            creneau.put("id", CRENEAU_NUIT);
            creneau.put("date", JOUR);
            creneau.put("heureDebut", "22:00:00");
            creneau.put("heureFin", "02:00:00");
            postes.add(nuit);
            return JSON.writeValueAsString(plan);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The stand this animateur holds on the afternoon slot — the seat a lock will freeze. */
    private static String standHeldInTheAfternoon(String animateurId) {
        JsonPath persiste = persistedPlanning();
        long creneau = afternoonCreneauId();
        List<Map<String, Object>> postes =
                persiste.getList("postes.findAll { it.animateur != null && it.animateur.id == '" + animateurId
                        + "' && it.creneau.id == " + creneau + " }");
        assertThat(postes).isNotEmpty();
        @SuppressWarnings("unchecked")
        Map<String, Object> stand = (Map<String, Object>) postes.getFirst().get("stand");
        return (String) stand.get("id");
    }

    /** Ids of the seats of the persisted plan whose timeslot starts at {@code heureDebut}. */
    private static List<String> seatsStartingAt(java.util.Collection<String> postes, String heureDebut) {
        JsonPath persiste = persistedPlanning();
        List<String> ids = persiste.getList("postes.id", String.class);
        List<String> retenus = new java.util.ArrayList<>();
        for (int i = 0; i < ids.size(); i++) {
            String debut = persiste.getString("postes[" + i + "].creneau.heureDebut");
            if (debut != null && debut.startsWith(heureDebut) && postes.contains(ids.get(i))) {
                retenus.add(ids.get(i));
            }
        }
        return retenus;
    }

    private static void publish() {
        given().header("X-Edition-Id", "E1")
                .contentType("application/json")
                .when()
                .post("/api/planning/publication")
                .then()
                .statusCode(200);
    }

    private static Map<String, Object> fiche(String animateurId) {
        return given().header("X-Edition-Id", "E1")
                .when()
                .get("/api/animateurs")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getMap("find { it.id == '" + animateurId + "' }");
    }

    /** The days off on an animateur's fiche. */
    private static List<String> daysOff(String animateurId) {
        List<String> jours = new java.util.ArrayList<>();
        if (fiche(animateurId).get("joursIndisponibles") instanceof List<?> existing) {
            existing.forEach(jour -> jours.add(String.valueOf(jour)));
        }
        return jours;
    }

    private static void putDaysOff(String animateurId, List<String> jours) {
        Map<String, Object> fiche = fiche(animateurId);
        fiche.put("joursIndisponibles", jours);
        given().header("X-Edition-Id", "E1")
                .contentType("application/json")
                .body(fiche)
                .when()
                .put("/api/animateurs/" + animateurId)
                .then()
                .statusCode(200);
    }

    private static int jobCount() {
        return given().header("X-Edition-Id", "E1")
                .when()
                .get("/api/jobs")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getList("id", String.class)
                .size();
    }

    private static String lastSolvedAt() {
        return given().header("X-Edition-Id", "E1")
                .when()
                .get("/api/planning/persisted/resolution")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getString("resoluLe");
    }

    private static JsonPath persistedPlanning() {
        return given().header("X-Edition-Id", "E1")
                .when()
                .get("/api/planning/persisted")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath();
    }

    /** Poste id → occupant of the persisted plan; unstaffed seats are simply absent. */
    private static Map<String, String> persistedOccupants() {
        JsonPath persiste = persistedPlanning();
        Map<String, String> occupants = new LinkedHashMap<>();
        List<String> ids = persiste.getList("postes.id", String.class);
        for (int i = 0; i < ids.size(); i++) {
            String animateurId = persiste.getString("postes[" + i + "].animateur.id");
            if (animateurId != null) {
                occupants.put(ids.get(i), animateurId);
            }
        }
        return occupants;
    }
}
