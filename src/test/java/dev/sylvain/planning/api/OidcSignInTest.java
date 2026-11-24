package dev.sylvain.planning.api;

import static dev.sylvain.planning.OidcTests.token;
import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;

import dev.sylvain.planning.OidcTests;
import dev.sylvain.planning.domain.Edition;
import dev.sylvain.planning.service.compte.Compte;
import dev.sylvain.planning.service.compte.CompteService;
import dev.sylvain.planning.service.compte.RoleHabilitation;
import dev.sylvain.planning.service.edition.EditionService;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * An OIDC provider signs people in (ADR 0093): the account of the application,
 * not the provider's token, decides who may administer. Unknown or deactivated: no way
 * into {@code /api}; an organiser works in the editions its rights name, and
 * never settles what belongs to the whole instance.
 */
@QuarkusTest
@TestProfile(OidcTests.Profil.class)
class OidcSignInTest {

    @Inject
    CompteService comptes;

    @Inject
    EditionService editions;

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

    private String organiser(String editionId) {
        String email = address();
        Compte compte = comptes.create(email, null);
        comptes.grant(compte.id(), RoleHabilitation.ORGANISATEUR, editionId, null);
        return token("s-" + UUID.randomUUID(), email, true);
    }

    private static io.restassured.response.ValidatableResponse call(String bearer, String edition, String path) {
        return given().header("X-Edition-Id", edition)
                .header("Authorization", bearer)
                .get(path)
                .then();
    }

    @Test
    void unOrganisateurTravailleDansSonEditionEtSeulementDansSonEdition() {
        String autre = editions.create(new Edition(null, "Autre", false, null)).getId();
        try {
            String bearer = organiser("E1");
            call(bearer, "E1", "/api/animateurs").statusCode(200);
            // Out of its rights, an edition is exactly as unknown as one that does not exist.
            call(bearer, autre, "/api/animateurs").statusCode(400).body("code", equalTo("EDITION_INCONNUE"));
            call(bearer, "inexistante", "/api/animateurs").statusCode(400).body("code", equalTo("EDITION_INCONNUE"));
            call(bearer, "E1", "/api/editions")
                    .statusCode(200)
                    .body("id", hasItem("E1"))
                    .body("id", org.hamcrest.Matchers.not(hasItem(autre)));
        } finally {
            editions.delete(autre);
        }
    }

    @Test
    void unDroitSurToutesLesEditionsLesCouvreToutes() {
        String autre = editions.create(new Edition(null, "Autre", false, null)).getId();
        try {
            String bearer = organiser(null);
            call(bearer, autre, "/api/animateurs").statusCode(200);
            call(bearer, "E1", "/api/editions").statusCode(200).body("id", hasItem(autre));
        } finally {
            editions.delete(autre);
        }
    }

    @Test
    void unOrganisateurNeTouchePasAuxReglagesDInstance() {
        String bearer = organiser(null);
        call(bearer, "E1", "/api/comptes").statusCode(403);
        call(bearer, "E1", "/api/webhooks").statusCode(403);
        call(bearer, "E1", "/api/backups").statusCode(403);
        call(bearer, "E1", "/api/historique/connexions").statusCode(403);
        given().header("X-Edition-Id", "E1")
                .header("Authorization", bearer)
                .contentType("application/json")
                .body("{\"nom\":\"Nouvelle\"}")
                .post("/api/editions")
                .then()
                .statusCode(403);
    }

    @Test
    void unOrganisateurImporteDansSonEditionCouranteSeulement() {
        String bearer = organiser(null);
        for (String yaml : new String[] {"edition:\n  nom: Ailleurs\n", "edition:\n  id: E1\n  nom: Ailleurs\n"}) {
            // E1 by id is its own edition: only the unknown name below must be refused,
            // and both forms are previews, which write nothing.
            given().header("X-Edition-Id", "E1")
                    .header("Authorization", bearer)
                    .contentType("application/x-yaml; charset=UTF-8")
                    .body(yaml.getBytes(java.nio.charset.StandardCharsets.UTF_8))
                    .post("/api/reference-data/cible-scenario-fichier")
                    .then()
                    .statusCode(yaml.contains("id: E1") ? 200 : 400);
        }
        given().header("X-Edition-Id", "E1")
                .header("Authorization", bearer)
                .contentType("application/x-yaml; charset=UTF-8")
                .body("edition:\n  nom: Ailleurs\n".getBytes(java.nio.charset.StandardCharsets.UTF_8))
                .post("/api/reference-data/import-scenario-fichier")
                .then()
                .statusCode(400);
    }

    @Test
    void unAdministrateurVoitToutesLesEditions() {
        String email = address();
        Compte compte = comptes.create(email, null);
        comptes.grant(compte.id(), RoleHabilitation.ADMIN, null, null);
        String autre = editions.create(new Edition(null, "Autre", false, null)).getId();
        try {
            String bearer = token("s-" + UUID.randomUUID(), email, true);
            call(bearer, autre, "/api/animateurs").statusCode(200);
            call(bearer, "E1", "/api/comptes").statusCode(200);
        } finally {
            editions.delete(autre);
        }
    }

    @Test
    void laSessionPorteLeRoleOrganisateur() {
        given().header("Authorization", organiser("E1"))
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

    /** What the provider must allow: the code comes back on the login route, exactly. */
    @Test
    void leRedirectUriDuCodeEstLaRouteDeConnexion() {
        String location = given().redirects()
                .follow(false)
                .header("Accept", "text/html")
                .get("/api/auth/oidc/login")
                .then()
                .statusCode(302)
                .extract()
                .header("Location");
        assertThat(java.net.URLDecoder.decode(location, java.nio.charset.StandardCharsets.UTF_8))
                .contains("redirect_uri=http://localhost:")
                .contains("/api/auth/oidc/login");
    }

    @Test
    void uneSessionDeCompteDesactiveNeLaisseAucuneTraceDeConnexion() {
        String email = address();
        Compte compte = comptes.create(email, null);
        Compte autre = comptes.create(address(), null);
        comptes.grant(autre.id(), RoleHabilitation.ADMIN, null, null);
        comptes.grant(compte.id(), RoleHabilitation.ADMIN, null, null);
        comptes.deactivate(compte.id());
        assertThat(api(token("s-" + UUID.randomUUID(), email, true))).isEqualTo(403);
        // Not claimed: still free to be bound by its owner once reactivated.
        comptes.reactivate(compte.id());
        assertThat(api(token("s-" + UUID.randomUUID(), email, true))).isEqualTo(200);
    }
}
