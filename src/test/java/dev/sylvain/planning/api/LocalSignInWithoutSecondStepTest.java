package dev.sylvain.planning.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

import dev.sylvain.planning.OidcTests;
import dev.sylvain.planning.service.compte.Compte;
import dev.sylvain.planning.service.compte.CompteAccesService;
import dev.sylvain.planning.service.compte.CompteService;
import dev.sylvain.planning.service.compte.RoleHabilitation;
import io.quarkus.mailer.MockMailbox;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The operator switched the second step off, and an OIDC provider is plugged
 * in: the password is enough, the local account stays open, the break-glass
 * door of the configuration stays shut (ADR 0094).
 */
@QuarkusTest
@TestProfile(LocalSignInWithoutSecondStepTest.Profil.class)
class LocalSignInWithoutSecondStepTest {

    public static class Profil extends OidcTests.Profil {
        public Profil() {
            super(Map.of("planning.auth.local.seconde-etape", "false"));
        }
    }

    @Inject
    CompteService comptes;

    @Inject
    CompteAccesService acces;

    @Inject
    MockMailbox mailbox;

    @Test
    void sansSecondeEtapeLeMotDePasseSuffitEtAucunCodeNePart() {
        Compte compte = comptes.create("sans-code-" + UUID.randomUUID() + "@example.org", null);
        comptes.grant(compte.id(), RoleHabilitation.ADMIN, null, null);
        acces.sendLink(compte.id(), true);
        Matcher m = Pattern.compile("/mot-de-passe#(\\S+)")
                .matcher(mailbox.getMailsSentTo(compte.email()).get(0).getText());
        assertThat(m.find()).isTrue();
        acces.choose(m.group(1), dev.sylvain.planning.TestPasswords.LONG);
        mailbox.clear();

        String challenge = given().contentType("application/json")
                .body(Map.of("email", compte.email(), "password", dev.sylvain.planning.TestPasswords.LONG))
                .post("/api/auth/connexion")
                .then()
                .statusCode(200)
                .body("codeRequired", equalTo(false))
                .body("maskedEmail", nullValue())
                .extract()
                .path("challenge");
        assertThat(mailbox.getTotalMessagesSent()).isZero();

        Response ok = given().contentType("application/x-www-form-urlencoded")
                .formParam("j_username", compte.email())
                .formParam("j_password", challenge)
                .redirects()
                .follow(false)
                .post("/api/auth/connexion/j_security_check");
        String cookie = ok.getCookie("planning-session");
        assertThat(cookie).isNotBlank();
        given().header("X-Edition-Id", "E1")
                .cookie("planning-session", cookie)
                .get("/api/animateurs")
                .then()
                .statusCode(200);

        // The configuration's account is still shut behind the provider.
        given().contentType("application/x-www-form-urlencoded")
                .formParam("j_username", "admin")
                .formParam("j_password", "admin")
                .redirects()
                .follow(false)
                .post("/j_security_check")
                .then()
                .statusCode(409);
    }
}
