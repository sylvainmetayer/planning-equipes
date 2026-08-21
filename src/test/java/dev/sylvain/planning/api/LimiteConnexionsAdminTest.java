package dev.sylvain.planning.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

import java.util.Map;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;

/**
 * Verrouillage du form login admin ({@link LimiteurConnexionsAdmin}). Le
 * compte est unique et sans second facteur : sans ce verrou, une seule paire
 * d'identifiants s'attaque au rythme du réseau.
 *
 * <p>Le profil abaisse le plafond à deux échecs. Le compteur étant tenu par
 * adresse, chaque test annonce la sienne via {@code X-Forwarded-For} plutôt
 * que de partager le {@code 127.0.0.1} de tous les autres — c'est aussi ce que
 * fait un vrai déploiement derrière un reverse proxy.</p>
 */
@QuarkusTest
@TestProfile(LimiteConnexionsAdminTest.Profil.class)
class LimiteConnexionsAdminTest {

    public static class Profil implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("planning.auth.connexion.max-echecs", "2",
                    "planning.auth.connexion.duree-blocage", "PT15M");
        }
    }

    /** Le défaut de développement d'{@code ADMIN_PASSWORD} — ce n'est pas un secret. */
    private static final String MOT_DE_PASSE_DEV = "admin";

    @Test
    void auDelaDeDeuxEchecsLAdresseEstVerrouillee() {
        String adresse = "203.0.113.10";
        connexion(adresse, "mauvais").then().statusCode(anyOf(is(401), is(302)));
        connexion(adresse, "mauvais").then().statusCode(anyOf(is(401), is(302)));

        // Le verrou tient même contre le bon mot de passe : c'est ce qui fait
        // qu'une attaque en ligne ne se contente pas d'attendre son tour.
        connexion(adresse, MOT_DE_PASSE_DEV).then()
                .statusCode(429)
                .header("Retry-After", notNullValue())
                .body("message", containsString("Trop de tentatives"));

        // Et il ne déborde pas sur les autres visiteurs.
        String cookie = connexion("203.0.113.11", MOT_DE_PASSE_DEV).then()
                .statusCode(anyOf(is(302), is(200)))
                .extract().cookie("planning-session");
        assertThat(cookie).isNotBlank();
    }

    /**
     * Une connexion réussie efface le compteur : sinon deux fautes de frappe
     * espacées d'une semaine finiraient par verrouiller l'administrateur.
     */
    @Test
    void uneConnexionReussieEffaceLesEchecsPrecedents() {
        String adresse = "203.0.113.20";
        connexion(adresse, "mauvais").then().statusCode(anyOf(is(401), is(302)));
        connexion(adresse, MOT_DE_PASSE_DEV).then().statusCode(anyOf(is(302), is(200)));

        connexion(adresse, "mauvais").then().statusCode(anyOf(is(401), is(302)));
        // Sans l'effacement, ce deuxième échec serait le deuxième d'une série
        // et la tentative suivante répondrait 429.
        String cookie = connexion(adresse, MOT_DE_PASSE_DEV).then()
                .statusCode(anyOf(is(302), is(200)))
                .extract().cookie("planning-session");
        assertThat(cookie).isNotBlank();
    }

    private static Response connexion(String adresse, String motDePasse) {
        return given()
                .contentType("application/x-www-form-urlencoded")
                .header("X-Forwarded-For", adresse)
                .formParam("j_username", "admin")
                .formParam("j_password", motDePasse)
                .redirects().follow(false)
                .when().post("/j_security_check");
    }
}
