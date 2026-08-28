package dev.sylvain.planning.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;

/**
 * The event-day screen (issue #297) end to end, over {@code scenario.yml}: one
 * day (2026-07-08), a morning slot 09:00-13:00 and an afternoon one 14:00-18:00.
 *
 * <p>Every test states the moment it reads the day from ({@code heure=…})
 * rather than depending on the wall clock: the whole feature is "what is still
 * ahead", so a test whose reference time is the machine's would assert
 * something different every afternoon.</p>
 */
@QuarkusTest
class JourJResourceTest {

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
        given().when().post("/api/planning/reset").then().statusCode(200);
    }

    /* --------------------------------- State ------------------------------- */

    @Test
    void etatKeepsOnlyTheTimeslotsStillAhead() {
        solveScenario();

        JsonPath etat = etat(ENTRE_LES_DEUX);

        assertThat(etat.getString("date")).isEqualTo(JOUR);
        assertThat(etat.getString("heureReference")).startsWith(ENTRE_LES_DEUX);
        assertThat(etat.getInt("creneauxDuJour")).isEqualTo(2);
        assertThat(etat.getList("creneauxRestants.heureDebut", String.class))
                .singleElement().asString().startsWith("14:00");
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

        // One forced unavailability, on the afternoon slot only.
        assertThat(marquee.getList("entrees.heureDebut", String.class))
                .singleElement().asString().startsWith("14:00");
        // The morning seats keep the person that really held them.
        Map<String, String> apres = persistedOccupants();
        for (String poste : postesDuMatin) {
            assertThat(apres.get(poste)).as("poste %s du matin", poste).isEqualTo(avant.get(poste));
        }
        // And the afternoon ones they held are now empty.
        List<String> liberes = marquee.getList("postesLiberes.posteId", String.class);
        assertThat(liberes).allSatisfy(poste -> assertThat(apres).doesNotContainKey(poste));
    }

    @Test
    void markingAbsentRecordsWhoAndWhen() {
        solveScenario();
        String absent = firstAnimateurOnDuty();

        markAbsent(absent, "Malade", ENTRE_LES_DEUX, 200);

        JsonPath contraintes = given().when().get("/api/contraintes-ad-hoc")
                .then().statusCode(200).extract().jsonPath();
        List<Map<String, Object>> indisponibilites = contraintes.getList(
                "findAll { it.type == 'INDISPONIBILITE_FORCEE' }");
        assertThat(indisponibilites).hasSize(1);
        Map<String, Object> trace = indisponibilites.getFirst();
        assertThat((String) trace.get("raison")).contains("mode jour J").contains("Malade");
        assertThat((String) trace.get("creeParUtilisateurId")).isNotBlank();
        assertThat(trace.get("creeLe")).isNotNull();
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

        assertThat(forcedUnavailabilities()).hasSize(1);
    }

    @Test
    void markingAbsentRefusesAnUnknownAnimateur() {
        solveScenario();

        given().contentType("application/json")
                .body("{\"animateurId\":\"ANIMATEUR-INEXISTANT\"}")
                .when().post("/api/jour-j/absences?date=" + JOUR + "&heure=" + ENTRE_LES_DEUX)
                .then().statusCode(400).body("message", notNullValue());
        assertThat(forcedUnavailabilities()).isEmpty();
    }

    /**
     * The contradiction {@code ContrainteAdHocContradictions} already knows how
     * to name: forcing somebody onto a seat they are declared unavailable for.
     * Marking the absence must hit it <b>at entry</b>, name both exceptions, and
     * leave the plan exactly as it was — not write half of the unavailabilities
     * and let the next solve report a negative hard score.
     */
    @Test
    void markingAbsentIsRefusedWhenItContradictsAForcedAssignment() {
        solveScenario();
        String absent = firstAnimateurOnDuty();
        long afternoonCreneauId = afternoonCreneauId();
        given().contentType("application/json")
                .body("""
                        {"id":"FORCE-APRES-MIDI","type":"AFFECTATION_FORCEE",
                         "animateursConcernes":[{"id":"%s"}],"creneau":{"id":%d}}"""
                        .formatted(absent, afternoonCreneauId))
                .when().post("/api/contraintes-ad-hoc")
                .then().statusCode(200);
        Map<String, String> avant = persistedOccupants();

        given().contentType("application/json")
                .body("{\"animateurId\":\"" + absent + "\"}")
                .when().post("/api/jour-j/absences?date=" + JOUR + "&heure=" + ENTRE_LES_DEUX)
                .then().statusCode(400)
                // Both exceptions are named: the one already recorded, and the
                // one the absence would have written.
                .body("message", containsString("FORCE-APRES-MIDI"))
                .body("message", containsString("absence-jour-j-" + absent));

        assertThat(forcedUnavailabilities()).isEmpty();
        assertThat(persistedOccupants()).isEqualTo(avant);
    }

    /** A lock is the operator saying "this one does not move" — refused whole, nothing written. */
    @Test
    void markingAbsentIsRefusedWhenASeatToFreeIsLocked() {
        solveScenario();
        String absent = firstAnimateurOnDuty();
        String standVerrouille = standHeldInTheAfternoon(absent);
        given().contentType("application/json")
                .body("{\"type\":\"STAND\",\"standId\":\"" + standVerrouille + "\"}")
                .when().post("/api/verrouillages").then().statusCode(200);
        Map<String, String> avant = persistedOccupants();

        given().contentType("application/json")
                .body("{\"animateurId\":\"" + absent + "\"}")
                .when().post("/api/jour-j/absences?date=" + JOUR + "&heure=" + ENTRE_LES_DEUX)
                .then().statusCode(400).body("message", containsString(standVerrouille));

        assertThat(forcedUnavailabilities()).isEmpty();
        assertThat(persistedOccupants()).isEqualTo(avant);
    }

    /* ------------------------------ Annulation ----------------------------- */

    @Test
    void cancellingAnAbsenceRemovesItsUnavailabilities() {
        solveScenario();
        String absent = firstAnimateurOnDuty();
        markAbsent(absent, null, ENTRE_LES_DEUX, 200);
        assertThat(forcedUnavailabilities()).hasSize(1);

        given().when().delete("/api/jour-j/absences/" + absent + "?date=" + JOUR)
                .then().statusCode(200).body("supprimees", equalTo(1));

        assertThat(forcedUnavailabilities()).isEmpty();
        assertThat(etat(ENTRE_LES_DEUX).getList("absences")).isEmpty();
    }

    /** Reversible timeslot by timeslot, not only as a block. */
    @Test
    void cancellingOneTimeslotLeavesTheOthers() {
        solveScenario();
        String absent = firstAnimateurOnDuty();
        // From the start of the day: both timeslots are ahead.
        markAbsent(absent, null, "00:00", 200);
        assertThat(forcedUnavailabilities()).hasSize(2);

        given().when().delete("/api/jour-j/absences/" + absent
                + "?date=" + JOUR + "&creneauId=" + afternoonCreneauId())
                .then().statusCode(200).body("supprimees", equalTo(1));

        List<Map<String, Object>> restantes = forcedUnavailabilities();
        assertThat(restantes).hasSize(1);
        assertThat((String) restantes.getFirst().get("id"))
                .doesNotContain(String.valueOf(afternoonCreneauId()));
    }

    @Test
    void cancellingWhatWasNeverMarkedIsA404() {
        solveScenario();
        String absent = firstAnimateurOnDuty();

        given().when().delete("/api/jour-j/absences/" + absent + "?date=" + JOUR)
                .then().statusCode(404).body("message", notNullValue());
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

        JsonPath suggestions = given()
                .when().post("/api/jour-j/postes/" + poste + "/suggestions")
                .then().statusCode(200)
                .body("posteId", equalTo(poste))
                .body("animateurActuelId", org.hamcrest.Matchers.nullValue())
                .extract().jsonPath();

        assertThat(suggestions.getInt("plafond")).isEqualTo(20);
        // The person just marked absent is never proposed back: the forced
        // unavailability makes every simulation of them lose hard points.
        assertThat(suggestions.getList("suggestions.animateurId", String.class)).doesNotContain(absent);
    }

    @Test
    void suggestionsRefuseAnUnknownSeat() {
        solveScenario();

        given().when().post("/api/jour-j/postes/POSTE-INEXISTANT/suggestions")
                .then().statusCode(404).body("message", notNullValue());
    }

    @Test
    void aMalformedReferenceTimeIsA400() {
        given().when().get("/api/jour-j?date=" + JOUR + "&heure=midi")
                .then().statusCode(400).body("message", notNullValue());
    }

    /* ------------------------------- Fixtures ------------------------------ */

    private static void solveScenario() {
        String sample = given().when().get("/api/planning/sample?name=scenario.yml")
                .then().statusCode(200).extract().asString();
        given().contentType("application/json").body(sample)
                .when().post("/api/solve?seconds=3").then().statusCode(200);
    }

    private static JsonPath etat(String heure) {
        return given().when().get("/api/jour-j?date=" + JOUR + "&heure=" + heure)
                .then().statusCode(200).extract().jsonPath();
    }

    private static JsonPath markAbsent(String animateurId, String raison, String heure, int statut) {
        String body = raison == null
                ? "{\"animateurId\":\"" + animateurId + "\"}"
                : "{\"animateurId\":\"" + animateurId + "\",\"raison\":\"" + raison + "\"}";
        return given().contentType("application/json").body(body)
                .when().post("/api/jour-j/absences?date=" + JOUR + "&heure=" + heure)
                .then().statusCode(statut).extract().jsonPath();
    }

    private static String firstAnimateurOnDuty() {
        List<String> ids = etat(ENTRE_LES_DEUX).getList("animateursDeService.animateurId", String.class);
        assertThat(ids).as("somebody must be on duty for this test to mean anything").isNotEmpty();
        return ids.getFirst();
    }

    private static long afternoonCreneauId() {
        return etat(ENTRE_LES_DEUX).getLong("creneauxRestants[0].id");
    }

    /** The stand this animateur holds on the afternoon slot — the seat a lock will freeze. */
    private static String standHeldInTheAfternoon(String animateurId) {
        JsonPath persiste = persistedPlanning();
        long creneau = afternoonCreneauId();
        List<Map<String, Object>> postes = persiste.getList(
                "postes.findAll { it.animateur != null && it.animateur.id == '" + animateurId
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

    private static List<Map<String, Object>> forcedUnavailabilities() {
        return given().when().get("/api/contraintes-ad-hoc").then().statusCode(200)
                .extract().jsonPath().getList("findAll { it.type == 'INDISPONIBILITE_FORCEE' }");
    }

    private static int jobCount() {
        return given().when().get("/api/jobs").then().statusCode(200)
                .extract().jsonPath().getList("id", String.class).size();
    }

    private static String lastSolvedAt() {
        return given().when().get("/api/planning/persisted/resolution")
                .then().statusCode(200).extract().jsonPath().getString("resoluLe");
    }

    private static JsonPath persistedPlanning() {
        return given().when().get("/api/planning/persisted").then().statusCode(200).extract().jsonPath();
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
