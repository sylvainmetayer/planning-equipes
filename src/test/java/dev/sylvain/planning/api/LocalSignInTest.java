package dev.sylvain.planning.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

import dev.sylvain.planning.service.compte.Compte;
import dev.sylvain.planning.service.compte.CompteAccesService;
import dev.sylvain.planning.service.compte.CompteService;
import dev.sylvain.planning.service.compte.RoleHabilitation;
import io.quarkus.mailer.MockMailbox;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Local accounts (ADR 0094), end to end through the real security policy:
 * a mailed single-use link to choose the password, then password and mailed
 * code exchanged for a session cookie.
 */
@QuarkusTest
@TestProfile(LocalSignInTest.Profil.class)
class LocalSignInTest {

    public static class Profil implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            // The lock counts failures per address and every test comes from the same
            // one: its own test is LocalSignInLockoutTest.
            return Map.of(
                    "quarkus.http.auth.permission.admin-api.policy", "role-gestion",
                    "planning.auth.connexion.max-echecs", "1000");
        }
    }

    private static final String PASSWORD = dev.sylvain.planning.TestPasswords.LONG;
    private static final Pattern LIEN = Pattern.compile("/mot-de-passe#(\\S+)");
    private static final Pattern CODE = Pattern.compile("\\b(\\d{6})\\b");

    @Inject
    CompteService comptes;

    @Inject
    CompteAccesService acces;

    @Inject
    MockMailbox mailbox;

    @Inject
    DataSource dataSource;

    @BeforeEach
    void clearMailbox() {
        mailbox.clear();
    }

    private Compte account(boolean admin) {
        Compte compte = comptes.create("local-" + UUID.randomUUID() + "@example.org", "Marie");
        if (admin) {
            comptes.grant(compte.id(), RoleHabilitation.ADMIN, null, null);
        }
        return compte;
    }

    private String mailText(Compte compte) {
        var mails = mailbox.getMailsSentTo(compte.email());
        assertThat(mails).isNotEmpty();
        return mails.get(mails.size() - 1).getText();
    }

    private String linkToken(Compte compte) {
        Matcher m = LIEN.matcher(mailText(compte));
        assertThat(m.find()).as("a link in the mail").isTrue();
        return m.group(1);
    }

    private Compte withPassword(boolean admin) {
        Compte compte = account(admin);
        acces.sendLink(compte.id(), true);
        acces.choose(linkToken(compte), PASSWORD);
        mailbox.clear();
        return compte;
    }

    private Response passwordStep(String email, String motDePasse) {
        return given().contentType("application/json")
                .body(Map.of("email", email, "password", motDePasse))
                .post("/api/auth/connexion");
    }

    private Response formPost(String email, String secret) {
        return given().contentType("application/x-www-form-urlencoded")
                .formParam("j_username", email)
                .formParam("j_password", secret)
                .redirects()
                .follow(false)
                .post("/api/auth/connexion/j_security_check");
    }

    private static boolean sessionOpened(Response reponse) {
        return reponse.getHeaders().getValues("Set-Cookie").stream()
                .anyMatch(c -> c.startsWith("planning-session=") && !c.startsWith("planning-session=;"));
    }

    @Test
    void lInvitationSeConsommeUneFoisEtLePremierMotDePasseOuvreLaConnexion() {
        Compte compte = account(true);
        acces.sendLink(compte.id(), true);
        String jeton = linkToken(compte);
        assertThat(mailText(compte)).doesNotContain(PASSWORD);

        given().contentType("application/json")
                .body(Map.of("token", jeton, "password", PASSWORD))
                .post("/api/auth/mot-de-passe")
                .then()
                .statusCode(204);
        // Used: the second time is refused.
        given().contentType("application/json")
                .body(Map.of("token", jeton, "password", PASSWORD + " bis"))
                .post("/api/auth/mot-de-passe")
                .then()
                .statusCode(400);
        assertThat(comptes.list().stream()
                        .filter(c -> c.id().equals(compte.id()))
                        .findFirst()
                        .orElseThrow()
                        .passwordSet())
                .isTrue();
        passwordStep(compte.email(), PASSWORD).then().statusCode(200);
    }

    @Test
    void unMotDePasseTropCourtNeBruleParLeLien() {
        Compte compte = account(false);
        acces.sendLink(compte.id(), true);
        String jeton = linkToken(compte);
        given().contentType("application/json")
                .body(Map.of("token", jeton, "password", "court"))
                .post("/api/auth/mot-de-passe")
                .then()
                .statusCode(400);
        given().contentType("application/json")
                .body(Map.of("token", jeton, "password", PASSWORD))
                .post("/api/auth/mot-de-passe")
                .then()
                .statusCode(204);
    }

    @Test
    void unLienExpireEstRefuse() throws Exception {
        Compte compte = account(false);
        acces.sendLink(compte.id(), true);
        String jeton = linkToken(compte);
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement(
                        "UPDATE compte_jeton SET expire_le = now() - interval '1 minute' WHERE compte_id = ?")) {
            ps.setString(1, compte.id());
            ps.executeUpdate();
        }
        given().contentType("application/json")
                .body(Map.of("token", jeton, "password", PASSWORD))
                .post("/api/auth/mot-de-passe")
                .then()
                .statusCode(400);
    }

    @Test
    void unNouveauLienRemplaceLePrecedent() {
        Compte compte = account(false);
        acces.sendLink(compte.id(), true);
        String premier = linkToken(compte);
        mailbox.clear();
        acces.sendLink(compte.id(), false);
        String second = linkToken(compte);
        assertThat(second).isNotEqualTo(premier);
        given().contentType("application/json")
                .body(Map.of("token", premier, "password", PASSWORD))
                .post("/api/auth/mot-de-passe")
                .then()
                .statusCode(400);
    }

    private void waitForMails(int total) throws InterruptedException {
        for (int i = 0; i < 100 && mailbox.getTotalMessagesSent() < total; i++) {
            Thread.sleep(50);
        }
    }

    @Test
    void lOubliRepondLaMemeChoseQueLAdresseExisteOuNon() throws InterruptedException {
        Compte compte = withPassword(false);
        given().contentType("application/json")
                .body(Map.of("email", compte.email()))
                .post("/api/auth/mot-de-passe/oubli")
                .then()
                .statusCode(204);
        given().contentType("application/json")
                .body(Map.of("email", "personne-" + UUID.randomUUID() + "@example.org"))
                .post("/api/auth/mot-de-passe/oubli")
                .then()
                .statusCode(204);
        // The work is done off the request: the answer never waits for the SMTP exchange.
        waitForMails(1);
        assertThat(mailbox.getMailsSentTo(compte.email())).hasSize(1);
        assertThat(linkToken(compte)).isNotBlank();
        assertThat(mailbox.getTotalMessagesSent()).isEqualTo(1);
    }

    @Test
    void lOubliNAnnulePasUneInvitationEncoreValable() throws InterruptedException {
        Compte compte = account(false);
        acces.sendLink(compte.id(), true);
        String invitation = linkToken(compte);
        given().contentType("application/json")
                .body(Map.of("email", compte.email()))
                .post("/api/auth/mot-de-passe/oubli")
                .then()
                .statusCode(204);
        Thread.sleep(500);
        assertThat(mailbox.getMailsSentTo(compte.email())).hasSize(1);
        given().contentType("application/json")
                .body(Map.of("token", invitation, "password", PASSWORD))
                .post("/api/auth/mot-de-passe")
                .then()
                .statusCode(204);
    }

    @Test
    void unNouveauMotDePasseFermeLesSessionsOuvertesEtPrevientLeTitulaire() {
        Compte compte = withPassword(true);
        String challenge =
                passwordStep(compte.email(), PASSWORD).then().extract().path("challenge");
        Matcher m = CODE.matcher(mailText(compte));
        assertThat(m.find()).isTrue();
        String cookie = formPost(compte.email(), challenge + ":" + m.group(1)).getCookie("planning-session");
        given().header("X-Edition-Id", "E1")
                .cookie("planning-session", cookie)
                .get("/api/animateurs")
                .then()
                .statusCode(200);

        mailbox.clear();
        acces.sendLink(compte.id(), false);
        acces.choose(linkToken(compte), PASSWORD + " nouveau");

        given().header("X-Edition-Id", "E1")
                .cookie("planning-session", cookie)
                .get("/api/animateurs")
                .then()
                .statusCode(403);
        assertThat(mailbox.getMailsSentTo(compte.email()).get(1).getText()).contains("vient d'être changé");
    }

    @Test
    void lesAccentsSontNormalises() {
        Compte compte = account(false);
        acces.sendLink(compte.id(), true);
        acces.choose(linkToken(compte), "e\u0301le\u0300ve tre\u0300s long");
        passwordStep(compte.email(), "\u00e9l\u00e8ve tr\u00e8s long").then().statusCode(200);
    }

    @Test
    void desactiverUnCompteEffaceSesLiensEnAttente() {
        Compte compte = account(false);
        acces.sendLink(compte.id(), true);
        String token = linkToken(compte);
        comptes.deactivate(compte.id());
        comptes.reactivate(compte.id());
        given().contentType("application/json")
                .body(Map.of("token", token, "password", PASSWORD))
                .post("/api/auth/mot-de-passe")
                .then()
                .statusCode(400);
    }

    @Test
    void lePasDeConnexionLocaleNAcceptePasLeMotDePasseAdminNiLeDefiSurLeFormulaireAdmin() {
        assertThat(sessionOpened(formPost("admin", "admin"))).isFalse();
        Compte compte = withPassword(true);
        String challenge =
                passwordStep(compte.email(), PASSWORD).then().extract().path("challenge");
        Matcher m = CODE.matcher(mailText(compte));
        assertThat(m.find()).isTrue();
        Response admin = given().contentType("application/x-www-form-urlencoded")
                .formParam("j_username", compte.email())
                .formParam("j_password", challenge + ":" + m.group(1))
                .redirects()
                .follow(false)
                .post("/j_security_check");
        assertThat(sessionOpened(admin)).isFalse();
        // And the challenge was not spent by the wrong door.
        assertThat(sessionOpened(formPost(compte.email(), challenge + ":" + m.group(1))))
                .isTrue();
    }

    @Test
    void laConnexionExigeLeMotDePasseEtLeCodeRecuParMail() {
        Compte compte = withPassword(true);

        passwordStep(compte.email(), "mauvais-" + UUID.randomUUID()).then().statusCode(401);
        assertThat(mailbox.getMailsSentTo(compte.email())).isEmpty();

        String challenge = passwordStep(compte.email(), PASSWORD)
                .then()
                .statusCode(200)
                .body("codeRequired", equalTo(true))
                .body("maskedEmail", notNullValue())
                .extract()
                .path("challenge");
        Matcher m = CODE.matcher(mailText(compte));
        assertThat(m.find()).isTrue();
        String code = m.group(1);

        // Without the code, or with a wrong one, no session.
        assertThat(sessionOpened(formPost(compte.email(), challenge))).isFalse();
        assertThat(sessionOpened(
                        formPost(compte.email(), challenge + ":" + (code.equals("000000") ? "111111" : "000000"))))
                .isFalse();

        // The right code: a session, which opens the API as an administrator.
        Response ok = formPost(compte.email(), challenge + ":" + code);
        assertThat(sessionOpened(ok)).isTrue();
        String cookie = ok.getCookie("planning-session");
        given().header("X-Edition-Id", "E1")
                .cookie("planning-session", cookie)
                .get("/api/animateurs")
                .then()
                .statusCode(200);
        // And once: the challenge is spent.
        assertThat(sessionOpened(formPost(compte.email(), challenge + ":" + code)))
                .isFalse();
    }

    @Test
    void unCodeExpireEstRefuse() throws Exception {
        Compte compte = withPassword(true);
        String challenge =
                passwordStep(compte.email(), PASSWORD).then().extract().path("challenge");
        Matcher m = CODE.matcher(mailText(compte));
        assertThat(m.find()).isTrue();
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement(
                        "UPDATE compte_defi SET expire_le = now() - interval '1 minute' WHERE compte_id = ?")) {
            ps.setString(1, compte.id());
            ps.executeUpdate();
        }
        assertThat(sessionOpened(formPost(compte.email(), challenge + ":" + m.group(1))))
                .isFalse();
    }

    @Test
    void unDefiNeSertPasPourUneAutreAdresse() {
        Compte compte = withPassword(true);
        Compte autre = withPassword(true);
        String challenge =
                passwordStep(compte.email(), PASSWORD).then().extract().path("challenge");
        Matcher m = CODE.matcher(mailText(compte));
        assertThat(m.find()).isTrue();
        assertThat(sessionOpened(formPost(autre.email(), challenge + ":" + m.group(1))))
                .isFalse();
    }

    @Test
    void unCompteSansDroitNeRecoitAucunRole() {
        Compte compte = withPassword(false);
        String challenge =
                passwordStep(compte.email(), PASSWORD).then().extract().path("challenge");
        Matcher m = CODE.matcher(mailText(compte));
        assertThat(m.find()).isTrue();
        Response ok = formPost(compte.email(), challenge + ":" + m.group(1));
        assertThat(sessionOpened(ok)).isTrue();
        String cookie = ok.getCookie("planning-session");
        given().header("X-Edition-Id", "E1")
                .cookie("planning-session", cookie)
                .get("/api/animateurs")
                .then()
                .statusCode(403);
        given().cookie("planning-session", cookie)
                .get("/api/auth/me")
                .then()
                .body("authentifie", equalTo(true))
                .body("roles.size()", equalTo(0));
    }

    @Test
    void unCompteDesactiveEstRefuseEtSaSessionSeCoupe() {
        Compte compte = withPassword(true);
        Compte admin = withPassword(true); // another one stays: the last administrator is protected
        assertThat(admin.id()).isNotEqualTo(compte.id());
        String challenge =
                passwordStep(compte.email(), PASSWORD).then().extract().path("challenge");
        Matcher m = CODE.matcher(mailText(compte));
        assertThat(m.find()).isTrue();
        String cookie = formPost(compte.email(), challenge + ":" + m.group(1)).getCookie("planning-session");
        given().header("X-Edition-Id", "E1")
                .cookie("planning-session", cookie)
                .get("/api/animateurs")
                .then()
                .statusCode(200);

        comptes.deactivate(compte.id());

        given().header("X-Edition-Id", "E1")
                .cookie("planning-session", cookie)
                .get("/api/animateurs")
                .then()
                .statusCode(403);
        passwordStep(compte.email(), PASSWORD).then().statusCode(401);

        // Reactivating the account does not bring its old cookie back to life.
        comptes.reactivate(compte.id());
        given().header("X-Edition-Id", "E1")
                .cookie("planning-session", cookie)
                .get("/api/animateurs")
                .then()
                .statusCode(403);
        given().cookie("planning-session", cookie).get("/api/auth/me").then().body("authentifie", equalTo(false));
    }

    @Test
    void lAdresseInconnueEtLeMauvaisMotDePasseRepondentPareil() {
        Compte compte = withPassword(false);
        String unknownAddress = "inconnu-" + UUID.randomUUID() + "@example.org";
        Response inconnue = passwordStep(unknownAddress, PASSWORD);
        Response mauvais = passwordStep(compte.email(), "mauvais-" + UUID.randomUUID());
        assertThat(inconnue.statusCode()).isEqualTo(mauvais.statusCode()).isEqualTo(401);
        assertThat(inconnue.asString()).isEqualTo(mauvais.asString());
    }

    @Test
    void l_adminDeLaConfigurationPasseToujoursParLeFormulaireDeSecours() {
        Response ok = given().contentType("application/x-www-form-urlencoded")
                .formParam("j_username", "admin")
                .formParam("j_password", "admin")
                .redirects()
                .follow(false)
                .post("/j_security_check");
        assertThat(sessionOpened(ok)).isTrue();
    }

    @Test
    void unAdministrateurInviteEtReinitialiseSansVoirLeLien() {
        String email = "invite-" + UUID.randomUUID() + "@example.org";
        // The route is the admin's: this profile runs the real policy, so sign in as the configuration account.
        String cookie = given().contentType("application/x-www-form-urlencoded")
                .formParam("j_username", "admin")
                .formParam("j_password", "admin")
                .redirects()
                .follow(false)
                .post("/j_security_check")
                .getCookie("planning-session");
        Compte cree = given().cookie("planning-session", cookie)
                .header("X-Edition-Id", "E1")
                .contentType("application/json")
                .body(Map.of("email", email, "invite", true))
                .post("/api/comptes")
                .then()
                .statusCode(200)
                .body("passwordSet", equalTo(false))
                .extract()
                .as(Compte.class);
        assertThat(mailbox.getMailsSentTo(email)).hasSize(1);

        given().cookie("planning-session", cookie)
                .header("X-Edition-Id", "E1")
                .post("/api/comptes/" + cree.id() + "/reinitialisation")
                .then()
                .statusCode(204);
        assertThat(mailbox.getMailsSentTo(email)).hasSize(2);

        comptes.deactivate(cree.id());
        given().cookie("planning-session", cookie)
                .header("X-Edition-Id", "E1")
                .post("/api/comptes/" + cree.id() + "/invitation")
                .then()
                .statusCode(409);
        assertThat(mailbox.getMailsSentTo(email)).hasSize(2);
    }
}
