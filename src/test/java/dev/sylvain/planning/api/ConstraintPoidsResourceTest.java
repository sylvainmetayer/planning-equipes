package dev.sylvain.planning.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

import java.util.List;
import java.util.Map;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Weighting a constraint, end to end: the HTTP surface, the SQL behind it, and
 * the edition boundary that separates two festivals sharing one deployment.
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

    private static final String HEADER = "X-Edition-Id";
    private static final String DEFAUT = "DEFAUT";

    /** MEDIUM, « Qualité d'organisation » — one of the twelve meant to be dosed. */
    private static final String DOSABLE = "equilibrerCharge";

    /** HARD, « Légal (mineurs) » — protected, and never dosable. */
    private static final String PROTEGEE = "travailDeNuitInterditPourMineur";

    /**
     * Weights are an edition-scoped override, so a test that sets one leaves a
     * row behind in the default edition that the next test would read. Dropping
     * the override (a {@code null} weight) is the documented way back to the
     * configured default, so the cleanup exercises that path on every run.
     */
    @AfterEach
    void rendreLesPoidsEtLesEditionsCommeTrouves() {
        setPoids(DEFAUT, DOSABLE, null, 200);
        setPoids(DEFAUT, PROTEGEE, null, 200);
        for (Map<String, Object> edition : listEditions()) {
            String id = (String) edition.get("id");
            if (!DEFAUT.equals(id)) {
                given().when().delete("/api/editions/" + id);
            }
        }
    }

    private List<Map<String, Object>> listEditions() {
        return given().when().get("/api/editions").then().statusCode(200).extract().jsonPath().getList("$");
    }

    private void createEdition(String id, String nom) {
        given().contentType("application/json")
                .body("{\"id\":\"" + id + "\",\"nom\":\"" + nom + "\"}")
                .when().post("/api/editions")
                .then().statusCode(200);
    }

    /** @param poids {@code null} sends a JSON {@code null}, which drops the override. */
    private void setPoids(String editionId, String nom, Integer poids, int expectedStatus) {
        given().header(HEADER, editionId)
                .contentType("application/json")
                .body("{\"poids\":" + (poids == null ? "null" : poids) + "}")
                .when().put("/api/constraints/" + nom + "/poids")
                .then().statusCode(expectedStatus);
    }

    private int readPoids(String editionId, String nom) {
        return given().header(HEADER, editionId)
                .when().get("/api/constraints")
                .then().statusCode(200)
                .extract().jsonPath()
                .getInt("contraintes.find { it.name == '" + nom + "' }.poids");
    }

    @Test
    void leCatalogueExposeLePoidsEtCeQueChaqueRegleAutorise() {
        given().header(HEADER, DEFAUT)
                .when().get("/api/constraints")
                .then().statusCode(200)
                // A deployment that tuned nothing weighs everything at 1.
                .body("contraintes.find { it.name == '" + DOSABLE + "' }.poids", equalTo(1))
                .body("contraintes.find { it.name == '" + DOSABLE + "' }.dosable", equalTo(true))
                .body("contraintes.find { it.name == '" + DOSABLE + "' }.protegee", equalTo(false))
                // A rule of public order is protected, and never dosed: the UI
                // turns that into a guarded switch, not a dial.
                .body("contraintes.find { it.name == '" + PROTEGEE + "' }.protegee", equalTo(true))
                .body("contraintes.find { it.name == '" + PROTEGEE + "' }.dosable", equalTo(false));
    }

    @Test
    void unPoidsPosePersisteEtSeRelitDansLeCatalogue() {
        setPoids(DEFAUT, DOSABLE, 7, 200);

        assertThat(readPoids(DEFAUT, DOSABLE)).isEqualTo(7);
    }

    @Test
    void reposerLeMemePoidsEcraseLaLigneAuLieuDeLaDupliquer() {
        setPoids(DEFAUT, DOSABLE, 7, 200);
        // The primary key is (edition_id, nom): without the ON CONFLICT clause
        // this second call would violate the uniqueness constraint.
        setPoids(DEFAUT, DOSABLE, 12, 200);

        assertThat(readPoids(DEFAUT, DOSABLE)).isEqualTo(12);
    }

    @Test
    void unPoidsNulRetireLaSurchargeEtRendLaValeurParDefaut() {
        setPoids(DEFAUT, DOSABLE, 12, 200);
        assertThat(readPoids(DEFAUT, DOSABLE)).isEqualTo(12);

        setPoids(DEFAUT, DOSABLE, null, 200);

        assertThat(readPoids(DEFAUT, DOSABLE)).isEqualTo(1);
    }

    @Test
    void unPoidsZeroEstRefuse() {
        // Zero would switch the rule off in fact while still displaying it as
        // active — and, for a legal rule, without the confirmation that guards
        // it.
        setPoids(DEFAUT, DOSABLE, 0, 400);
        setPoids(DEFAUT, DOSABLE, -3, 400);

        assertThat(readPoids(DEFAUT, DOSABLE)).isEqualTo(1);
    }

    @Test
    void unPoidsHorsBorneHauteEstRefuseAvantDatteindreLaBase() {
        setPoids(DEFAUT, DOSABLE, 101, 400);

        // Refused by the application, so the caller gets a business message:
        // the database CHECK is the last net, not the first.
        assertThat(readPoids(DEFAUT, DOSABLE)).isEqualTo(1);
    }

    @Test
    void unNomDeContrainteInconnuEstRefuseAuLieuDetreStockeEnSilence() {
        setPoids(DEFAUT, "contrainteQuiNexistePas", 5, 404);
    }

    @Test
    void deuxEditionsNePartagentPasLeurDosage() {
        createEdition("ANNEE-2026", "Année 2026");

        setPoids(DEFAUT, DOSABLE, 9, 200);

        // This is the assertion that justifies migration V53: the setting
        // follows the festival, not the deployment.
        assertThat(readPoids(DEFAUT, DOSABLE)).isEqualTo(9);
        assertThat(readPoids("ANNEE-2026", DOSABLE)).isEqualTo(1);

        setPoids("ANNEE-2026", DOSABLE, 3, 200);

        assertThat(readPoids("ANNEE-2026", DOSABLE)).isEqualTo(3);
        assertThat(readPoids(DEFAUT, DOSABLE)).isEqualTo(9);
    }

    @Test
    void supprimerUneEditionEmporteSesPoids() {
        createEdition("ANNEE-2026", "Année 2026");
        setPoids("ANNEE-2026", DOSABLE, 4, 200);

        given().when().delete("/api/editions/ANNEE-2026").then().statusCode(204);
        createEdition("ANNEE-2026", "Année 2026");

        // The foreign key cascade towards `edition`: an edition recreated under
        // the same identifier must not inherit the settings of the one it
        // replaces.
        assertThat(readPoids("ANNEE-2026", DOSABLE)).isEqualTo(1);
    }

    @Test
    void dupliquerUneEditionRecopieSonDosage() {
        setPoids(DEFAUT, DOSABLE, 8, 200);

        given().contentType("application/json")
                .body("{\"id\":\"COPIE-2026\",\"nom\":\"Copie 2026\"}")
                .when().post("/api/editions/" + DEFAUT + "/dupliquer")
                .then().statusCode(200);

        // Duplicating is how a festival starts from last year's setup: a
        // patiently tuned dosage that failed to follow would be a silent
        // regression — the copy would solve a different problem than its model.
        assertThat(readPoids("COPIE-2026", DOSABLE)).isEqualTo(8);

        setPoids("COPIE-2026", DOSABLE, 2, 200);

        assertThat(readPoids("COPIE-2026", DOSABLE)).isEqualTo(2);
        assertThat(readPoids(DEFAUT, DOSABLE)).isEqualTo(8);
    }

    @Test
    void unPoidsPeutSePoserSurUneRegleProtegee() {
        // Protecting a rule means keeping its switch guarded, not its dial:
        // strengthening a legal rule never needs to be prevented.
        setPoids(DEFAUT, PROTEGEE, 50, 200);

        assertThat(readPoids(DEFAUT, PROTEGEE)).isEqualTo(50);
    }
}
