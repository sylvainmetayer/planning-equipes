package dev.sylvain.planning.service.webhook;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.ConnectException;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import javax.net.ssl.SSLHandshakeException;
import org.junit.jupiter.api.Test;

/** The schedule and the reading of an answer, without a network. */
class WebhookDelivererTest {

    @Test
    void aSuccessIsFinal() {
        WebhookDeliverer.Exchange exchange = WebhookDeliverer.classify(204, null, 12);

        assertThat(exchange.delivered()).isTrue();
        assertThat(exchange.error()).isNull();
    }

    @Test
    void timeoutsThrottlingAndServerErrorsAreRetried() {
        for (int status : new int[] {408, 429, 500, 502, 503}) {
            WebhookDeliverer.Exchange exchange = WebhookDeliverer.classify(status, null, 12);
            assertThat(exchange.retryable()).as("%d", status).isTrue();
            assertThat(exchange.delivered()).isFalse();
        }
    }

    @Test
    void anyOtherAnswerIsGivenUpAtOnce() {
        for (int status : new int[] {301, 302, 400, 401, 403, 404, 410}) {
            WebhookDeliverer.Exchange exchange = WebhookDeliverer.classify(status, null, 12);
            assertThat(exchange.retryable()).as("%d", status).isFalse();
            assertThat(exchange.delivered()).isFalse();
        }
        assertThat(WebhookDeliverer.classify(302, null, 1).error()).contains("Redirection non suivie");
    }

    @Test
    void theScheduleIsOneFiveThirtyMinutesThenTwoAndTwelveHours() {
        assertThat(WebhookDeliverer.MAX_ATTEMPTS).isEqualTo(6);
        assertThat(WebhookDeliverer.nextDelay(1, null)).isEqualTo(Duration.ofMinutes(1));
        assertThat(WebhookDeliverer.nextDelay(2, null)).isEqualTo(Duration.ofMinutes(5));
        assertThat(WebhookDeliverer.nextDelay(3, null)).isEqualTo(Duration.ofMinutes(30));
        assertThat(WebhookDeliverer.nextDelay(4, null)).isEqualTo(Duration.ofHours(2));
        assertThat(WebhookDeliverer.nextDelay(5, null)).isEqualTo(Duration.ofHours(12));
    }

    @Test
    void retryAfterIsHonouredWithinTheLastDelay() {
        WebhookDeliverer.Exchange exchange = WebhookDeliverer.classify(429, "600", 12);

        assertThat(exchange.retryAfter()).isEqualTo(Duration.ofMinutes(10));
        assertThat(WebhookDeliverer.nextDelay(1, exchange.retryAfter())).isEqualTo(Duration.ofMinutes(10));
        // Shorter than the schedule: the schedule wins.
        assertThat(WebhookDeliverer.nextDelay(3, Duration.ofSeconds(5))).isEqualTo(Duration.ofMinutes(30));
        // A week is capped.
        assertThat(WebhookDeliverer.nextDelay(1, Duration.ofDays(7))).isEqualTo(Duration.ofHours(12));
        assertThat(WebhookDeliverer.retryAfter("Wed, 21 Oct 2015 07:28:00 GMT")).isEqualTo(Duration.ZERO);
        assertThat(WebhookDeliverer.retryAfter("bientôt")).isNull();
    }

    /** Vert.x writes the request line into its timeout message: none of it may reach the journal. */
    @Test
    void aNetworkErrorIsAFixedSentenceThatQuotesNothing() {
        String path = "/services/T000/B000/SECRET";
        assertThat(WebhookDeliverer.networkError(
                        new ExecutionException(new io.vertx.core.impl.NoStackTraceTimeoutException(
                                "The timeout period of 10000ms has been exceeded while executing POST " + path))))
                .startsWith("Délai dépassé")
                .doesNotContain("SECRET");
        assertThat(WebhookDeliverer.networkError(new TimeoutException())).startsWith("Délai dépassé");
        assertThat(WebhookDeliverer.networkError(new ExecutionException(new ConnectException("refused " + path))))
                .isEqualTo("Connexion refusée par le récepteur.");
        assertThat(WebhookDeliverer.networkError(new ExecutionException(new SSLHandshakeException("bad " + path))))
                .startsWith("Échec TLS");
        assertThat(WebhookDeliverer.networkError(new ExecutionException(new IllegalStateException(path))))
                .isEqualTo("Erreur réseau (IllegalStateException).");
    }
}
