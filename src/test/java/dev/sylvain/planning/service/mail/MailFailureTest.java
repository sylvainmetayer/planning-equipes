package dev.sylvain.planning.service.mail;

import static org.assertj.core.api.Assertions.assertThat;

import io.vertx.ext.mail.SMTPException;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.util.List;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;

/**
 * The categories an organiser acts on, read from what the mailer throws. The
 * one split that changes behaviour is a permanent refusal against a temporary
 * one: the first stops every send until the address changes, the second does
 * not.
 */
class MailFailureTest {

    @Test
    void aPermanentRefusalOfTheRecipientIsARefusedAddress() {
        assertThat(MailFailure.classify(wrapped(smtp(550)))).isEqualTo(MailFailure.ADRESSE_REFUSEE);
        assertThat(MailFailure.classify(smtp(553))).isEqualTo(MailFailure.ADRESSE_REFUSEE);
    }

    @Test
    void aTransientReplyIsTemporary() {
        assertThat(MailFailure.classify(wrapped(smtp(450)))).isEqualTo(MailFailure.TEMPORAIRE);
        assertThat(MailFailure.classify(smtp(421))).isEqualTo(MailFailure.TEMPORAIRE);
    }

    @Test
    void theAuthenticationRepliesAreAboutTheRelayNotThePerson() {
        assertThat(MailFailure.classify(wrapped(smtp(535)))).isEqualTo(MailFailure.AUTHENTIFICATION);
        assertThat(MailFailure.classify(smtp(530))).isEqualTo(MailFailure.AUTHENTIFICATION);
        assertThat(MailFailure.classify(new IllegalStateException("AUTH LOGIN failed")))
                .isEqualTo(MailFailure.AUTHENTIFICATION);
    }

    @Test
    void anUnreachableRelayIsSaidSo() {
        assertThat(MailFailure.classify(wrapped(new ConnectException("Connection refused"))))
                .isEqualTo(MailFailure.RELAIS_INJOIGNABLE);
        assertThat(MailFailure.classify(new RuntimeException(new UnknownHostException("smtp.invalid"))))
                .isEqualTo(MailFailure.RELAIS_INJOIGNABLE);
    }

    @Test
    void anythingElseIsOther() {
        assertThat(MailFailure.classify(new IllegalStateException("boom"))).isEqualTo(MailFailure.AUTRE);
    }

    @Test
    void onlyARefusedAddressBlocksFurtherSends() {
        java.time.Instant now = java.time.Instant.now();
        assertThat(new LastDelivery("RELANCE_NUIT", "ECHEC", "ADRESSE_REFUSEE", now).blocksAddress())
                .isTrue();
        assertThat(new LastDelivery("RELANCE_NUIT", "ECHEC", "TEMPORAIRE", now).blocksAddress())
                .isFalse();
        assertThat(new LastDelivery("RELANCE_NUIT", "ENVOYE", null, now).blocksAddress())
                .isFalse();
    }

    private static SMTPException smtp(int code) {
        return new SMTPException(code + " refused", code, List.of(code + " refused"), code >= 500);
    }

    /** What the blocking mailer throws: the Vert.x client's exception, wrapped. */
    private static RuntimeException wrapped(Throwable cause) {
        return new CompletionException(cause);
    }
}
