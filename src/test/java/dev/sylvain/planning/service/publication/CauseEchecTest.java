package dev.sylvain.planning.service.publication;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sylvain.planning.service.publication.EnvoiPlanningRepository.CauseEchec;
import java.net.ConnectException;
import org.junit.jupiter.api.Test;

/**
 * The cause of a failed planning mail, read off the exception chain: a short
 * code the Diffuser table words, never the server's message itself.
 */
class CauseEchecTest {

    @Test
    void aRefusedRecipientIsAnAddressRefused() {
        assertThat(CauseEchec.of(new IllegalStateException(
                        "Failed", new RuntimeException("550 5.1.1 <x@example.org>: Recipient address rejected"))))
                .isEqualTo(CauseEchec.ADRESSE_REFUSEE);
    }

    @Test
    void aFullMailboxIsSaidAsSuch() {
        assertThat(CauseEchec.of(new RuntimeException("552 5.2.2 Mailbox full")))
                .isEqualTo(CauseEchec.BOITE_PLEINE);
    }

    @Test
    void anUnreachableServerIsNotBlamedOnTheAddress() {
        assertThat(CauseEchec.of(new RuntimeException("send failed", new ConnectException("Connection refused"))))
                .isEqualTo(CauseEchec.SERVEUR_INJOIGNABLE);
    }

    @Test
    void anythingElseIsUnknown() {
        assertThat(CauseEchec.of(new IllegalStateException("PDF could not be built")))
                .isEqualTo(CauseEchec.AUTRE);
        assertThat(CauseEchec.of(new IllegalStateException((String) null))).isEqualTo(CauseEchec.AUTRE);
    }
}
