package dev.sylvain.planning.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.service.referentiel.ReferenceDataService;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.util.Map;
import org.junit.jupiter.api.Test;

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
                    "quarkus.http.auth.permission.admin-api.policy",
                    "authenticated",
                    "planning.auth.remote-user.enabled",
                    "true",
                    "planning.auth.remote-user.secret",
                    SECRET,
                    "planning.auth.remote-user.admin-email",
                    EMAIL_ADMIN);
        }
    }

    @Inject
    ReferenceDataService referenceDataService;

    @Test
    void lAdresseAdminAttesteeParLeProxyOuvreLApi() {
        given().header("Remote-Auth-Secret", SECRET)
                .header("Remote-Email", EMAIL_ADMIN)
                .when()
                .get("/api/auth/me")
                .then()
                .statusCode(200)
                .body("authentifie", equalTo(true))
                .body("nom", equalTo("admin"));
    }

    @Test
    void laCasseEtLesEspacesDeLAdresseNeChangentRien() {
        given().header("Remote-Auth-Secret", SECRET)
                .header("Remote-Email", "  " + EMAIL_ADMIN.toUpperCase() + " ")
                .when()
                .get("/api/constraints")
                .then()
                .statusCode(200);
    }

    @Test
    void sansLeSecretLEnTeteNEstQuUneAffirmation() {
        given().header("Remote-Email", EMAIL_ADMIN)
                .when()
                .get("/api/constraints")
                .then()
                .statusCode(401);
    }

    @Test
    void unMauvaisSecretEstRefuse() {
        given().header("Remote-Auth-Secret", "pas-le-bon")
                .header("Remote-Email", EMAIL_ADMIN)
                .when()
                .get("/api/constraints")
                .then()
                .statusCode(401);
    }

    @Test
    void uneAdresseInconnueNObtientPasLeRoleAdmin() {
        given().header("Remote-Auth-Secret", SECRET)
                .header("Remote-Email", "quelquun@exemple.fr")
                .when()
                .get("/api/constraints")
                .then()
                .statusCode(401);
    }

    @Test
    void leFormLoginContinueDeFonctionnerEnParallele() {
        String cookie = given().contentType("application/x-www-form-urlencoded")
                .formParam("j_username", "admin")
                .formParam("j_password", "admin")
                .redirects()
                .follow(false)
                .when()
                .post("/j_security_check")
                .then()
                .extract()
                .cookie("planning-session");

        given().cookie("planning-session", cookie)
                .when()
                .get("/api/auth/me")
                .then()
                .statusCode(200)
                .body("authentifie", equalTo(true));
    }

    /* --------------------------- Espace animateur --------------------------- */

    @Test
    void lAnimateurAttesteParLeProxyEntreDansSonEspaceSansCode() {
        String token = createAnimateur("A-REMOTE-1", EMAIL_ANIMATEUR);

        // With no assertion, the token alone is not enough: the e-mail is the second factor.
        given().when().get("/api/espace-animateur/" + token).then().statusCode(401);

        given().header("Remote-Auth-Secret", SECRET)
                .header("Remote-Email", EMAIL_ANIMATEUR)
                .when()
                .get("/api/espace-animateur/" + token)
                .then()
                .statusCode(200);
    }

    @Test
    void uneAttestationNOuvrePasLEspaceDUnAutreQueSoi() {
        String colleagueToken = createAnimateur("A-REMOTE-2", "collegue@exemple.fr");

        // A colleague's link, picked up from a PDF, plus one's own assertion:
        // the address does not match the record the token names.
        given().header("Remote-Auth-Secret", SECRET)
                .header("Remote-Email", EMAIL_ANIMATEUR)
                .when()
                .get("/api/espace-animateur/" + colleagueToken)
                .then()
                .statusCode(401);
    }

    @Test
    void lAdresseAdminNOuvrePasLEspaceDUnAnimateur() {
        String token = createAnimateur("A-REMOTE-3", "encore@exemple.fr");

        given().header("Remote-Auth-Secret", SECRET)
                .header("Remote-Email", EMAIL_ADMIN)
                .when()
                .get("/api/espace-animateur/" + token)
                .then()
                .statusCode(401);
    }

    /** The label becomes the fiche's nom: its id is drawn by the application (ADR 0050). */
    private String createAnimateur(String label, String email) {
        Animateur animateur = new Animateur(null, "Prénom", label, java.time.LocalDate.of(1990, 1, 1), false);
        animateur.setEmail(email);
        String id = referenceDataService.createAnimateur(animateur).getId();
        return referenceDataService.regenerateAnimateurToken(id);
    }
}
