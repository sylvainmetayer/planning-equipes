package dev.sylvain.planning.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import io.sentry.SentryEvent;
import io.sentry.protocol.Message;
import io.sentry.protocol.SentryException;
import org.junit.jupiter.api.Test;

/**
 * Scrubbing of the espace animateur's access token from anything reported to
 * the error tracker.
 *
 * <p>The privacy policy tells the reader that identifier is removed before a
 * report leaves. These tests are what makes that sentence true on the server
 * side: an exception raised while serving {@code /api/espace-animateur/{token}}
 * carries the path in its message, and the tracker is a third-party processor
 * that keeps what it receives.</p>
 */
class SentryInitializerTest {

    @Test
    void masqueLeJetonDansLesDeuxFormesQuePrendLURL() {
        assertThat(SentryInitializer.maskToken("GET /api/espace-animateur/a1b2c3/postes a échoué"))
                .isEqualTo("GET /api/espace-animateur/<jeton>/postes a échoué");
        assertThat(SentryInitializer.maskToken("https://planning.example.org/animateur/a1b2c3/echanges"))
                .isEqualTo("https://planning.example.org/animateur/<jeton>/echanges");
    }

    @Test
    void laisseIntactCeQuiNePorteAucunJeton() {
        assertThat(SentryInitializer.maskToken("Connexion à la base perdue")).isEqualTo("Connexion à la base perdue");
        assertThat(SentryInitializer.maskToken((String) null)).isNull();
    }

    @Test
    void masqueLeMessageDeLEvenementEtCeluiDeChaqueException() {
        SentryEvent rapport = new SentryEvent();
        Message message = new Message();
        message.setFormatted("Échec sur /animateur/a1b2c3");
        rapport.setMessage(message);
        SentryException exception = new SentryException();
        exception.setValue("NotFoundException: /api/espace-animateur/a1b2c3/postes");
        rapport.setExceptions(List.of(exception));

        SentryInitializer.maskTokensInReport(rapport);

        assertThat(rapport.getMessage().getFormatted()).isEqualTo("Échec sur /animateur/<jeton>");
        assertThat(rapport.getExceptions().get(0).getValue())
                .isEqualTo("NotFoundException: /api/espace-animateur/<jeton>/postes");
    }

    @Test
    void unRapportSansMessageNiExceptionNeCassePas() {
        SentryEvent rapport = new SentryEvent();

        assertThat(SentryInitializer.maskTokensInReport(rapport)).isSameAs(rapport);
    }
}
