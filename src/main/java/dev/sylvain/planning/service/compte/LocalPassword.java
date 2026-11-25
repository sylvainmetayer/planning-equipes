package dev.sylvain.planning.service.compte;

import dev.sylvain.planning.service.BusinessError;
import io.quarkus.elytron.security.common.BcryptUtil;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;

/**
 * The password of a local account: its policy, and its bcrypt hash.
 *
 * <p>The policy is a length and nothing else — no mandatory digit or capital,
 * which only teaches people to write {@code Password1!}. The ceiling is bcrypt's
 * own: it reads 72 bytes and silently ignores the rest, so a longer password
 * would be refused rather than quietly truncated.</p>
 */
public final class LocalPassword {

    public static final int LONGUEUR_MIN = 12;

    /** Bytes, not characters: that is what bcrypt reads. */
    public static final int OCTETS_MAX = 72;

    /** About a quarter of a second: slow enough for an attacker holding the table, invisible to a person. */
    private static final int COUT = 12;

    /** A hash of nothing anybody knows, checked when the account does not exist so that both cases take as long. */
    private static final String FACTICE = BcryptUtil.bcryptHash("compte-inexistant-sans-mot-de-passe", COUT);

    private LocalPassword() {}

    /** NFC, so « é » typed as one character or as two is one password, on every device. */
    private static String nfc(String password) {
        return password == null ? null : Normalizer.normalize(password, Normalizer.Form.NFC);
    }

    public static void check(String motDePasse) {
        motDePasse = nfc(motDePasse);
        if (motDePasse == null || motDePasse.codePointCount(0, motDePasse.length()) < LONGUEUR_MIN) {
            throw new BusinessError.Invalid("Le mot de passe doit faire au moins " + LONGUEUR_MIN + " caractères.");
        }
        if (motDePasse.getBytes(StandardCharsets.UTF_8).length > OCTETS_MAX) {
            throw new BusinessError.Invalid("Le mot de passe est trop long (" + OCTETS_MAX
                    + " octets au plus, soit environ " + OCTETS_MAX + " caractères sans accent).");
        }
    }

    public static String hash(String motDePasse) {
        return BcryptUtil.bcryptHash(nfc(motDePasse), COUT);
    }

    /** {@code hash} is {@code null} for an account that has none, or that does not exist: the cost is paid anyway. */
    public static boolean matches(String motDePasse, String hash) {
        motDePasse = nfc(motDePasse);
        if (motDePasse == null || motDePasse.getBytes(StandardCharsets.UTF_8).length > OCTETS_MAX) {
            return false;
        }
        boolean bon = BcryptUtil.matches(motDePasse, hash == null ? FACTICE : hash);
        return bon && hash != null;
    }
}
