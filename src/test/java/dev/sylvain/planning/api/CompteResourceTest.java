package dev.sylvain.planning.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Accounts and rights through the API: created, deactivated, granted and withdrawn — never deleted. */
@QuarkusTest
class CompteResourceTest {

    @Test
    void anAccountIsCreatedDeactivatedAndReactivated() {
        String id = create(unique("cycle"));

        post(id, "/desactivation", null).statusCode(200).body("desactiveLe", notNullValue());
        post(id, "/reactivation", null).statusCode(200).body("desactiveLe", nullValue());
        given().header("X-Edition-Id", "E1")
                .get("/api/comptes")
                .then()
                .statusCode(200)
                .body("id", hasItem(id));
    }

    @Test
    void anAddressServesOneAccountWhateverItsCase() {
        String email = unique("unique");
        create(email);
        given().header("X-Edition-Id", "E1")
                .contentType(ContentType.JSON)
                .body("{\"email\":\"" + email.toUpperCase(Locale.ROOT) + "\"}")
                .post("/api/comptes")
                .then()
                .statusCode(409);
        given().header("X-Edition-Id", "E1")
                .contentType(ContentType.JSON)
                .body("{\"email\":\"pas-une-adresse\"}")
                .post("/api/comptes")
                .then()
                .statusCode(400);
    }

    @Test
    void aRightIsGrantedThenWithdrawnWithoutDisappearing() {
        String id = create(unique("droit"));
        String droit = post(id, "/habilitations", "{\"role\":\"ORGANISATEUR\"}")
                .statusCode(200)
                .body("habilitations[0].role", equalTo("ORGANISATEUR"))
                .body("habilitations[0].creePar", equalTo("admin"))
                .extract()
                .path("habilitations[0].id");

        given().header("X-Edition-Id", "E1")
                .delete("/api/comptes/" + id + "/habilitations/" + droit)
                .then()
                .statusCode(200)
                .body("habilitations[0].retireeLe", notNullValue());
        given().header("X-Edition-Id", "E1")
                .delete("/api/comptes/" + id + "/habilitations/" + droit)
                .then()
                .statusCode(404);
    }

    @Test
    void anAdministratorIsNeverScopedToAnEditionAndAnExpiryIsInTheFuture() {
        String id = create(unique("refus"));
        String edition = given().header("X-Edition-Id", "E1")
                .get("/api/editions/courant")
                .then()
                .extract()
                .path("id");
        post(id, "/habilitations", "{\"role\":\"ADMIN\",\"editionId\":\"" + edition + "\"}")
                .statusCode(400);
        post(id, "/habilitations", "{\"role\":\"ORGANISATEUR\",\"editionId\":\"inconnue\"}")
                .statusCode(400);
        post(id, "/habilitations", "{\"role\":\"ORGANISATEUR\",\"expireLe\":\"2000-01-01T00:00:00Z\"}")
                .statusCode(400);
        post(id, "/habilitations", "{\"role\":\"ORGANISATEUR\",\"editionId\":\"" + edition + "\"}")
                .statusCode(200);
    }

    @Test
    void anUnknownAccountAnswers404() {
        post("inconnu", "/desactivation", null).statusCode(404);
    }

    private static io.restassured.response.ValidatableResponse post(String id, String suffix, String body) {
        var request = given().header("X-Edition-Id", "E1");
        if (body != null) {
            request = request.contentType(ContentType.JSON).body(body);
        }
        return request.post("/api/comptes/" + id + suffix).then();
    }

    /** A fresh address per call: accounts are never deleted, and the suite may run twice on one base. */
    private static String unique(String prefix) {
        return prefix + "-" + UUID.randomUUID() + "@example.org";
    }

    private static String create(String email) {
        return given().header("X-Edition-Id", "E1")
                .contentType(ContentType.JSON)
                .body("{\"email\":\"" + email + "\",\"nom\":\"Test\"}")
                .post("/api/comptes")
                .then()
                .statusCode(200)
                .extract()
                .path("id");
    }
}
