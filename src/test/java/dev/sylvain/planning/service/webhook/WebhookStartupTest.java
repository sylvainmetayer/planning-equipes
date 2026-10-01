package dev.sylvain.planning.service.webhook;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.sylvain.planning.config.TrustedProxies;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The boot refuses webhooks whose secrets the key cannot open — missing, or
 * another one than the key that sealed them: they would look configured and
 * send nothing.
 */
class WebhookStartupTest {

    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);

    private static final String OTHER_KEY = "HxwbGhkYFxYVFBMSERAPDg0MCwoJCAcGBQQDAgEAAAA=";

    /** {@code storedSecret} empty: no webhook at all. */
    private static WebhookService service(Optional<String> storedSecret, Optional<String> key) {
        WebhookRepository repository = new WebhookRepository(null) {
            @Override
            public boolean any() {
                return storedSecret.isPresent();
            }

            @Override
            public Optional<String> anySecret() {
                return storedSecret;
            }
        };
        return new WebhookService(
                repository,
                new SecretCipher(key),
                new OutboundGuard(TrustedProxies.of(List.of()), host -> new java.net.InetAddress[0]),
                null,
                null,
                null,
                null);
    }

    private static Optional<String> sealed() {
        return Optional.of(new SecretCipher(Optional.of(KEY)).encrypt("https://hooks.example.org/services/T0-PRIVE"));
    }

    @Test
    void aWebhookWithoutTheKeyStopsTheBoot() {
        assertThatThrownBy(() -> service(sealed(), Optional.empty()).checkAtStartup(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("WEBHOOKS_SECRET_KEY");
    }

    /** One secret is tried: a key opens all of them or none. */
    @Test
    void aWebhookUnderAnotherKeyStopsTheBootToo() {
        assertThatThrownBy(() -> service(sealed(), Optional.of(OTHER_KEY)).checkAtStartup(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("n'ouvre pas")
                .hasMessageNotContaining("T0-PRIVE");
    }

    @Test
    void noWebhookNeedsNoKey() {
        assertThatCode(() -> service(Optional.empty(), Optional.empty()).checkAtStartup(null))
                .doesNotThrowAnyException();
        assertThatCode(() -> service(sealed(), Optional.of(KEY)).checkAtStartup(null))
                .doesNotThrowAnyException();
    }
}
