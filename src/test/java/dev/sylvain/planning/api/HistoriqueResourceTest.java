package dev.sylvain.planning.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * The history end to end (issue #406): an action leaves a line, a line says
 * which fields moved, and nothing nominative reaches the table.
 */
@QuarkusTest
class HistoriqueResourceTest {

    @Test
    void aWriteLeavesALineNamingWhatItTouched() {
        given().contentType(ContentType.JSON)
                .body("""
                        {"id":"HIST-A1","prenom":"Alice","nom":"Martin","dateNaissance":"1990-01-01"}""")
                .when()
                .post("/api/animateurs")
                .then()
                .statusCode(200);

        given().contentType(ContentType.JSON)
                .body("""
                        {"id":"HIST-A1","prenom":"Alice","nom":"Durand","dateNaissance":"1990-01-01",
                         "email":"alice@example.org"}""")
                .when()
                .put("/api/animateurs/HIST-A1")
                .then()
                .statusCode(200);

        given().when()
                .get("/api/historique")
                .then()
                .statusCode(200)
                // The creation and the edit, newest first.
                .body("find { it.action == 'ANIMATEUR_MODIFIE' }.entiteId", equalTo("HIST-A1"))
                .body("find { it.action == 'ANIMATEUR_MODIFIE' }.libelle", equalTo("Fiche animateur modifiée"))
                .body("find { it.action == 'ANIMATEUR_MODIFIE' }.resultat", equalTo("SUCCES"))
                .body("find { it.action == 'ANIMATEUR_MODIFIE' }.champs", hasItem("nom"))
                .body("find { it.action == 'ANIMATEUR_MODIFIE' }.champs", hasItem("email"))
                // Untouched fields stay out: the screen re-sends the whole fiche.
                .body("find { it.action == 'ANIMATEUR_MODIFIE' }.champs", not(hasItem("prenom")))
                .body("find { it.action == 'ANIMATEUR_CREE' }.entiteId", equalTo("HIST-A1"));

        given().when().delete("/api/animateurs/HIST-A1").then().statusCode(204);
    }

    /**
     * The rule the whole table is built on: a line carries identifiers, and the
     * identity is joined when it is read. So a fiche deleted since leaves a
     * line that still says what happened and no longer says to whom.
     */
    @Test
    void nothingNominativeIsStoredAndTheNameIsJoinedOnRead() {
        given().contentType(ContentType.JSON)
                .body("""
                        {"id":"HIST-A2","prenom":"Bérénice","nom":"Dupont","dateNaissance":"1990-01-01"}""")
                .when()
                .post("/api/animateurs")
                .then()
                .statusCode(200);

        given().when()
                .get("/api/historique")
                .then()
                .statusCode(200)
                .body("find { it.entiteId == 'HIST-A2' }.entiteNom", equalTo("Bérénice Dupont"));

        given().when().delete("/api/animateurs/HIST-A2").then().statusCode(204);

        // The line survives; the name does not.
        given().when()
                .get("/api/historique")
                .then()
                .statusCode(200)
                .body(
                        "find { it.action == 'ANIMATEUR_SUPPRIME' && it.entiteId == 'HIST-A2' }.entiteNom",
                        equalTo(null));
    }

    /** A refused action is a fact worth keeping — often the one being looked for. */
    @Test
    void aRefusedActionIsRecordedAsRefused() {
        given().contentType(ContentType.JSON)
                .body("""
                        {"id":"HIST-INCONNU","prenom":"X","nom":"Y","dateNaissance":"1990-01-01"}""")
                .when()
                .put("/api/animateurs/HIST-INCONNU")
                .then()
                .statusCode(404)
                // The contract of a refusal: a status and one sentence, the one
                // the screen shows. A 404 used to come back empty (#447).
                .body("message", equalTo("Animateur inconnu : HIST-INCONNU"));

        given().when()
                .get("/api/historique")
                .then()
                .statusCode(200)
                .body("find { it.entiteId == 'HIST-INCONNU' }.resultat", equalTo("REFUS"))
                .body("find { it.entiteId == 'HIST-INCONNU' }.statut", equalTo(404));
    }

    /** A read changes nothing and leaves nothing: the history is not an access log. */
    @Test
    void plainReadsLeaveNoTrace() {
        given().when().get("/api/historique").then().statusCode(200);
        given().when().get("/api/animateurs").then().statusCode(200);

        given().when()
                .get("/api/historique")
                .then()
                .statusCode(200)
                .body("findAll { it.action == 'ANIMATEURS_LUS' }", empty());
    }

    /**
     * One route, two actions. The catalogue keys on the method, so without the
     * resource stating the direction the journal would record « Contrainte
     * activée » for a deactivation — asserting the opposite of what happened.
     */
    @Test
    void aToggleRecordsTheDirectionItWentIn() {
        given().contentType(ContentType.JSON)
                .body("{\"actif\":false}")
                .when()
                .put("/api/constraints/equilibrerCharge")
                .then()
                .statusCode(200);

        given().when()
                .get("/api/historique")
                .then()
                .statusCode(200)
                .body("find { it.entiteId == 'equilibrerCharge' }.action", equalTo("CONTRAINTE_DESACTIVEE"))
                .body("find { it.entiteId == 'equilibrerCharge' }.libelle", equalTo("Contrainte désactivée"));

        given().contentType(ContentType.JSON)
                .body("{\"actif\":true}")
                .when()
                .put("/api/constraints/equilibrerCharge")
                .then()
                .statusCode(200);

        given().when()
                .get("/api/historique")
                .then()
                .statusCode(200)
                .body("find { it.entiteId == 'equilibrerCharge' }.action", equalTo("CONTRAINTE_ACTIVEE"));
    }

    /**
     * The summary the solver screen shows under « des données de référence ont
     * été modifiées depuis cette résolution » : how much moved, of what kind,
     * and the last lines — everything before {@code depuis} left out, and so is
     * everything that changes no data.
     */
    @Test
    void theChangesSinceAMomentAreCountedPerFamilyAndDated() {
        String avant = Instant.now().toString();

        given().contentType(ContentType.JSON)
                .body("""
                        {"id":"HIST-C1","prenom":"Chloé","nom":"Bernard","dateNaissance":"1990-01-01"}""")
                .when()
                .post("/api/animateurs")
                .then()
                .statusCode(200);
        // Reading changes nothing, and an export leaves with a copy of the plan
        // without moving it: neither belongs in this summary.
        given().when().get("/api/animateurs").then().statusCode(200);

        given().queryParam("depuis", avant)
                .when()
                .get("/api/historique/changements")
                .then()
                .statusCode(200)
                .body("total", greaterThan(0))
                .body("parEntite.find { it.entite == 'ANIMATEUR' }.nombre", greaterThan(0))
                .body("dernieres.find { it.entiteId == 'HIST-C1' }.libelle", equalTo("Animateur ajouté"))
                .body("dernieres.find { it.entiteId == 'HIST-C1' }.entiteNom", equalTo("Chloé Bernard"))
                .body("dernieres.action", everyItem(not(equalTo("ANIMATEURS_LUS"))));

        // Asked from now on, the same creation is behind us.
        given().queryParam("depuis", Instant.now().toString())
                .when()
                .get("/api/historique/changements")
                .then()
                .statusCode(200)
                .body("total", equalTo(0))
                .body("parEntite", empty())
                .body("dernieres", empty());

        given().when().delete("/api/animateurs/HIST-C1").then().statusCode(204);
    }

    @Test
    void theChangesRefuseAMomentTheyCannotRead() {
        given().when().get("/api/historique/changements").then().statusCode(400);
        given().queryParam("depuis", "hier")
                .when()
                .get("/api/historique/changements")
                .then()
                .statusCode(400);
    }

    @Test
    void theActionInventoryIsServedForTheScreensFilter() {
        given().when()
                .get("/api/historique/actions")
                .then()
                .statusCode(200)
                .body("size()", greaterThan(50))
                .body("libelle", everyItem(not(empty())))
                .body("find { it.code == 'ANIMATEUR_CREE' }.libelle", equalTo("Animateur ajouté"));
    }
}
