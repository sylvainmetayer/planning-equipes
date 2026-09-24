package dev.sylvain.planning.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;

import dev.sylvain.planning.OidcJetons;
import dev.sylvain.planning.service.compte.Compte;
import dev.sylvain.planning.service.compte.CompteService;
import dev.sylvain.planning.service.compte.RoleHabilitation;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Who opens {@code /api} once Keycloak signs people in (ADR 0049): the realm
 * role {@code admin}, and nobody else. The default {@code %test} profile opens
 * the API so the functional suites need no session; this one restores the real
 * policy.
 */
@QuarkusTest
@TestProfile(RolesKeycloakTest.Profil.class)
class RolesKeycloakTest {

    public static class Profil implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "quarkus.http.auth.permission.admin-api.policy", "role-admin",
                    "quarkus.http.auth.permission.api-docs.policy", "role-admin");
        }
    }

    @Inject
    CompteService comptes;

    @Test
    void leRoleAdminDuRealmOuvreLApi() {
        given().header("Authorization", porteur("roles-admin@example.org", "user", "admin"))
                .when()
                .get("/api/animateurs")
                .then()
                .statusCode(200);
    }

    /**
     * Signed in is not entitled: an animateur's session, or a bare account,
     * meets 403 — not 401, which a frontend would take for "sign in again".
     */
    @Test
    void unAnimateurOuUnCompteOrdinaireNOuvrePasLApi() {
        given().header("Authorization", porteur("roles-animateur@example.org", "user", "animateur"))
                .when()
                .get("/api/animateurs")
                .then()
                .statusCode(403);
        given().header("Authorization", porteur("roles-user@example.org", "user"))
                .when()
                .get("/api/animateurs")
                .then()
                .statusCode(403);
        given().header("Authorization", porteur("roles-user@example.org", "user"))
                .when()
                .get("/q/openapi")
                .then()
                .statusCode(403);
    }

    @Test
    void laSessionDitQuiEtAvecQuelsRoles() {
        given().header("Authorization", porteur("roles-moi@example.org", "user", "admin"))
                .when()
                .get("/api/auth/me")
                .then()
                .statusCode(200)
                .body("authentifie", equalTo(true))
                .body("nom", equalTo("roles-moi@example.org"))
                .body("roles", hasItem("admin"));
        given().header("Authorization", porteur("roles-moi@example.org", "user", "admin"))
                .when()
                .post("/api/auth/logout")
                .then()
                .statusCode(200)
                .body("urlDeconnexion", equalTo("/api/auth/oidc/logout"));
    }

    /**
     * An edition-wide right grants its role — which opens nothing yet: the
     * policies name {@code admin} and {@code mcp} only. Deny by default, until
     * the lot that builds the role's projections opens its routes.
     */
    @Test
    void uneHabilitationRhNOuvreRienTantQueSesRoutesNExistentPas() {
        String email = "roles-rh-" + UUID.randomUUID() + "@example.org";
        Compte compte = comptes.create(email, "RH");
        comptes.grant(compte.id(), RoleHabilitation.RH, null, null, List.of(), "test");

        given().header("Authorization", porteur(email, "user"))
                .when()
                .get("/api/auth/me")
                .then()
                .body("roles", hasItem("rh"));
        given().header("Authorization", porteur(email, "user"))
                .when()
                .get("/api/animateurs")
                .then()
                .statusCode(403);
    }

    @Test
    void uneHabilitationExpireeOuRetireeNOuvrePlusRien() {
        String email = "roles-rh-retire-" + UUID.randomUUID() + "@example.org";
        Compte compte = comptes.create(email, null);
        Compte avec = comptes.grant(
                compte.id(), RoleHabilitation.RH, null, Instant.now().plus(Duration.ofDays(1)), List.of(), "test");
        comptes.withdraw(compte.id(), avec.habilitations().getFirst().id());

        given().header("Authorization", porteur(email, "user"))
                .when()
                .get("/api/auth/me")
                .then()
                .body("roles", not(hasItem("rh")));
        given().when().get("/api/auth/me").then().body("authentifie", equalTo(false));
    }

    /** The kill switch: a deactivated account keeps its name and loses every role, realm roles included. */
    @Test
    void unCompteDesactiveNOuvrePlusLApiMalgreLeRealm() {
        String email = "roles-desactive-" + UUID.randomUUID() + "@example.org";
        Compte compte = comptes.create(email, null);
        comptes.deactivate(compte.id());

        given().header("Authorization", porteur(email, "user", "admin"))
                .when()
                .get("/api/animateurs")
                .then()
                .statusCode(403);
    }

    @Test
    void laConnexionKeycloakRenvoieVersUnCheminDeLApplication() {
        given().header("Authorization", porteur("roles-retour@example.org", "user", "admin"))
                .redirects()
                .follow(false)
                .when()
                .get("/api/auth/oidc/login?redirect=/animateurs")
                .then()
                .statusCode(303)
                .header("Location", org.hamcrest.Matchers.endsWith("/animateurs"));
    }

    /**
     * A Keycloak session has no password to re-type: revealing the MCP key
     * reads the moment of the last sign-in instead, and a session older than
     * five minutes is told to sign in again.
     */
    @Test
    void laCleMcpExigeUneConnexionRecentePlutotQuUnMotDePasse() {
        String email = "roles-mcp-" + UUID.randomUUID() + "@example.org";
        String ancienne =
                OidcJetons.jeton(email, List.of("user", "admin"), Instant.now().minus(Duration.ofHours(2)));
        String recente =
                OidcJetons.jeton(email, List.of("user", "admin"), Instant.now().minusSeconds(30));

        given().header("Authorization", "Bearer " + ancienne)
                .when()
                .get("/api/mcp/statut")
                .then()
                .statusCode(200)
                .body("revelationParReconnexion", equalTo(true));
        given().header("Authorization", "Bearer " + ancienne)
                .contentType("application/json")
                .body("{}")
                .when()
                .post("/api/mcp/cle")
                .then()
                .statusCode(401);
        given().header("Authorization", "Bearer " + recente)
                .contentType("application/json")
                .body("{}")
                .when()
                .post("/api/mcp/cle")
                .then()
                .statusCode(200)
                .body("cle", equalTo("test-mcp-key"));
    }

    /**
     * A browser token whose address the realm never verified cannot be tied to
     * an account, so it could not be switched off either: it keeps no role,
     * realm {@code admin} included.
     */
    @Test
    void unJetonSansAdresseVerifieeNeGardeAucunRole() {
        String email = "roles-non-verifie-" + UUID.randomUUID() + "@example.org";
        String jeton = OidcJetons.jeton(email, List.of("user", "admin"), "planning-app", email, false);
        given().header("Authorization", "Bearer " + jeton)
                .when()
                .get("/api/animateurs")
                .then()
                .statusCode(403);
    }

    /**
     * The realm corrects a person's address: the subject is the same, so the
     * same account follows — no second row, no lockout, and the deactivation
     * switch still bites.
     */
    @Test
    void unChangementDAdresseDansLeRealmSuitLeMemeCompte() {
        String sujet = "sujet-" + UUID.randomUUID();
        String avant = sujet + "-avant@example.org";
        String apres = sujet + "-apres@example.org";
        given().header(
                        "Authorization",
                        "Bearer " + OidcJetons.jeton(sujet, List.of("user", "admin"), "planning-app", avant, true))
                .when()
                .get("/api/animateurs")
                .then()
                .statusCode(200);
        given().header(
                        "Authorization",
                        "Bearer " + OidcJetons.jeton(sujet, List.of("user", "admin"), "planning-app", apres, true))
                .when()
                .get("/api/animateurs")
                .then()
                .statusCode(200);

        List<Compte> siens = comptes.list().stream()
                .filter(compte -> sujet.equals(compte.sujet()))
                .toList();
        org.assertj.core.api.Assertions.assertThat(siens)
                .singleElement()
                .satisfies(compte -> org.assertj.core.api.Assertions.assertThat(compte.email())
                        .isEqualTo(apres));
        comptes.deactivate(siens.getFirst().id());
        given().header(
                        "Authorization",
                        "Bearer " + OidcJetons.jeton(sujet, List.of("user", "admin"), "planning-app", apres, true))
                .when()
                .get("/api/animateurs")
                .then()
                .statusCode(403);
    }

    private static String porteur(String email, String... roles) {
        return "Bearer " + OidcJetons.jeton(email, List.of(roles), "planning-app", email, true);
    }
}
