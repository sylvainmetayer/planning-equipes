package dev.sylvain.planning.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

import dev.sylvain.planning.service.compte.CompteRepository;
import dev.sylvain.planning.service.compte.Habilitation;
import dev.sylvain.planning.service.compte.RoleHabilitation;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Accounts and rights through the API: created, deactivated, granted and withdrawn — never deleted. */
@QuarkusTest
@TestSecurity(user = "admin", roles = "admin")
class CompteResourceTest {

    @Inject
    CompteRepository repository;

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
    void theSameRightInForceCannotBeGrantedTwice() {
        String id = create(unique("double"));
        post(id, "/habilitations", "{\"role\":\"ORGANISATEUR\"}").statusCode(200);
        post(id, "/habilitations", "{\"role\":\"ORGANISATEUR\"}").statusCode(409);
    }

    @Test
    void anExpiredRightNotWithdrawnDoesNotBlockGrantingItAgain() {
        String id = create(unique("expire"));
        // The API refuses a past expiry, so the lapsed right is written as the clock would have left it.
        repository.insertHabilitation(
                id,
                new Habilitation(
                        UUID.randomUUID().toString(),
                        RoleHabilitation.ORGANISATEUR,
                        null,
                        Instant.now().minusSeconds(60),
                        "admin",
                        null,
                        null,
                        null));

        post(id, "/habilitations", "{\"role\":\"ORGANISATEUR\"}")
                .statusCode(200)
                .body("habilitations.size()", equalTo(2))
                .body("habilitations.findAll { it.retireeLe != null }.retireePar", equalTo(java.util.List.of("admin")))
                .body("habilitations.findAll { it.retireeLe == null }.size()", equalTo(1));
    }

    @Test
    void anAdministratorRightNeverExpires() {
        String id = create(unique("admin-expire"));
        post(id, "/habilitations", "{\"role\":\"ADMIN\",\"expireLe\":\"2099-01-01T00:00:00Z\"}")
                .statusCode(400);
    }

    @Test
    void theLastActiveAdministratorCanNeitherBeDeactivatedNorWithdrawn() {
        String id = create(unique("dernier-admin"));
        String droit = post(id, "/habilitations", "{\"role\":\"ADMIN\"}")
                .statusCode(200)
                .extract()
                .path("habilitations[0].id");
        // Accounts are never deleted: make this one the only active administrator.
        java.util.List<String> autres = given().header("X-Edition-Id", "E1")
                .get("/api/comptes")
                .then()
                .extract()
                .path("findAll { it.desactiveLe == null && it.id != '" + id + "' }.id");
        autres.forEach(autre -> post(autre, "/desactivation", null).statusCode(200));

        post(id, "/desactivation", null).statusCode(409).body("message", containsString("administrateur"));
        given().header("X-Edition-Id", "E1")
                .delete("/api/comptes/" + id + "/habilitations/" + droit)
                .then()
                .statusCode(409);
        // A second administrator frees the first.
        String relais = create(unique("relais"));
        post(relais, "/habilitations", "{\"role\":\"ADMIN\"}").statusCode(200);
        post(id, "/desactivation", null).statusCode(200);
    }

    @Test
    void anAddressOrANameTooLongIsRefusedWith400() {
        String longue = "a".repeat(320) + "@example.org";
        given().header("X-Edition-Id", "E1")
                .contentType(ContentType.JSON)
                .body("{\"email\":\"" + longue + "\"}")
                .post("/api/comptes")
                .then()
                .statusCode(400);
        given().header("X-Edition-Id", "E1")
                .contentType(ContentType.JSON)
                .body("{\"email\":\"" + unique("nom-long") + "\",\"nom\":\"" + "n".repeat(256) + "\"}")
                .post("/api/comptes")
                .then()
                .statusCode(400);
    }

    @Test
    void deletingAnEditionWithdrawsItsRights() {
        String edition = given().header("X-Edition-Id", "E1")
                .contentType(ContentType.JSON)
                .body("{\"nom\":\"Édition à supprimer\"}")
                .post("/api/editions")
                .then()
                .statusCode(200)
                .extract()
                .path("id");
        String id = create(unique("edition-supprimee"));
        post(id, "/habilitations", "{\"role\":\"ORGANISATEUR\",\"editionId\":\"" + edition + "\"}")
                .statusCode(200);
        given().header("X-Edition-Id", "E1")
                .delete("/api/editions/" + edition)
                .then()
                .statusCode(204);
        given().header("X-Edition-Id", "E1")
                .get("/api/comptes")
                .then()
                .body("find { it.id == '" + id + "' }.habilitations[0].retireePar", equalTo("systeme"));
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
