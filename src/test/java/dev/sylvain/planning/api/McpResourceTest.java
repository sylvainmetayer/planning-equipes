package dev.sylvain.planning.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;

/**
 * The MCP page's two server-side needs: knowing whether a key exists, and
 * exchanging the admin password for it.
 *
 * <p>Ordered, and the lock-out case runs last on purpose: the counter is
 * application-scoped state, so five deliberate failures leave the endpoint
 * blocked for five minutes. Nothing else in the suite calls {@code /api/mcp},
 * so the leak is contained — but the order is what keeps the two useful cases
 * from being collateral damage.</p>
 *
 * <p>Values come from the {@code %test} profile: {@code planning.mcp.api-key} is
 * {@code test-mcp-key} and the admin password falls back to {@code admin}.</p>
 */
@QuarkusTest
@TestMethodOrder(OrderAnnotation.class)
class McpResourceTest {

    @Test
    @Order(1)
    void leStatutDitQuUneCleEstConfigureeSansJamaisLaDonner() {
        given()
                .when().get("/api/mcp/statut")
                .then()
                .statusCode(200)
                .body("configuree", is(true))
                .body("header", equalTo("X-MCP-Api-Key"))
                .body("$", org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasKey("cle")));
    }

    @Test
    @Order(2)
    void leBonMotDePasseRevelaLaCle() {
        given()
                .contentType(ContentType.JSON)
                .body("{\"motDePasse\":\"admin\"}")
                .when().post("/api/mcp/cle")
                .then()
                .statusCode(200)
                .body("cle", equalTo("test-mcp-key"));
    }

    @Test
    @Order(3)
    void unMauvaisMotDePasseNeRevelaRien() {
        given()
                .contentType(ContentType.JSON)
                .body("{\"motDePasse\":\"pas-le-bon\"}")
                .when().post("/api/mcp/cle")
                .then()
                .statusCode(401);
    }

    @Test
    @Order(4)
    void unMotDePasseAbsentEstTraiteCommeUnMauvaisMotDePasse() {
        given()
                .contentType(ContentType.JSON)
                .body("{}")
                .when().post("/api/mcp/cle")
                .then()
                .statusCode(401);
    }

    @Test
    @Order(99)
    void lesEssaisRepetesFinissentParBloquerLEndpoint() {
        // Le compteur est global au processus : les deux échecs des tests
        // précédents comptent déjà. On boucle donc jusqu'au blocage plutôt que
        // de miser sur un nombre exact d'essais, en exigeant que tout ce qui
        // précède le 429 soit bien un 401.
        int statut = 401;
        for (int essai = 0; essai < McpResource.MAX_ESSAIS + 1 && statut == 401; essai++) {
            statut = given()
                    .contentType(ContentType.JSON)
                    .body("{\"motDePasse\":\"toujours-faux\"}")
                    .when().post("/api/mcp/cle")
                    .then().extract().statusCode();
        }
        assertThat(statut).isEqualTo(429);

        // Le bon mot de passe ne lève pas le blocage : sinon il suffirait de le
        // deviner une fois pour annuler toute la limitation.
        given()
                .contentType(ContentType.JSON)
                .body("{\"motDePasse\":\"admin\"}")
                .when().post("/api/mcp/cle")
                .then()
                .statusCode(429);
    }
}
