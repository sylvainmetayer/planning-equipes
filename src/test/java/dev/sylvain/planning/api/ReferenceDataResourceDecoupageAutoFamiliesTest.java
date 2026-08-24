package dev.sylvain.planning.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Locks the <b>ordering</b> guarantee a scenario relies on when it pins its own
 * {@code parametresDecoupage} next to a {@code decoupageAuto} section: the
 * importReferenceData must apply the parameters <i>before</i> auto-slicing, never after.
 *
 * <p>Applied afterwards, the vacations would be generated with whatever
 * happened to be configured on the server, the scenario's own settings taking
 * effect only on the next manual regeneration — a silent discrepancy, since
 * both the parameters and the timeslot group would look correct on screen.
 *
 * <p>This matters beyond tidiness: a dense scenario is only feasible with a
 * staggered relay grid (see the coverage-deficit investigation (kept out of the public repository: it is based on a real festival dataset)), so importing
 * one and immediately solving it would fail for a reason invisible in the
 * data. The check here is that the generated vacations actually carry several
 * {@code famille} values, which can only happen if
 * {@code nombreFamillesDecalage} was already in force when they were built.
 */
@QuarkusTest
class ReferenceDataResourceDecoupageAutoFamiliesTest {

    /**
     * {@code parametresDecoupage} is a single, global row: importing a scenario
     * that pins it leaks into every other {@code @QuarkusTest} sharing this
     * application instance. Restoring the domain defaults afterwards keeps the
     * neighbouring tests — which legitimately assume them, e.g.
     * {@link ReferenceDataResourceDecoupageAutoTest} expecting exactly five
     * vacations — independent of execution order.
     */
    private static final String PARAMETRES_PAR_DEFAUT = """
            {"dureeChevauchementMinutes":30,"dureeDecalageMaxMinutes":0,"dureePauseRepasMinutes":45,
             "dureeVacationCibleMinutes":300,"dureeVacationMaxMinutes":360,"dureeVacationMinMinutes":180,
             "fenetreRepasMidiDebut":"12:00:00","fenetreRepasMidiFin":"14:00:00",
             "fenetreRepasSoirDebut":"19:00:00","fenetreRepasSoirFin":"21:00:00",
             "nombreFamillesDecalage":1,"strategieCouverturePendantPause":"FERMETURE"}
            """;

    @AfterEach
    void restaurerLesParametresParDefaut() {
        given().contentType("application/json").body(PARAMETRES_PAR_DEFAUT)
                .when().put("/api/parametres-decoupage")
                .then().statusCode(200);
    }

    @Test
    void lesParametresDuScenarioSontAppliquesAvantLeDecoupageAutomatique() {
        given().when().post("/api/planning/reset").then().statusCode(200);

        // A starting state deliberately at odds with the scenario: a single
        // family. If the import applied the slicing before the parameters, every
        // shift would be born in family 0 and the test would fail.
        given().contentType("application/json").body(PARAMETRES_PAR_DEFAUT)
                .when().put("/api/parametres-decoupage")
                .then().statusCode(200);

        given()
                .when().post("/api/reference-data/import-scenario?name=scenario-decoupage-auto-familles.yaml")
                .then()
                .statusCode(200)
                .body("decoupageAuto", equalTo(true));

        given()
                .when().get("/api/parametres-decoupage")
                .then()
                .statusCode(200)
                .body("nombreFamillesDecalage", equalTo(3))
                .body("dureeDecalageMaxMinutes", equalTo(60))
                .body("dureeChevauchementMinutes", equalTo(15));

        List<Map<String, Object>> creneaux = given()
                .when().get("/api/creneaux")
                .then()
                .statusCode(200)
                .extract().jsonPath().getList("$");

        assertThat(creneaux).as("l'édition doit porter les vacations générées").isNotEmpty();

        Set<Object> families = creneaux.stream()
                .map(vacation -> vacation.get("famille"))
                .collect(Collectors.toSet());

        assertThat(families)
                .as("les vacations doivent couvrir les 3 familles du scénario, "
                        + "preuve que parametresDecoupage était appliqué avant le découpage")
                .containsExactlyInAnyOrder(0, 1, 2);
    }
}
