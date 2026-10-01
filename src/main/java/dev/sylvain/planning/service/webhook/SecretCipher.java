package dev.sylvain.planning.service.webhook;

import dev.sylvain.planning.config.ConfigWebhooks;
import dev.sylvain.planning.service.BusinessError;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Optional;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Encrypts the secrets of the webhooks at rest — the HMAC key, a Slack,
 * Discord or Matrix address, a Telegram bot token — with AES-256-GCM.
 *
 * <p>The export SQL already leaves the webhook tables out; the nightly
 * {@code pg_dump} does not, it takes the whole cluster. Encrypting here is what
 * makes « a backup holds only ciphertext » true: the key lives in the
 * environment ({@code WEBHOOKS_SECRET_KEY}), never in the database it
 * protects.</p>
 *
 * <p>The stored form is {@code v1:<base64(nonce ‖ ciphertext ‖ tag)>}, a
 * fresh 96-bit nonce per value. The version prefix leaves room for a key
 * rotation that would have to read both forms; there is none today.</p>
 */
@ApplicationScoped
public class SecretCipher {

    private static final String PREFIX = "v1:";

    private static final int NONCE_BYTES = 12;

    private static final int TAG_BITS = 128;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final Optional<SecretKey> key;

    @Inject
    public SecretCipher(ConfigWebhooks config) {
        this(config.secretKey());
    }

    /**
     * @throws IllegalStateException on a key that is not 32 bytes of base64:
     *         read at startup, so a typo fails the boot instead of the first
     *         webhook somebody saves
     */
    SecretCipher(Optional<String> base64Key) {
        this.key = base64Key.map(String::trim).filter(value -> !value.isEmpty()).map(SecretCipher::parse);
    }

    private static SecretKey parse(String base64) {
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(base64);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("WEBHOOKS_SECRET_KEY n'est pas du base64 valide.", e);
        }
        if (bytes.length != 32) {
            throw new IllegalStateException("WEBHOOKS_SECRET_KEY doit faire 32 octets (256 bits) une fois décodée, pas "
                    + bytes.length + " : générez-la avec « openssl rand -base64 32 ».");
        }
        return new SecretKeySpec(bytes, "AES");
    }

    /** Whether a key is configured — without it nothing can be stored, and nothing stored can be read. */
    public boolean available() {
        return key.isPresent();
    }

    /**
     * @throws BusinessError.Conflict without a key: storing a secret in clear
     *         is precisely what this class exists to rule out
     */
    public String encrypt(String plain) {
        SecretKey cle = requireKey();
        byte[] nonce = new byte[NONCE_BYTES];
        RANDOM.nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, cle, new GCMParameterSpec(TAG_BITS, nonce));
            byte[] sealed = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            return PREFIX
                    + Base64.getEncoder()
                            .encodeToString(ByteBuffer.allocate(nonce.length + sealed.length)
                                    .put(nonce)
                                    .put(sealed)
                                    .array());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("A webhook secret could not be encrypted", e);
        }
    }

    /**
     * @throws IllegalStateException without a key, or on a value the key does
     *         not open — another instance's key, or a tampered row. The
     *         message never quotes the value.
     */
    public String decrypt(String stored) {
        SecretKey cle = key.orElseThrow(() -> new IllegalStateException("WEBHOOKS_SECRET_KEY n'est pas configurée."));
        if (stored == null || !stored.startsWith(PREFIX)) {
            throw new IllegalStateException("Secret de webhook illisible : format inconnu.");
        }
        try {
            byte[] all = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, cle, new GCMParameterSpec(TAG_BITS, all, 0, NONCE_BYTES));
            return new String(cipher.doFinal(all, NONCE_BYTES, all.length - NONCE_BYTES), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException(
                    "Secret de webhook illisible avec la clé WEBHOOKS_SECRET_KEY actuelle (clé changée ?).", e);
        }
    }

    private SecretKey requireKey() {
        return key.orElseThrow(() -> new BusinessError.Conflict(
                "Les secrets des webhooks sont chiffrés au repos, et la clé WEBHOOKS_SECRET_KEY n'est pas configurée "
                        + "sur cette instance : demandez à l'hébergeur de la définir (openssl rand -base64 32), "
                        + "puis réessayez."));
    }

    /** A fresh HMAC secret: 32 random bytes, URL-safe base64, shown once. */
    public static String newSecret() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
