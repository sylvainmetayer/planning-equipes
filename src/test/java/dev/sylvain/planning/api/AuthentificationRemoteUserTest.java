package dev.sylvain.planning.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

import java.util.Map;

import org.junit.jupiter.api.Test;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.service.ReferenceDataService;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;

/**
 * The opt-in remote-user mode: an access proxy authenticates the visitor and
 * asserts their address, the application trusts it — but only alongside the
 * shared secret.
 *
 * <p>The profile restores the real {@code authenticated} policy, otherwise the
 * {@code %test} profile's {@code permit} would make every assertion here pass
 * for the wrong reason.</p>
 */
@QuarkusTest
@TestProfile(AuthentificationRemoteUserTest.Profil.class)
class AuthentificationRemoteUserTest {

    static final String SECRET = "secret-de-test";
    static final String EMAIL_ADMIN = "patronne@exemple.fr";
    static final String EMAIL_ANIMATEUR = "animatrice@exemple.fr";

    public static class Profil implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "quarkus.http.auth.permission.admin-api.policy", "authenticated",
                    "planning.auth.remote-user.enabled", "true",
                    "planning.auth.remote-user.secret", SECRET,
                    "planning.auth.remote-user.admin-email", EMAIL_ADMIN);
        }
    }

    @Inject
    ReferenceDataService referenceDataService;

    @Test
    void lAdresseAdminAttesteeParLeProxyOuvreLApi() {
        given()
                .header("Remote-Auth-Secret", SECRET)
                .header("Remote-Email", EMAIL_ADMIN)
                .when().get("/api/auth/me")
                .then()
                .statusCode(200)
                .body("authentifie", equalTo(true))
                .body("nom", equalTo("admin"));
    }

    @Test
    void laCasseEtLesEspacesDeLAdresseNeChangentRien() {
        given()
                .header("Remote-Auth-Secret", SECRET)
                .header("Remote-Email", "  " + EMAIL_ADMIN.toUpperCase() + " ")
                .when().get("/api/constraints")
                .then()
                .statusCode(200);
    }

    @Test
    void sansLeSecretLEnTeteNEstQuUneAffirmation() {
        given()
                .header("Remote-Email", EMAIL_ADMIN)
                .when().get("/api/constraints")
                .then()
                .statusCode(401);
    }

    @Test
    void unMauvaisSecretEstRefuse() {
        given()
                .header("Remote-Auth-Secret", "pas-le-bon")
                .header("Remote-Email", EMAIL_ADMIN)
                .when().get("/api/constraints")
                .then()
                .statusCode(401);
    }

    @Test
    void uneAdresseInconnueNObtientPasLeRoleAdmin() {
        given()
                .header("Remote-Auth-Secret", SECRET)
                .header("Remote-Email", "quelquun@exemple.fr")
                .when().get("/api/constraints")
                .then()
                .statusCode(401);
    }

    @Test
    void leFormLoginContinueDeFonctionnerEnParallele() {
        String cookie = given()
                .contentType("application/x-www-form-urlencoded")
                .formParam("j_username", "admin")
                .formParam("j_password", "admin")
                .redirects().follow(false)
                .when().post("/j_security_check")
                .then().extract().cookie("planning-session");

        given().cookie("planning-session", cookie)
                .when().get("/api/auth/me")
                .then()
                .statusCode(200)
                .body("authentifie", equalTo(true));
    }

    /* --------------------------- Espace animateur --------------------------- */

    @Test
    void lAnimateurAttesteParLeProxyEntreDansSonEspaceSansCode() {
        String jeton = creerAnimateur("A-REMOTE-1", EMAIL_ANIMATEUR);

        // Sans attestation, le jeton seul ne suffit pas : l'e-mail est le second facteur.
        given().when().get("/api/espace-animateur/" + jeton).then().statusCode(401);

        given()
                .header("Remote-Auth-Secret", SECRET)
                .header("Remote-Email", EMAIL_ANIMATEUR)
                .when().get("/api/espace-animateur/" + jeton)
                .then()
                .statusCode(200);
    }

    @Test
    void uneAttestationNOuvrePasLEspaceDUnAutreQueSoi() {
        String jetonDuCollegue = creerAnimateur("A-REMOTE-2", "collegue@exemple.fr");

        // Le lien d'un collègue, ramassé sur un PDF, plus sa propre attestation :
        // l'adresse ne correspond pas à la fiche que le jeton désigne.
        given()
                .header("Remote-Auth-Secret", SECRET)
                .header("Remote-Email", EMAIL_ANIMATEUR)
                .when().get("/api/espace-animateur/" + jetonDuCollegue)
                .then()
                .statusCode(401);
    }

    @Test
    void lAdresseAdminNOuvrePasLEspaceDUnAnimateur() {
        String jeton = creerAnimateur("A-REMOTE-3", "encore@exemple.fr");

        given()
                .header("Remote-Auth-Secret", SECRET)
                .header("Remote-Email", EMAIL_ADMIN)
                .when().get("/api/espace-animateur/" + jeton)
                .then()
                .statusCode(401);
    }

    private String creerAnimateur(String id, String email) {
        Animateur animateur = new Animateur(id, "Prénom", "Nom", java.time.LocalDate.of(1990, 1, 1), false);
        animateur.setEmail(email);
        referenceDataService.createAnimateur(animateur);
        return referenceDataService.regenererJetonAnimateur(id);
    }
}
