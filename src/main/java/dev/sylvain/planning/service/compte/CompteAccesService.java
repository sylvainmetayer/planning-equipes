package dev.sylvain.planning.service.compte;

import dev.sylvain.planning.config.ConfigComptesLocaux;
import dev.sylvain.planning.service.BusinessError;
import dev.sylvain.planning.service.RateLimitVerdict;
import dev.sylvain.planning.service.Sha256;
import dev.sylvain.planning.service.espace.ApplicationLinks;
import dev.sylvain.planning.service.espace.CodeRequestLimiter;
import dev.sylvain.planning.service.publication.MailService;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Signing in to a local account, and choosing its password (ADR 0094).
 *
 * <ul>
 *   <li><b>A link</b> (invitation 72 h, reset 1 h) is a random 256-bit token
 *       mailed to the account's address; only its SHA-256 is stored, and using
 *       it deletes it. The administrator who triggers one never sees it.</li>
 *   <li><b>A sign-in</b> is two moves. The password buys a <i>challenge</i>
 *       (single-use, ten minutes) and, unless the operator turned the second
 *       step off, mails a six-digit code. The session itself is opened by the
 *       form mechanism, which exchanges the challenge (and the code) through
 *       {@link #redeem} — so the lock, the journal and the cookie are the ones
 *       the break-glass account already has.</li>
 * </ul>
 *
 * <p>Nothing here ever says whether an address has an account: a wrong address,
 * a wrong password, a deactivated account and an account without password
 * answer the same, after the same bcrypt computation.</p>
 */
@ApplicationScoped
public class CompteAccesService {

    public static final Duration INVITATION_VALIDITY = Duration.ofHours(72);
    public static final Duration RESET_VALIDITY = Duration.ofHours(1);
    static final Duration CHALLENGE_VALIDITY = Duration.ofMinutes(10);
    static final int CODE_ATTEMPTS = 5;

    /** A challenge as handed out: 32 random bytes, base64url, so 43 characters. */
    private static final Pattern CHALLENGE_SHAPE = Pattern.compile("[A-Za-z0-9_-]{43}");

    private static final String INVALID_LINK = "Ce lien n'est pas valable : il a expiré ou il a déjà servi. "
            + "Demandez-en un nouveau depuis la page de connexion.";

    private final SecureRandom random = new SecureRandom();

    @Inject
    CompteAccesRepository acces;

    @Inject
    CompteRepository comptes;

    @Inject
    MailService mail;

    @Inject
    ApplicationLinks liens;

    @Inject
    ConfigComptesLocaux config;

    @Inject
    CodeRequestLimiter limiteur;

    /** What the password step answers: whether a code is now waited for, and where it went. */
    @Schema(
            name = "SignInChallenge",
            requiredProperties = {"codeRequired", "challenge"})
    public record Challenge(boolean codeRequired, String challenge, String maskedEmail) {}

    /** Too many codes asked without using one. */
    public static class TooManyRequests extends RuntimeException {

        private final long secondsBeforeNextTry;

        TooManyRequests(long secondsBeforeNextTry) {
            super("Trop de codes demandés : réessayez dans " + Math.max(1, (secondsBeforeNextTry + 59) / 60)
                    + " minute(s).");
            this.secondsBeforeNextTry = secondsBeforeNextTry;
        }

        public long secondsBeforeNextTry() {
            return secondsBeforeNextTry;
        }
    }

    /**
     * Mails an account the link to choose its password, on an administrator's
     * request. The failure propagates: the administrator is told it did not
     * leave. {@code invitation} picks the wording and the lifetime.
     */
    public void sendLink(String compteId, boolean invitation) {
        Compte compte = comptes.findById(compteId)
                .orElseThrow(() -> new BusinessError.NotFound("Compte inconnu : " + compteId));
        if (!CompteService.isActive(compte)) {
            throw new BusinessError.Conflict("Ce compte est désactivé : réactivez-le d'abord.");
        }
        if (!liens.disponible()) {
            throw new BusinessError.Invalid("L'adresse publique de l'application n'est pas configurée "
                    + "(PUBLIC_URL) : le lien ne peut pas être écrit.");
        }
        emit(compte.id(), compte.email(), compte.nom(), invitation);
    }

    /**
     * « Mot de passe oublié » from the sign-in page. Always returns quietly: a
     * refusal, an unknown address, a deactivated account, a mail that failed
     * all look the same from outside — a failure is logged by account id, never
     * by address.
     */
    public void forgot(String email) {
        String cle = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
        Optional<CompteAccesRepository.Credentials> compte = cle.isEmpty() ? Optional.empty() : acces.credentials(cle);
        if (compte.isEmpty() || !compte.get().active() || !liens.disponible()) {
            return;
        }
        CompteAccesRepository.Credentials c = compte.get();
        if (!limiteur.request("oubli/" + c.id()).autorise()) {
            return;
        }
        try {
            emit(c.id(), c.email(), c.nom(), false);
        } catch (RuntimeException e) {
            Log.warnf(
                    "A password reset link could not be sent to account %s: %s",
                    c.id(), e.getClass().getSimpleName());
        }
    }

    /** Uses a link to set the password. The password is checked <b>before</b> the link is spent. */
    public void choose(String token, String password) {
        LocalPassword.check(password);
        if (token == null || token.isBlank()) {
            throw new BusinessError.Invalid(INVALID_LINK);
        }
        String compteId =
                acces.consumeLink(Sha256.hex(token.trim())).orElseThrow(() -> new BusinessError.Invalid(INVALID_LINK));
        if (comptes.findById(compteId).filter(CompteService::isActive).isEmpty()) {
            throw new BusinessError.Invalid(INVALID_LINK);
        }
        acces.definePassword(compteId, LocalPassword.hash(password));
    }

    /**
     * The password step. Empty when the address and the password do not open
     * an account (and the same time was spent); else the challenge, with its
     * code on the way when the second step is on.
     *
     * @throws TooManyRequests when codes pile up unused for this account
     */
    public Optional<Challenge> open(String email, String password) {
        Optional<CompteAccesRepository.Credentials> compte =
                email == null || email.isBlank() ? Optional.empty() : acces.credentials(email.trim());
        boolean bon = LocalPassword.matches(
                password,
                compte.map(CompteAccesRepository.Credentials::passwordHash).orElse(null));
        if (!bon || !compte.get().active()) {
            return Optional.empty();
        }
        CompteAccesRepository.Credentials c = compte.get();
        String challenge = token();
        if (!config.secondeEtape()) {
            acces.replaceChallenge(
                    c.id(),
                    Sha256.hex(challenge),
                    null,
                    CODE_ATTEMPTS,
                    Instant.now().plus(CHALLENGE_VALIDITY));
            return Optional.of(new Challenge(false, challenge, null));
        }
        RateLimitVerdict verdict = limiteur.request("connexion/" + c.id());
        if (!verdict.autorise()) {
            throw new TooManyRequests(verdict.secondsBeforeNextTry());
        }
        String code = String.format("%06d", random.nextInt(1_000_000));
        acces.replaceChallenge(
                c.id(),
                Sha256.hex(challenge),
                Sha256.hex(code + challenge),
                CODE_ATTEMPTS,
                Instant.now().plus(CHALLENGE_VALIDITY));
        try {
            mail.sendAccountCode(c.email(), c.nom(), code);
        } catch (RuntimeException e) {
            Log.warnf(
                    "A sign-in code could not be sent to account %s: %s",
                    c.id(), e.getClass().getSimpleName());
            throw new BusinessError.Conflict("Le code de connexion n'a pas pu être envoyé par e-mail : "
                    + "réessayez, ou prévenez l'administrateur de l'instance.");
        }
        return Optional.of(new Challenge(true, challenge, mask(c.email())));
    }

    /**
     * The form mechanism's side: exchanges {@code defi} or {@code defi:code}
     * for the account it was issued to, once. Empty on anything else — which
     * includes the {@code admin} password of the configuration, that the next
     * provider then reads.
     */
    public Optional<Compte> redeem(String email, String secret) {
        if (email == null || secret == null) {
            return Optional.empty();
        }
        int deuxPoints = secret.indexOf(':');
        String challenge = deuxPoints < 0 ? secret : secret.substring(0, deuxPoints);
        String code = deuxPoints < 0 ? null : secret.substring(deuxPoints + 1).trim();
        if (!CHALLENGE_SHAPE.matcher(challenge).matches()) {
            return Optional.empty();
        }
        Optional<String> compteId = acces.redeemChallenge(
                Sha256.hex(challenge), code == null || code.isEmpty() ? null : Sha256.hex(code + challenge));
        Optional<Compte> compte = compteId.flatMap(comptes::findById)
                .filter(CompteService::isActive)
                .filter(c -> c.email().equalsIgnoreCase(email.trim()));
        compte.ifPresent(c -> {
            limiteur.oublier("connexion/" + c.id());
            comptes.touchSignIn(c.id());
        });
        return compte;
    }

    /** The account a session cookie names, if it is still active. */
    public Optional<Compte> activeByEmail(String email) {
        return comptes.findByEmail(email).filter(CompteService::isActive);
    }

    private void emit(String compteId, String email, String nom, boolean invitation) {
        String token = token();
        Duration validity = invitation ? INVITATION_VALIDITY : RESET_VALIDITY;
        acces.replaceLink(
                compteId,
                Sha256.hex(token),
                invitation ? "INVITATION" : "REINITIALISATION",
                Instant.now().plus(validity));
        mail.sendAccountLink(email, nom, liens.passwordScreen(token).orElseThrow(), duree(validity), invitation);
    }

    private String token() {
        byte[] brut = new byte[32];
        random.nextBytes(brut);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(brut);
    }

    private static String duree(Duration validity) {
        return validity.toHours() >= 2 ? validity.toHours() + " heures" : "une heure";
    }

    /** {@code a•••@example.org} — enough to recognise one's address, nothing more. */
    static String mask(String email) {
        int arobase = email.indexOf('@');
        return arobase <= 0 ? "•••" : email.charAt(0) + "•••" + email.substring(arobase);
    }
}
