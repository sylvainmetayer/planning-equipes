package dev.sylvain.planning.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.notNullValue;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.service.referentiel.ReferenceDataService;
import io.quarkus.mailer.MockMailbox;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.RestAssured;
import io.restassured.builder.RequestSpecBuilder;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Rate limit on a colleague's seats: one espace session must not be able to
 * walk the roster and rebuild the whole event's grid, while someone looking
 * for a swap never meets the ceiling.
 *
 * <p>Its own profile, like {@link DeclarationRateLimitTest}: the ceiling drops
 * to two distinct colleagues, and a profile is a separate application, hence a
 * counter no other test class has already spent.</p>
 */
@QuarkusTest
@TestProfile(ColleagueLookupRateLimitTest.Profil.class)
class ColleagueLookupRateLimitTest {

    public static class Profil implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("planning.espace.collegues.max-collegues", "2", "planning.espace.collegues.fenetre", "PT1H");
        }
    }

    private static final String ME = "DEBIT-COL-A";
    /** Labels only, borne as each colleague's nom: their ids are drawn (ADR 0050). */
    private static final List<String> COLLEAGUES = List.of("DEBIT-COL-B", "DEBIT-COL-C", "DEBIT-COL-D");

    /** Label → the id the application gave that fiche. */
    private final Map<String, String> ids = new HashMap<>();

    private static final String EMAIL = "debit-col@example.org";

    @Inject
    ReferenceDataService referenceData;

    @Inject
    MockMailbox mailbox;

    @BeforeEach
    void seed() {
        RestAssured.requestSpecification = null;
        removeFixture();
        Animateur me = new Animateur(ME, "Carla", "Roux", LocalDate.of(1991, 3, 3), false);
        me.setEmail(EMAIL);
        ids.put(ME, referenceData.createAnimateur(me).getId());
        for (String label : COLLEAGUES) {
            Animateur collegue = new Animateur(null, "Camille", label, LocalDate.of(1990, 1, 1), false);
            ids.put(label, referenceData.createAnimateur(collegue).getId());
        }
        given().contentType(ContentType.JSON)
                .body("{\"foireOuverte\":true}")
                .when()
                .put("/api/echanges/configuration")
                .then()
                .statusCode(200);
        mailbox.clear();
        String session = EspaceSessions.open(mailbox, token(), EMAIL);
        RestAssured.requestSpecification =
                new RequestSpecBuilder().addCookie("planning-espace", session).build();
    }

    @AfterEach
    void cleanUp() {
        RestAssured.requestSpecification = null;
        removeFixture();
    }

    @Test
    void pastTheCeilingOfDistinctColleaguesTheirSeatsAreRefusedWithADelay() {
        seatsOf("DEBIT-COL-B").then().statusCode(200);
        seatsOf("DEBIT-COL-C").then().statusCode(200);
        // The same colleague again costs nothing: that is how a person searches.
        seatsOf("DEBIT-COL-B").then().statusCode(200);

        seatsOf("DEBIT-COL-D")
                .then()
                .statusCode(429)
                .header("Retry-After", notNullValue())
                .body("message", containsString("sans choisir son créneau"));
        // An id nobody bears counts like a real one: probing costs what reading does.
        seatsOf("PERSONNE").then().statusCode(429);
        // The colleagues already looked at stay readable.
        seatsOf("DEBIT-COL-C").then().statusCode(200);
    }

    /** A label of this fixture, or any other string taken as an id nobody bears. */
    private Response seatsOf(String collegue) {
        String collegueId = ids.getOrDefault(collegue, collegue);
        return given().when().get("/api/espace-animateur/" + token() + "/collegues/" + collegueId + "/postes");
    }

    private String token() {
        return referenceData.listAnimateurs().stream()
                .filter(candidat -> candidat.getId().equals(ids.get(ME)))
                .findFirst()
                .orElseThrow()
                .getAccessToken();
    }

    /** Leaves the shared database as it was found. */
    private void removeFixture() {
        referenceData.listAnimateurs().stream()
                .filter(animateur -> EMAIL.equals(animateur.getEmail()) || COLLEAGUES.contains(animateur.getNom()))
                .map(Animateur::getId)
                .forEach(referenceData::deleteAnimateur);
        ids.clear();
    }
}
