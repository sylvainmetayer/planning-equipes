package dev.sylvain.planning.service.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.sylvain.planning.service.BusinessError;
import java.util.Base64;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SecretCipherTest {

    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);

    private static final String OTHER_KEY =
            Base64.getEncoder().encodeToString("another-key-of-32-bytes-exactly!".getBytes());

    @Test
    void whatIsEncryptedIsReadBackAndNeverStoredInClear() {
        SecretCipher cipher = new SecretCipher(Optional.of(KEY));

        String stored = cipher.encrypt("https://hooks.slack.com/services/T0/B0/XYZ");

        assertThat(stored).startsWith("v1:").doesNotContain("hooks.slack.com");
        assertThat(cipher.decrypt(stored)).isEqualTo("https://hooks.slack.com/services/T0/B0/XYZ");
        // A fresh nonce each time: two encryptions of one value never match.
        assertThat(cipher.encrypt("same")).isNotEqualTo(cipher.encrypt("same"));
    }

    @Test
    void anotherKeyCannotOpenIt() {
        String stored = new SecretCipher(Optional.of(KEY)).encrypt("secret");

        assertThatThrownBy(() -> new SecretCipher(Optional.of(OTHER_KEY)).decrypt(stored))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining("secret");
    }

    @Test
    void withoutAKeyNothingIsStoredAndTheRefusalSaysWhatToDo() {
        SecretCipher cipher = new SecretCipher(Optional.empty());

        assertThat(cipher.available()).isFalse();
        assertThatThrownBy(() -> cipher.encrypt("secret"))
                .isInstanceOf(BusinessError.Conflict.class)
                .hasMessageContaining("WEBHOOKS_SECRET_KEY");
    }

    @Test
    void aKeyOfTheWrongSizeFailsAtOnce() {
        String court = Base64.getEncoder().encodeToString(new byte[16]);

        assertThatThrownBy(() -> new SecretCipher(Optional.of(court)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32 octets");
        assertThatThrownBy(() -> new SecretCipher(Optional.of("pas du base64 !")))
                .isInstanceOf(IllegalStateException.class);
    }
}
