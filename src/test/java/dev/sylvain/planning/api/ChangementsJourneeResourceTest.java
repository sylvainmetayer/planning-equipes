package dev.sylvain.planning.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The Changements rendering of the Journée page over the API: the two
 * references, what each one says when it does not exist, and a day that
 * really moved between two solves and one publication.
 *
 * <p>The sample scenario is one day (2026-07-08); the day is made to move by
 * declaring a seated animateur unavailable, the one late change that makes
 * the move certain rather than hoped for.</p>
 */
@QuarkusTest
class ChangementsJourneeResourceTest {

    private static final String JOUR = "2026-07-08";

    @Inject
    DataSource dataSource;

    /**
     * Snapshots survive the reset — that is what they are for — so a published
     * or automatique one left by another class would pass for a reference
     * here. The suite shares one database: cleared on both sides.
     */
    @BeforeEach
    void clean() {
        forgetSnapshots();
    }

    @AfterEach
    void resetDatabase() {
        given().when().post("/api/planning/reset").then().statusCode(200);
        forgetSnapshots();
    }

    private void forgetSnapshots() {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM plan_snapshot");
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to clear the plan snapshots", e);
        }
    }

    /** Solves the sample scenario, which persists a plan for the single day above. */
    private static void solve() {
        String sample = given().when()
                .get("/api/planning/sample?name=scenario.yml")
                .then()
                .statusCode(200)
                .extract()
                .asString();
        given().contentType("application/json")
                .body(sample)
                .when()
                .post("/api/solve?seconds=3")
                .then()
                .statusCode(200);
    }

    private static JsonPath changements(String query) {
        return given().when()
                .get("/api/journees/" + JOUR + "/changements" + query)
                .then()
                .statusCode(200)
                .extract()
                .jsonPath();
    }

    @Test
    void beforeAnyPublicationOrSecondSolveBothReferencesAreMissing() {
        solve();

        // Never published: the default falls back on the last solve — which
        // captured nothing, the first solve of an edition replacing no plan.
        JsonPath parDefaut = changements("");
        assertThat(parDefaut.getString("reference")).isEqualTo("RESOLUTION");
        assertThat(parDefaut.getBoolean("referenceDisponible")).isFalse();
        assertThat(parDefaut.getObject("referenceLe", Object.class)).isNull();

        given().when()
                .get("/api/journees/" + JOUR + "/changements?reference=publication")
                .then()
                .statusCode(200)
                .body("reference", equalTo("PUBLICATION"))
                .body("referenceDisponible", is(false))
                .body("nouveaux", is(0))
                .body("parVacation.size()", is(0));
    }

    @Test
    void aDayThatMovedSinceThePublicationIsReadSeatBySeatAndPersonByPerson() {
        solve();
        String absent = aSeatedAnimateur();
        publish();
        markUnavailable(absent);
        solve();

        JsonPath depuisPublication = changements("");
        assertThat(depuisPublication.getString("reference")).isEqualTo("PUBLICATION");
        assertThat(depuisPublication.getBoolean("referenceDisponible")).isTrue();
        assertThat(depuisPublication.getString("referenceLe")).isNotBlank();
        assertThat(depuisPublication.getInt("retires") + depuisPublication.getInt("remplaces"))
                .isGreaterThan(0);
        List<String> avant = depuisPublication.getList("parVacation.avant.animateurId");
        assertThat(avant).contains(absent);
        assertThat(depuisPublication.getList("parVacation.date", String.class)).containsOnly(JOUR);
        List<String> concernes = depuisPublication.getList("parAnimateur.animateurId");
        assertThat(concernes).contains(absent);
        assertThat(depuisPublication.getInt("animateursConcernes")).isEqualTo(concernes.size());
        List<String> lignes = depuisPublication.getList(
                "parAnimateur.find { it.animateurId == '" + absent + "' }.changements.libelle");
        assertThat(lignes).isNotEmpty().allMatch(ligne -> ligne.contains("08/07"));

        // The second solve captured the plan it replaced — the published one —
        // so the other reference tells the same story here.
        JsonPath depuisResolution = changements("?reference=resolution");
        assertThat(depuisResolution.getString("reference")).isEqualTo("RESOLUTION");
        assertThat(depuisResolution.getBoolean("referenceDisponible")).isTrue();
        assertThat(depuisResolution.getList("parVacation.avant.animateurId")).contains(absent);
    }

    @Test
    void anUnreadableDayOrReferenceIsRefused() {
        given().when()
                .get("/api/journees/hier/changements")
                .then()
                .statusCode(400)
                .body(containsString("illisible"));
        given().when()
                .get("/api/journees/" + JOUR + "/changements?reference=instantane")
                .then()
                .statusCode(400)
                .body(containsString("Référence inconnue"));
    }

    @Test
    void aDayTheGridDoesNotHoldIsAnEmptyAnswer() {
        solve();
        publish();

        given().when()
                .get("/api/journees/2026-12-25/changements?reference=publication")
                .then()
                .statusCode(200)
                .body("referenceDisponible", is(true))
                .body("parVacation.size()", is(0))
                .body("parAnimateur.size()", is(0))
                .body("referenceLe", is(notNullValue()));
    }

    private static void publish() {
        given().contentType("application/json")
                .when()
                .post("/api/planning/publication")
                .then()
                .statusCode(200);
    }

    /** An animateur the persisted plan seats somewhere on the day. */
    private static String aSeatedAnimateur() {
        String id = given().when()
                .get("/api/planning/persisted")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getString("postes.find { it.animateur != null }.animateur.id");
        assertThat(id).as("the sample plan should seat somebody").isNotBlank();
        return id;
    }

    /** A late change the next solve has to work around: this person is out that day. */
    private static void markUnavailable(String animateurId) {
        given().contentType("application/json")
                .body("""
                        {"id":"CHANGEMENTS-TEST-INDISPO","type":"INDISPONIBILITE_FORCEE",
                         "animateursConcernes":[{"id":"%s"}],"jour":"%s"}""".formatted(animateurId, JOUR))
                .when()
                .post("/api/contraintes-ad-hoc")
                .then()
                .statusCode(200);
    }
}
