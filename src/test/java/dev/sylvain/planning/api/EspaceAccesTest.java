package dev.sylvain.planning.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.service.PlanningPersistenceService;
import dev.sylvain.planning.service.ReferenceDataService;
import io.quarkus.mailer.Mail;
import io.quarkus.mailer.MockMailbox;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;

/**
 * Passwordless authentication of the espace animateur: the link is no longer
 * enough, a code sent to the address on the record opens a lasting session.
 * Exercised end to end — the code is read from the mock mailbox, the way an
 * animateur would read it from their own.
 */
@QuarkusTest
class EspaceAccesTest {

    private static final LocalDate JOUR = LocalDate.of(2026, 7, 14);
    private static final long CRENEAU_ID = 9501L;
    private static final String EMAIL_ALICE = "acces-alice@example.org";

    @Inject
    PlanningPersistenceService persistence;

    @Inject
    ReferenceDataService referenceData;

    @Inject
    MockMailbox mailbox;

    @Inject
    DataSource dataSource;

    @BeforeEach
    void seed() {
        mailbox.clear();
        Animateur alice = new Animateur("ACCES-A", "Alice", "Martin", LocalDate.of(1990, 1, 1), false);
        Animateur bruno = new Animateur("ACCES-B", "Bruno", "Petit", LocalDate.of(1992, 2, 2), false);
        Stand stand = new Stand("ACCES-S1", "Stand accès", Set.of(), 1, 1, false);
        Creneau creneau = new Creneau(CRENEAU_ID, 1, JOUR, LocalTime.of(10, 0), LocalTime.of(12, 0));
        PosteAffectation poste = new PosteAffectation("ACCES-P1", stand, creneau);
        poste.setAnimateur(alice);
        persistence.persist(new PlanningEvenement(JOUR, List.of(alice, bruno), List.of(poste)));
        donnerEmail("ACCES-A", EMAIL_ALICE);
        donnerEmail("ACCES-B", null);
    }

    /** The whole espace — downloads included — answers 401 without a session. */
    @Test
    void sansSessionToutLEspaceRepond401() {
        String token = tokenOf("ACCES-A");
        given().when().get("/api/espace-animateur/" + token)
                .then().statusCode(401)
                .body("message", containsString("code d'accès"));
        given().when().get("/api/espace-animateur/" + token + "/demandes")
                .then().statusCode(401);
        given().when().get("/api/espace-animateur/" + token + "/planning.pdf")
                .then().statusCode(401);
        given().when().get("/api/espace-animateur/" + token + "/planning.ics")
                .then().statusCode(401);
        // An unknown token stays a plain 404: nothing must help guessing one.
        given().when().get("/api/espace-animateur/jeton-invente")
                .then().statusCode(404);
    }

    @Test
    void leCodeRecuParEmailOuvreUneSessionDurable() {
        String token = tokenOf("ACCES-A");
        String session = EspaceSessions.open(mailbox, token, EMAIL_ALICE);

        given().cookie("planning-espace", session)
                .when().get("/api/espace-animateur/" + token)
                .then().statusCode(200)
                .body("animateurId", equalTo("ACCES-A"));
        given().cookie("planning-espace", session)
                .when().get("/api/espace-animateur/" + token + "/planning.pdf")
                .then().statusCode(200);
    }

    /** The session is bound to ONE animateur: it does not open a colleague's espace. */
    @Test
    void laSessionDUnAnimateurNOuvrePasLEspaceDUnAutre() {
        String session = EspaceSessions.open(mailbox, tokenOf("ACCES-A"), EMAIL_ALICE);
        given().cookie("planning-espace", session)
                .when().get("/api/espace-animateur/" + tokenOf("ACCES-B"))
                .then().statusCode(401);
    }

    /**
     * The session cookie is worth 30 days: on an HTTPS visit it must carry
     * {@code Secure}, otherwise it would also leave in clear on an
     * {@code http://} request to the same host. Behind a proxy terminating TLS
     * the origin sees plain http: {@code X-Forwarded-Proto} is what tells the
     * scheme of the real visit.
     */
    @Test
    void leCookieDeSessionPorteSecureQuandLaVisiteEstEnHttps() {
        String token = tokenOf("ACCES-A");
        String setCookie = given().contentType(ContentType.JSON)
                .header("X-Forwarded-Proto", "https")
                .body("{\"code\":\"" + codeEnvoye(token) + "\"}")
                .when().post("/api/espace-animateur/" + token + "/session")
                .then().statusCode(204)
                .extract().header("Set-Cookie");
        assertThat(setCookie)
                .contains("HttpOnly")
                .contains("SameSite=Strict")
                .contains("Secure");
    }

    /** On the local http stack the flag is absent — otherwise the browser would drop the cookie. */
    @Test
    void leCookieDeSessionResteUtilisableSurUneVisiteEnClair() {
        String token = tokenOf("ACCES-A");
        String setCookie = given().contentType(ContentType.JSON)
                .body("{\"code\":\"" + codeEnvoye(token) + "\"}")
                .when().post("/api/espace-animateur/" + token + "/session")
                .then().statusCode(204)
                .extract().header("Set-Cookie");
        assertThat(setCookie).doesNotContain("Secure");
    }

    @Test
    void sansAdresseEmailAucunCodeNEstPossible() {
        given().contentType(ContentType.JSON)
                .when().post("/api/espace-animateur/" + tokenOf("ACCES-B") + "/code")
                .then().statusCode(400)
                .body("message", containsString("Aucune adresse e-mail"));
    }

    @Test
    void unMauvaisCodeEpuiseSesTentativesPuisExigeUnNouveauCode() {
        String token = tokenOf("ACCES-A");
        given().contentType(ContentType.JSON)
                .when().post("/api/espace-animateur/" + token + "/code")
                .then().statusCode(200)
                .body("emailMasque", equalTo("a•••@example.org"));

        for (int tentative = 0; tentative < 5; tentative++) {
            given().contentType(ContentType.JSON).body("{\"code\":\"000000\"}")
                    .when().post("/api/espace-animateur/" + token + "/session")
                    .then().statusCode(400)
                    .body("message", containsString("Code incorrect"));
        }
        given().contentType(ContentType.JSON).body("{\"code\":\"000000\"}")
                .when().post("/api/espace-animateur/" + token + "/session")
                .then().statusCode(400)
                .body("message", containsString("nouveau code"));
    }

    @Test
    void unCodeExpireEstRefuse() throws Exception {
        String token = tokenOf("ACCES-A");
        given().contentType(ContentType.JSON)
                .when().post("/api/espace-animateur/" + token + "/code")
                .then().statusCode(200);
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement(
                        "UPDATE espace_acces SET expire_le = now() - interval '1 minute' "
                                + "WHERE animateur_id = 'ACCES-A'")) {
            ps.executeUpdate();
        }
        // Whatever the code was, an expired one must be refused unread.
        given().contentType(ContentType.JSON).body("{\"code\":\"123456\"}")
                .when().post("/api/espace-animateur/" + token + "/session")
                .then().statusCode(400)
                .body("message", containsString("nouveau code"));
    }

    /** Asks for a code and reads it back from the mock mailbox, as the animateur would. */
    private String codeEnvoye(String token) {
        given().contentType(ContentType.JSON)
                .when().post("/api/espace-animateur/" + token + "/code")
                .then().statusCode(200);
        List<Mail> mails = mailbox.getMailsSentTo(EMAIL_ALICE);
        assertThat(mails).isNotEmpty();
        Matcher matcher = Pattern.compile("\\b(\\d{6})\\b").matcher(mails.get(mails.size() - 1).getText());
        assertThat(matcher.find()).isTrue();
        return matcher.group(1);
    }

    private void donnerEmail(String animateurId, String email) {
        Animateur animateur = referenceData.listAnimateurs().stream()
                .filter(candidat -> candidat.getId().equals(animateurId))
                .findFirst()
                .orElseThrow();
        animateur.setEmail(email);
        referenceData.updateAnimateur(animateurId, animateur);
    }

    private String tokenOf(String animateurId) {
        return referenceData.listAnimateurs().stream()
                .filter(candidat -> candidat.getId().equals(animateurId))
                .findFirst()
                .orElseThrow()
                .getAccessToken();
    }
}
