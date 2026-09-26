package dev.sylvain.planning.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

import io.quarkus.test.junit.QuarkusTest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Weighting a constraint, end to end: the HTTP surface, the SQL behind it, and
 * the edition boundary that separates two events sharing one deployment.
 *
 * <p>What is being pinned down is <b>not</b> that a number can be stored. It is
 * that the number belongs to an edition rather than to the deployment — the
 * whole point of migration {@code V53}, and the reason the weights left
 * {@code application.properties} in the first place. A test that only checked
 * "PUT then GET returns 7" would pass just as happily on a static field.</p>
 *
 * <p>The bounds are part of the contract too, and the lower one carries a
 * decision: a weight of zero is refused because a rule scoring nothing is
 * switched off <em>in fact</em> while still displaying as active — and, for a
 * rule of public order, without ever going through the confirmation that
 * protects it. Switching off has one door, and it is the toggle.</p>
 */
@QuarkusTest
class ConstraintPoidsResourceTest {

    /** What a medium rule weighs when nobody set it: the « normale » of the three positions (ADR 0057). */
    private static final int DEFAULT_WEIGHT = 5;

    private static final String HEADER = "X-Edition-Id";

    /** MEDIUM, « Qualité d'organisation » — one of the twelve meant to be dosed. */
    private static final String DOSABLE = "equilibrerCharge";

    /** HARD, « Légal (mineurs) » — protected, and never dosable. */
    private static final String PROTEGEE = "travailDeNuitInterditPourMineur";

    /** The default edition of the test database, resolved rather than assumed (ADR 0050). */
    private String defaut;

    @BeforeEach
    void resolveDefaultEdition() {
        defaut = listEditions().stream()
                .filter(edition -> Boolean.TRUE.equals(edition.get("defaut")))
                .map(edition -> (String) edition.get("id"))
                .findFirst()
                .orElseThrow();
    }

    /**
     * Weights are an edition-scoped override, so a test that sets one leaves a
     * row behind in the default edition that the next test would read. Dropping
     * the override (a {@code null} weight) is the documented way back to the
     * configured default, so the cleanup exercises that path on every run.
     */
    @AfterEach
    void restoreWeightsAndEditions() {
        setPoids(defaut, DOSABLE, null, 200);
        setPoids(defaut, PROTEGEE, null, 200);
        for (Map<String, Object> edition : listEditions()) {
            String id = (String) edition.get("id");
            if (!defaut.equals(id)) {
                given().when().delete("/api/editions/" + id);
            }
        }
    }

    private List<Map<String, Object>> listEditions() {
        return given().when()
                .get("/api/editions")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getList("$");
    }

    /** Creates an edition and answers the id the application drew for it. */
    private String createEdition(String nom) {
        return given().contentType("application/json")
                .body("{\"nom\":\"" + nom + "\"}")
                .when()
                .post("/api/editions")
                .then()
                .statusCode(200)
                .extract()
                .path("id");
    }

    /** @param poids {@code null} sends a JSON {@code null}, which drops the override. */
    private void setPoids(String editionId, String nom, Integer poids, int expectedStatus) {
        given().header(HEADER, editionId)
                .contentType("application/json")
                .body("{\"poids\":" + (poids == null ? "null" : poids) + "}")
                .when()
                .put("/api/constraints/" + nom + "/poids")
                .then()
                .statusCode(expectedStatus);
    }

    private int readPoids(String editionId, String nom) {
        return given().header(HEADER, editionId)
                .when()
                .get("/api/constraints")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getInt("contraintes.find { it.name == '" + nom + "' }.poids");
    }

    @Test
    void theCatalogueExposesTheWeightAndWhatEachRuleAllows() {
        given().header(HEADER, defaut)
                .when()
                .get("/api/constraints")
                .then()
                .statusCode(200)
                // A deployment that tuned nothing weighs a quality rule at the « normale » position.
                .body("contraintes.find { it.name == '" + DOSABLE + "' }.poids", equalTo(DEFAULT_WEIGHT))
                .body("contraintes.find { it.name == '" + DOSABLE + "' }.dosable", equalTo(true))
                .body("contraintes.find { it.name == '" + DOSABLE + "' }.protegee", equalTo(false))
                // A rule of public order is protected, and never dosed: the UI
                // turns that into a guarded switch, not a dial.
                .body("contraintes.find { it.name == '" + PROTEGEE + "' }.protegee", equalTo(true))
                .body("contraintes.find { it.name == '" + PROTEGEE + "' }.dosable", equalTo(false));
    }

    @Test
    void aWeightSetPersistsAndIsReadBackInTheCatalogue() {
        setPoids(defaut, DOSABLE, 7, 200);

        assertThat(readPoids(defaut, DOSABLE)).isEqualTo(7);
    }

    @Test
    void settingTheWeightAgainOverwritesTheRowInsteadOfDuplicatingIt() {
        setPoids(defaut, DOSABLE, 7, 200);
        // The primary key is (edition_id, nom): without the ON CONFLICT clause
        // this second call would violate the uniqueness constraint.
        setPoids(defaut, DOSABLE, 12, 200);

        assertThat(readPoids(defaut, DOSABLE)).isEqualTo(12);
    }

    @Test
    void aNullWeightDropsTheOverrideAndRestoresTheDefault() {
        setPoids(defaut, DOSABLE, 12, 200);
        assertThat(readPoids(defaut, DOSABLE)).isEqualTo(12);

        setPoids(defaut, DOSABLE, null, 200);

        assertThat(readPoids(defaut, DOSABLE)).isEqualTo(DEFAULT_WEIGHT);
    }

    @Test
    void aZeroWeightIsRefused() {
        // Zero would switch the rule off in fact while still displaying it as
        // active — and, for a legal rule, without the confirmation that guards
        // it.
        setPoids(defaut, DOSABLE, 0, 400);
        setPoids(defaut, DOSABLE, -3, 400);

        assertThat(readPoids(defaut, DOSABLE)).isEqualTo(DEFAULT_WEIGHT);
    }

    @Test
    void aWeightAboveTheUpperBoundIsRefusedBeforeReachingTheDatabase() {
        setPoids(defaut, DOSABLE, 501, 400);

        // Refused by the application, so the caller gets a business message:
        // the database CHECK is the last net, not the first.
        assertThat(readPoids(defaut, DOSABLE)).isEqualTo(DEFAULT_WEIGHT);
    }

    @Test
    void anUnknownConstraintNameIsRefusedInsteadOfBeingStoredSilently() {
        setPoids(defaut, "contrainteQuiNexistePas", 5, 404);
    }

    @Test
    void twoEditionsDoNotShareTheirDosage() {
        String annee2026 = createEdition("Année 2026");

        setPoids(defaut, DOSABLE, 9, 200);

        // This is the assertion that justifies migration V53: the setting
        // follows the event, not the deployment.
        assertThat(readPoids(defaut, DOSABLE)).isEqualTo(9);
        assertThat(readPoids(annee2026, DOSABLE)).isEqualTo(DEFAULT_WEIGHT);

        setPoids(annee2026, DOSABLE, 3, 200);

        assertThat(readPoids(annee2026, DOSABLE)).isEqualTo(3);
        assertThat(readPoids(defaut, DOSABLE)).isEqualTo(9);
    }

    @Test
    void deletingAnEditionTakesItsWeightsAlong() {
        String premiere = createEdition("Année 2026");
        setPoids(premiere, DOSABLE, 4, 200);

        given().when().delete("/api/editions/" + premiere).then().statusCode(204);
        String recreee = createEdition("Année 2026");

        // The foreign key cascade towards `edition`: an edition recreated under
        // the same name must not inherit the settings of the one it replaces —
        // and, its id being drawn afresh (ADR 0050), it never reuses the old one.
        assertThat(recreee).isNotEqualTo(premiere);
        assertThat(readPoids(recreee, DOSABLE)).isEqualTo(DEFAULT_WEIGHT);
    }

    @Test
    void duplicatingAnEditionCopiesItsDosage() {
        setPoids(defaut, DOSABLE, 8, 200);

        String copie = given().contentType("application/json")
                .body("{\"nom\":\"Copie 2026\"}")
                .when()
                .post("/api/editions/" + defaut + "/dupliquer")
                .then()
                .statusCode(200)
                .extract()
                .path("id");

        // Duplicating is how an event starts from last year's setup: a
        // patiently tuned dosage that failed to follow would be a silent
        // regression — the copy would solve a different problem than its model.
        assertThat(readPoids(copie, DOSABLE)).isEqualTo(8);

        setPoids(copie, DOSABLE, 2, 200);

        assertThat(readPoids(copie, DOSABLE)).isEqualTo(2);
        assertThat(readPoids(defaut, DOSABLE)).isEqualTo(8);
    }

    @Test
    void aWeightCanBeSetOnAProtectedRule() {
        // Protecting a rule means keeping its switch guarded, not its dial:
        // strengthening a legal rule never needs to be prevented.
        setPoids(defaut, PROTEGEE, 50, 200);

        assertThat(readPoids(defaut, PROTEGEE)).isEqualTo(50);
    }
}
