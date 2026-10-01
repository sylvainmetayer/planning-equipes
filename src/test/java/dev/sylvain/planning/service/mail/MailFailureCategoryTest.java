package dev.sylvain.planning.service.mail;

import static org.assertj.core.api.Assertions.assertThat;

import io.vertx.ext.mail.SMTPException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.List;
import java.util.concurrent.CompletionException;
import javax.net.ssl.SSLHandshakeException;
import org.junit.jupiter.api.Test;

/**
 * What an organiser reads of a failed send is one of five words, read off the
 * exception the mailer threw. Pure, so pinned here without a mailer: the
 * Vert.x client's {@link SMTPException}, the network failures on the way to
 * the relay, and the bare messages of another client.
 */
class MailFailureCategoryTest {

    private static SMTPException smtp(int code, String reply) {
        return new SMTPException("sending failed", code, List.of(reply), true);
    }

    @Test
    void anUnreachableRelayIsNamedAsSuch() {
        assertThat(MailFailureCategory.of(new ConnectException("Connection refused: localhost/127.0.0.1:1025")))
                .isEqualTo(MailFailureCategory.RELAIS_INJOIGNABLE);
        assertThat(MailFailureCategory.of(new UnknownHostException("smtp.example.org")))
                .isEqualTo(MailFailureCategory.RELAIS_INJOIGNABLE);
        assertThat(MailFailureCategory.of(new SocketTimeoutException("connect timed out")))
                .isEqualTo(MailFailureCategory.RELAIS_INJOIGNABLE);
        assertThat(MailFailureCategory.of(new SSLHandshakeException("PKIX path building failed")))
                .isEqualTo(MailFailureCategory.RELAIS_INJOIGNABLE);
    }

    /** The blocking mailer wraps what the Vert.x client failed with: the chain is walked. */
    @Test
    void theCauseChainIsWalked() {
        RuntimeException wrapped =
                new CompletionException(new IllegalStateException("send failed", new ConnectException("refused")));

        assertThat(MailFailureCategory.of(wrapped)).isEqualTo(MailFailureCategory.RELAIS_INJOIGNABLE);
        assertThat(MailFailureCategory.of(new CompletionException(smtp(535, "535 5.7.8 Authentication failed"))))
                .isEqualTo(MailFailureCategory.AUTHENTIFICATION);
    }

    /**
     * The blocking mailer's {@code CompletionException} carries its cause's
     * {@code toString()} as its message, host and port included: the port is
     * not a reply code, and the type underneath wins over the message above.
     */
    @Test
    void aWrappedNetworkFailureIsNotReadThroughThePortItQuotes() {
        assertThat(MailFailureCategory.of(new CompletionException(
                        new ConnectException("Connection refused: smtp.example.com/203.0.113.10:465"))))
                .isEqualTo(MailFailureCategory.RELAIS_INJOIGNABLE);
        assertThat(MailFailureCategory.of(new CompletionException(
                        new ConnectException("Connection refused: smtp.example.com/203.0.113.10:587"))))
                .isEqualTo(MailFailureCategory.RELAIS_INJOIGNABLE);
        assertThat(MailFailureCategory.of(new IllegalStateException("could not reach smtp.example.com:465")))
                .isEqualTo(MailFailureCategory.AUTRE);
    }

    @Test
    void refusedCredentialsAreAnAuthenticationFailure() {
        assertThat(MailFailureCategory.of(smtp(535, "535 5.7.8 Username and Password not accepted")))
                .isEqualTo(MailFailureCategory.AUTHENTIFICATION);
        assertThat(MailFailureCategory.of(smtp(530, "530 5.7.0 Authentication required")))
                .isEqualTo(MailFailureCategory.AUTHENTIFICATION);
        assertThat(MailFailureCategory.of(new IllegalStateException("AUTH failed: bad credentials")))
                .isEqualTo(MailFailureCategory.AUTHENTIFICATION);
    }

    /** What jakarta.mail would throw, read by its message: no type of that client is on the classpath. */
    @Test
    void anAuthenticationFailureOfAnotherClientIsReadByItsMessage() {
        class AuthenticationFailedException extends Exception {
            AuthenticationFailedException(String message) {
                super(message);
            }
        }

        assertThat(MailFailureCategory.of(
                        new CompletionException(new AuthenticationFailedException("Authentication failed"))))
                .isEqualTo(MailFailureCategory.AUTHENTIFICATION);
    }

    @Test
    void aRecipientRefusedForGoodIsARefusedAddress() {
        assertThat(MailFailureCategory.of(smtp(550, "550 5.1.1 <x@example.org>: Recipient address rejected")))
                .isEqualTo(MailFailureCategory.ADRESSE_REFUSEE);
        assertThat(MailFailureCategory.of(smtp(553, "553 mailbox name not allowed")))
                .isEqualTo(MailFailureCategory.ADRESSE_REFUSEE);
        assertThat(MailFailureCategory.of(smtp(501, "501 5.1.3 Bad recipient address syntax")))
                .isEqualTo(MailFailureCategory.ADRESSE_REFUSEE);
        assertThat(MailFailureCategory.of(new IllegalStateException("550 5.1.1 recipient refused")))
                .isEqualTo(MailFailureCategory.ADRESSE_REFUSEE);
    }

    /**
     * A 5.7.x is the relay refusing the application — its sender, its right to
     * relay —, not the recipient: read as a refused address, a wrong
     * {@code MAIL_FROM} would hold back the reminders of the whole roster.
     */
    @Test
    void aPolicyRefusalIsNotARefusedAddress() {
        assertThat(MailFailureCategory.of(smtp(553, "553 5.7.1 <noreply@example.org>: Sender address rejected")))
                .isEqualTo(MailFailureCategory.AUTRE);
        assertThat(MailFailureCategory.of(smtp(550, "550 5.7.1 Relaying denied")))
                .isEqualTo(MailFailureCategory.AUTRE);
        assertThat(MailFailureCategory.of(new IllegalStateException("550 5.7.1 Relaying denied")))
                .isEqualTo(MailFailureCategory.AUTRE);
        assertThat(MailFailureCategory.of(smtp(550, "550 5.7.1 Relaying denied: authentication required")))
                .isEqualTo(MailFailureCategory.AUTHENTIFICATION);
        assertThat(MailFailureCategory.of(smtp(550, "550 Requested action not taken: mailbox unavailable")))
                .as("without an enhanced status, 550 keeps its usual meaning")
                .isEqualTo(MailFailureCategory.ADRESSE_REFUSEE);
        assertThat(MailFailureCategory.of(smtp(550, "550 mailbox unavailable (relay 2.0.1)")))
                .as("a status of another class than the reply's is no status")
                .isEqualTo(MailFailureCategory.ADRESSE_REFUSEE);
    }

    /** Every 4xx says « try later », and so does a full mailbox: neither holds the next reminder back. */
    @Test
    void aTemporaryRefusalOrAFullMailboxIsTemporary() {
        assertThat(MailFailureCategory.of(smtp(421, "421 4.7.0 Try again later")))
                .isEqualTo(MailFailureCategory.TEMPORAIRE);
        assertThat(MailFailureCategory.of(smtp(450, "450 4.2.1 Mailbox busy")))
                .isEqualTo(MailFailureCategory.TEMPORAIRE);
        assertThat(MailFailureCategory.of(smtp(552, "552 5.2.2 Mailbox full")))
                .isEqualTo(MailFailureCategory.TEMPORAIRE);
        assertThat(MailFailureCategory.of(new IllegalStateException("451 local error in processing")))
                .isEqualTo(MailFailureCategory.TEMPORAIRE);
    }

    @Test
    void anythingElseIsOther() {
        assertThat(MailFailureCategory.of(smtp(554, "554 5.7.1 Message rejected as spam")))
                .isEqualTo(MailFailureCategory.AUTRE);
        assertThat(MailFailureCategory.of(new IllegalStateException("template could not be rendered")))
                .isEqualTo(MailFailureCategory.AUTRE);
        assertThat(MailFailureCategory.of(new IllegalStateException((String) null)))
                .isEqualTo(MailFailureCategory.AUTRE);
    }

    /** A port number or a year in a message is not a reply code. */
    @Test
    void digitsInsideAMessageAreNotMistakenForAReplyCode() {
        assertThat(MailFailureCategory.of(new IllegalStateException("could not open localhost:2525 in 2026")))
                .isEqualTo(MailFailureCategory.AUTRE);
    }
}
