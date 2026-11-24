package dev.sylvain.planning.api;

import static dev.sylvain.planning.OidcTests.token;
import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;

import dev.sylvain.planning.OidcTests;
import dev.sylvain.planning.service.compte.Compte;
import dev.sylvain.planning.service.compte.CompteService;
import dev.sylvain.planning.service.compte.RoleHabilitation;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * An OIDC provider signs people in (ADR 0093): the account of the application,
 * not the provider's token, decides who may administer. Unknown, deactivated,
 * or merely organiser: no way into {@code /api}.
 */
@QuarkusTest
@TestProfile(OidcTests.Profil.class)
class OidcSignInTest {

    @Inject
    CompteService comptes;

    private static String address() {
        return "oidc-" + UUID.randomUUID() + "@example.org";
    }

    private static int api(String bearer) {
        return given().header("X-Edition-Id", "E1")
                .header("Authorization", bearer)
                .get("/api/animateurs")
                .statusCode();
    }

    @Test
    void unCompteInconnuEstRefuseMemeAvecUnRoleAdminDuFournisseur() {
        assertThat(api(token("s-" + UUID.randomUUID(), address(), true, "admin")))
                .isEqualTo(403);
    }

    @Test
    void unAdminDeLApplicationOuvreLApiEtSeLieParSonSujet() {
        String email = address();
        Compte compte = comptes.create(email, null);
        comptes.grant(compte.id(), RoleHabilitation.ADMIN, null, null);
        String sujet = "s-" + UUID.randomUUID();
        assertThat(api(token(sujet, email, true))).isEqualTo(200);
        // Bound to the subject: the same address behind another subject opens nothing.
        assertThat(api(token("autre-" + UUID.randomUUID(), email, true))).isEqualTo(403);
        // And a changed address does not lock the owner out.
        assertThat(api(token(sujet, address(), true))).isEqualTo(200);
    }

    @Test
    void uneAdresseNonVerifieeNeLiePasLeCompte() {
        String email = address();
        Compte compte = comptes.create(email, null);
        comptes.grant(compte.id(), RoleHabilitation.ADMIN, null, null);
        assertThat(api(token("s-" + UUID.randomUUID(), email, false))).isEqualTo(403);
    }

    @Test
    void unCompteDesactiveEstRefuse() {
        String email = address();
        Compte compte = comptes.create(email, null);
        comptes.grant(compte.id(), RoleHabilitation.ADMIN, null, null);
        String bearer = token("s-" + UUID.randomUUID(), email, true);
        assertThat(api(bearer)).isEqualTo(200);
        // Another administrator exists, or the last one could not be deactivated.
        Compte autre = comptes.create(address(), null);
        comptes.grant(autre.id(), RoleHabilitation.ADMIN, null, null);
        comptes.deactivate(compte.id());
        assertThat(api(bearer)).isEqualTo(403);
    }

    @Test
    void unOrganisateurNePasseJamaisLaPolitiqueAdmin() {
        String email = address();
        Compte compte = comptes.create(email, null);
        comptes.grant(compte.id(), RoleHabilitation.ORGANISATEUR, null, null);
        String bearer = token("s-" + UUID.randomUUID(), email, true);
        assertThat(api(bearer)).isEqualTo(403);
        given().header("Authorization", bearer)
                .get("/api/auth/me")
                .then()
                .statusCode(200)
                .body("authentifie", equalTo(true))
                .body("roles", hasItem("organisateur"));
    }

    @Test
    void laDeconnexionNommeLaRouteDuFournisseur() {
        given().header("Authorization", token("s-" + UUID.randomUUID(), address(), true))
                .post("/api/auth/logout")
                .then()
                .statusCode(200)
                .body("urlDeconnexion", equalTo("/api/auth/oidc/logout"));
    }

    @Test
    void laConfigurationAnnonceLeFournisseurSansFormulaire() {
        given().get("/api/config")
                .then()
                .statusCode(200)
                .body("authOidc", equalTo(true))
                .body("authSecours", equalTo(false));
    }
}
