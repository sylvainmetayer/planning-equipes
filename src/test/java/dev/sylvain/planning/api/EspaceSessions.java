package dev.sylvain.planning.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.mailer.Mail;
import io.quarkus.mailer.MockMailbox;
import io.restassured.http.ContentType;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Opens espace-animateur sessions for the tests, the way an animateur does:
 * ask for a code, read it in the (mock) mailbox, exchange it for the session
 * cookie. No backdoor — the flow under test is the flow used.
 */
final class EspaceSessions {

    private static final Pattern CODE = Pattern.compile("\\b(\\d{6})\\b");

    private EspaceSessions() {}

    /** Returns the {@code planning-espace} cookie value of a fresh session. */
    static String open(MockMailbox mailbox, String token, String email) {
        given().contentType(ContentType.JSON)
                .when()
                .post("/api/espace-animateur/" + token + "/code")
                .then()
                .statusCode(200);
        List<Mail> mails = mailbox.getMailsSentTo(email);
        assertThat(mails).as("the access code mail must reach " + email).isNotEmpty();
        Matcher matcher = CODE.matcher(mails.get(mails.size() - 1).getText());
        assertThat(matcher.find()).as("the mail must carry a 6-digit code").isTrue();
        String cookie = given().contentType(ContentType.JSON)
                .body("{\"code\":\"" + matcher.group(1) + "\"}")
                .when()
                .post("/api/espace-animateur/" + token + "/session")
                .then()
                .statusCode(204)
                .extract()
                .cookie("planning-espace");
        assertThat(cookie).isNotBlank();
        return cookie;
    }
}
