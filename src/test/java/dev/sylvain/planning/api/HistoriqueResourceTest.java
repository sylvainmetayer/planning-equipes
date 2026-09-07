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
                .when().post("/api/animateurs")
                .then().statusCode(200);

        given().contentType(ContentType.JSON)
                .body("""
                        {"id":"HIST-A1","prenom":"Alice","nom":"Durand","dateNaissance":"1990-01-01",
                         "email":"alice@example.org"}""")
                .when().put("/api/animateurs/HIST-A1")
                .then().statusCode(200);

        given().when().get("/api/historique")
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
                .when().post("/api/animateurs")
                .then().statusCode(200);

        given().when().get("/api/historique")
                .then()
                .statusCode(200)
                .body("find { it.entiteId == 'HIST-A2' }.entiteNom", equalTo("Bérénice Dupont"));

        given().when().delete("/api/animateurs/HIST-A2").then().statusCode(204);

        // The line survives; the name does not.
        given().when().get("/api/historique")
                .then()
                .statusCode(200)
                .body("find { it.action == 'ANIMATEUR_SUPPRIME' && it.entiteId == 'HIST-A2' }.entiteNom",
                        equalTo(null));
    }

    /** A refused action is a fact worth keeping — often the one being looked for. */
    @Test
    void aRefusedActionIsRecordedAsRefused() {
        given().contentType(ContentType.JSON)
                .body("""
                        {"id":"HIST-INCONNU","prenom":"X","nom":"Y","dateNaissance":"1990-01-01"}""")
                .when().put("/api/animateurs/HIST-INCONNU")
                .then().statusCode(404);

        given().when().get("/api/historique")
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

        given().when().get("/api/historique")
                .then()
                .statusCode(200)
                .body("findAll { it.action == 'ANIMATEURS_LUS' }", empty());
    }

    @Test
    void theActionInventoryIsServedForTheScreensFilter() {
        given().when().get("/api/historique/actions")
                .then()
                .statusCode(200)
                .body("size()", greaterThan(50))
                .body("libelle", everyItem(not(empty())))
                .body("find { it.code == 'ANIMATEUR_CREE' }.libelle", equalTo("Animateur ajouté"));
    }
}
