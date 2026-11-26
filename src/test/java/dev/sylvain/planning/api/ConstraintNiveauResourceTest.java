package dev.sylvain.planning.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

import io.quarkus.test.junit.QuarkusTest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The level of an organisation rule, chosen by edition (ADR 0106): stored
 * beside the weight, applied by the next solve, never on a rule the edition
 * does not choose for, and independent of the weight it keeps for the way back.
 */
@QuarkusTest
class ConstraintNiveauResourceTest {

    private static final String HEADER = "X-Edition-Id";

    /** MEDIUM, « Qualité d'organisation » : an organisation rule. */
    private static final String ORGANISATION = "equilibrerCharge";

    /** HARD, « Légal (temps de travail) » : never an edition's choice. */
    private static final String LEGALE = "dureeQuotidienneMax";

    /** HARD, « Affectation » : one of the fixed rules, never an edition's choice either. */
    private static final String BASE = "posteDoitEtrePourvu";

    /** HARD as the catalogue ships it, and choosable. */
    private static final String REPAS = "coupureRepasObligatoire";

    /** The run of days, one rule since 4.0, and the name its hard form had in 3.x (ADR 0118). */
    private static final String JOURS = "maxJoursConsecutifsTravailles";

    private static final String JOURS_FORME_DURE = "maxJoursConsecutifsTravaillesDur";

    private String defaut;

    @BeforeEach
    void resolveDefaultEdition() {
        List<Map<String, Object>> editions = given().header(HEADER, "E1")
                .when()
                .get("/api/editions")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getList("$");
        defaut = editions.stream()
                .filter(edition -> Boolean.TRUE.equals(edition.get("active")))
                .map(edition -> (String) edition.get("id"))
                .findFirst()
                .orElseThrow();
    }

    /** Back to the catalogue's level, and to the default weight: the row goes. */
    @AfterEach
    void restore() {
        setNiveau(ORGANISATION, "PREFERENCE_NORMALE", 200);
        setNiveau(REPAS, "OBLIGATOIRE", 200);
        setNiveau(JOURS, "PREFERENCE_NORMALE", 200);
    }

    private void setNiveau(String nom, String niveau, int expectedStatus) {
        given().header(HEADER, defaut)
                .contentType("application/json")
                .body("{\"niveau\":\"" + niveau + "\"}")
                .when()
                .put("/api/constraints/" + nom + "/niveau")
                .then()
                .statusCode(expectedStatus);
    }

    private String rule(String nom) {
        return "contraintes.find { it.name == '" + nom + "' }";
    }

    @Test
    void theCatalogueSaysWhichRulesHaveALevelAndWhereTheEditionPutThem() {
        given().header(HEADER, defaut)
                .when()
                .get("/api/constraints")
                .then()
                .statusCode(200)
                .body(rule(ORGANISATION) + ".niveauReglable", equalTo(true))
                .body(rule(ORGANISATION) + ".niveauChoisi", equalTo("PREFERENCE_NORMALE"))
                .body(rule(LEGALE) + ".niveauReglable", equalTo(false))
                .body(rule(LEGALE) + ".niveauChoisi", nullValue());
    }

    @Test
    void aRuleMadeObligatoireIsReadBackObligatoireAndItsWeightIsKeptForTheWayBack() {
        given().header(HEADER, defaut)
                .contentType("application/json")
                .body("{\"poids\":25}")
                .when()
                .put("/api/constraints/" + ORGANISATION + "/poids")
                .then()
                .statusCode(200);

        setNiveau(ORGANISATION, "OBLIGATOIRE", 200);

        // `niveau` stays the catalogue's own; the edition's choice is `niveauChoisi`, and the weight
        // applied at the hard level is the hard one.
        given().header(HEADER, defaut)
                .when()
                .get("/api/constraints")
                .then()
                .body(rule(ORGANISATION) + ".niveau", equalTo("MEDIUM"))
                .body(rule(ORGANISATION) + ".niveauChoisi", equalTo("OBLIGATOIRE"))
                .body(rule(ORGANISATION) + ".poids", equalTo(1));

        // Back to a preference, at the position asked: the rule is weighed as it was before.
        setNiveau(ORGANISATION, "PREFERENCE_FORTE", 200);
        given().header(HEADER, defaut)
                .when()
                .get("/api/constraints")
                .then()
                .body(rule(ORGANISATION) + ".niveauChoisi", equalTo("PREFERENCE_FORTE"))
                .body(rule(ORGANISATION) + ".poids", equalTo(25));
    }

    @Test
    void aLegalRuleHasNoLevelToChoose() {
        setNiveau(LEGALE, "PREFERENCE_LEGERE", 400);
        setNiveau(LEGALE, "OBLIGATOIRE", 400);
        setNiveau(BASE, "PREFERENCE_FORTE", 400);
        setNiveau(BASE, "OBLIGATOIRE", 400);
        setNiveau("nomInconnu", "OBLIGATOIRE", 404);
    }

    /**
     * A rule the catalogue ships hard, made a strong preference and then
     * obligatoire again, scores hard 1: the weight of the preference does not
     * come back as a hard weight of 25.
     */
    @Test
    void aShippedHardRuleBroughtBackToObligatoireWeighsOneHardPoint() {
        setNiveau(REPAS, "PREFERENCE_FORTE", 200);
        given().header(HEADER, defaut)
                .when()
                .get("/api/constraints")
                .then()
                .body(rule(REPAS) + ".niveauChoisi", equalTo("PREFERENCE_FORTE"))
                .body(rule(REPAS) + ".poids", equalTo(25));

        setNiveau(REPAS, "OBLIGATOIRE", 200);
        given().header(HEADER, defaut)
                .when()
                .get("/api/constraints")
                .then()
                .body(rule(REPAS) + ".niveauChoisi", equalTo("OBLIGATOIRE"))
                .body(rule(REPAS) + ".poids", equalTo(1));
    }

    @Test
    void anUnknownLevelIsRefused() {
        given().header(HEADER, defaut)
                .contentType("application/json")
                .body("{\"niveau\":\"FACULTATIVE\"}")
                .when()
                .put("/api/constraints/" + ORGANISATION + "/niveau")
                .then()
                .statusCode(400);
    }

    @Test
    void theOldHardRunFormSwitchesTheLevelOfTheMergedRule() {
        toggle(JOURS_FORME_DURE, true);
        given().header(HEADER, defaut)
                .when()
                .get("/api/constraints")
                .then()
                .body(rule(JOURS) + ".niveauChoisi", equalTo("OBLIGATOIRE"))
                .body(rule(JOURS) + ".actif", equalTo(true))
                .body(rule(JOURS_FORME_DURE), nullValue());

        toggle(JOURS_FORME_DURE, false);
        given().header(HEADER, defaut)
                .when()
                .get("/api/constraints")
                .then()
                .body(rule(JOURS) + ".niveauChoisi", equalTo("PREFERENCE_NORMALE"))
                .body(rule(JOURS) + ".actif", equalTo(true));

        // A hard weight under the old name would dose the preference down: refused, never read.
        given().header(HEADER, defaut)
                .contentType("application/json")
                .body("{\"poids\":1}")
                .when()
                .put("/api/constraints/" + JOURS_FORME_DURE + "/poids")
                .then()
                .statusCode(400);
    }

    private void toggle(String nom, boolean actif) {
        given().header(HEADER, defaut)
                .contentType("application/json")
                .body("{\"actif\":" + actif + "}")
                .when()
                .put("/api/constraints/" + nom)
                .then()
                .statusCode(200);
    }
}
