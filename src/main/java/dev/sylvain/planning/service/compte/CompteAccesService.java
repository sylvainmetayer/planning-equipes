package dev.sylvain.planning.service.compte;

import dev.sylvain.planning.config.ConfigAdminLogin;
import dev.sylvain.planning.config.ConfigComptesLocaux;
import dev.sylvain.planning.service.BusinessError;
import dev.sylvain.planning.service.FailureLockout;
import dev.sylvain.planning.service.RateLimitVerdict;
import dev.sylvain.planning.service.Sha256;
import dev.sylvain.planning.service.espace.ApplicationLinks;
import dev.sylvain.planning.service.espace.CodeRequestLimiter;
import dev.sylvain.planning.service.publication.MailService;
import io.quarkus.logging.Log;
import io.quarkus.runtime.LaunchMode;
import io.quarkus.runtime.StartupEvent;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
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

    private final CompteAccesRepository acces;
    private final CompteRepository comptes;
    private final MailService mail;
    private final ApplicationLinks liens;
    private final ConfigComptesLocaux config;
    private final CodeRequestLimiter limiteur;
    private final ConfigAdminLogin adminLogin;

    @Inject
    public CompteAccesService(
            CompteAccesRepository acces,
            CompteRepository comptes,
            MailService mail,
            ApplicationLinks liens,
            ConfigComptesLocaux config,
            CodeRequestLimiter limiteur,
            ConfigAdminLogin adminLogin) {
        this.acces = acces;
        this.comptes = comptes;
        this.mail = mail;
        this.liens = liens;
        this.config = config;
        this.limiteur = limiteur;
        this.adminLogin = adminLogin;
    }

    /**
     * Failures of the password step <b>per account address</b> (existing or not:
     * the key must not tell), beside the per-address lock of the form: a spray
     * from many addresses at one account is stopped here, and a success on
     * another account does not wipe it.
     */
    private final FailureLockout accountFailures = new FailureLockout(1_000);

    /** « Forgot my password » requests handed to the pool and not over yet. */
    private final AtomicInteger pendingForgot = new AtomicInteger();

    private static final int MAX_PENDING_FORGOT = 32;

    void warnIfLinksPointToLocalhost(@Observes StartupEvent startup) {
        if (LaunchMode.current() == LaunchMode.NORMAL && liens.pointsToLocalhost()) {
            Log.warn("PUBLIC_URL points at localhost: the password links and the sign-in pages mailed to "
                    + "accounts open nothing for their readers, and none is sent. Set PUBLIC_URL.");
        }
    }

    /** What the password step answers: whether a code is now waited for, and where it went. */
    @Schema(
            name = "SignInChallenge",
            requiredProperties = {"codeRequired", "challenge"})
    public record Challenge(boolean codeRequired, String challenge, String maskedEmail) {}

    /** Too many codes asked without using one. */
    public static class TooManyRequests extends RuntimeException {

        private final long secondsBeforeNextTry;

        TooManyRequests(String what, long secondsBeforeNextTry) {
            super("Trop de " + what + " : réessayez dans " + Math.max(1, (secondsBeforeNextTry + 59) / 60)
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
        if (!liens.disponible() || localhostRefused()) {
            throw new BusinessError.Invalid("L'adresse publique de l'application n'est pas configurée "
                    + "(PUBLIC_URL) : le lien ne peut pas être écrit.");
        }
        try {
            emit(compte.id(), compte.email(), compte.nom(), invitation);
        } catch (RuntimeException e) {
            Log.warnf(
                    "A password link could not be sent to account %s: %s",
                    compte.id(), e.getClass().getSimpleName());
            throw new BusinessError.Conflict("Le mail n'a pas pu être envoyé : vérifiez la messagerie de "
                    + "l'instance, puis renvoyez le lien depuis la ligne du compte.");
        }
    }

    /** A link to localhost is refused outside dev and test: nobody who reads the mail can open it. */
    private boolean localhostRefused() {
        return LaunchMode.current() == LaunchMode.NORMAL && liens.pointsToLocalhost();
    }

    /**
     * « Mot de passe oublié » from the sign-in page: returns at once and does
     * the rest — the lookup, the link, the SMTP exchange — on the worker pool,
     * so that the time it takes never tells whether an address has an account.
     * A full queue drops the request silently, like any other refusal.
     */
    public void forgotAsync(String email) {
        if (pendingForgot.incrementAndGet() > MAX_PENDING_FORGOT) {
            pendingForgot.decrementAndGet();
            return;
        }
        try {
            Infrastructure.getDefaultWorkerPool().execute(() -> {
                try {
                    forgot(email);
                } catch (RuntimeException e) {
                    Log.warnf(
                            "A password reset request failed: %s", e.getClass().getSimpleName());
                } finally {
                    pendingForgot.decrementAndGet();
                }
            });
        } catch (RuntimeException e) {
            pendingForgot.decrementAndGet();
        }
    }

    /**
     * The work of {@link #forgotAsync}. Quiet on a refusal, an unknown address,
     * a deactivated account, a link still in force (asking again never cancels
     * an invitation or a reset the person has not used yet) and a mail that
     * failed: a failure is logged by account id, never by address.
     */
    void forgot(String email) {
        String cle = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
        Optional<CompteAccesRepository.Credentials> compte = cle.isEmpty() ? Optional.empty() : acces.credentials(cle);
        if (compte.isEmpty() || !compte.get().active() || !liens.disponible() || localhostRefused()) {
            return;
        }
        CompteAccesRepository.Credentials c = compte.get();
        if (acces.hasLiveLink(c.id()) || !limiteur.request("oubli/" + c.id()).autorise()) {
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

    /**
     * Uses a link to set the password. The password is checked <b>before</b>
     * the link is spent, and spending and setting are one transaction. Setting
     * a password closes every session opened with the previous one (the epoch
     * moves) and tells the owner by mail.
     */
    public void choose(String token, String password) {
        LocalPassword.check(password);
        if (token == null || token.isBlank()) {
            throw new BusinessError.Invalid(INVALID_LINK);
        }
        String compteId = acces.useLink(Sha256.hex(token.trim()), LocalPassword.hash(password))
                .orElseThrow(() -> new BusinessError.Invalid(INVALID_LINK));
        comptes.findById(compteId).ifPresent(c -> {
            try {
                mail.sendPasswordChanged(c.email(), c.nom());
            } catch (RuntimeException e) {
                Log.warnf(
                        "The password change notice could not be sent to account %s: %s",
                        c.id(), e.getClass().getSimpleName());
            }
        });
    }

    /**
     * The password step. Empty when the address and the password do not open
     * an account (and the same time was spent); else the challenge, with its
     * code on the way when the second step is on.
     *
     * @throws TooManyRequests when codes pile up unused for this account
     */
    public Optional<Challenge> open(String email, String password) {
        String key = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
        long wait = accountFailures.lockoutSeconds(key, adminLogin.maxEchecs(), adminLogin.dureeBlocage());
        if (wait > 0) {
            throw new TooManyRequests("tentatives de connexion sur ce compte", wait);
        }
        Optional<CompteAccesRepository.Credentials> compte = key.isEmpty() ? Optional.empty() : acces.credentials(key);
        boolean bon = LocalPassword.matches(
                password,
                compte.map(CompteAccesRepository.Credentials::passwordHash).orElse(null));
        if (!bon || !compte.get().active()) {
            accountFailures.recordFailure(key, adminLogin.dureeBlocage());
            return Optional.empty();
        }
        accountFailures.clear(key);
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
            throw new TooManyRequests("codes demandés", verdict.secondsBeforeNextTry());
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

    /**
     * What a local session's cookie carries as its principal: the address and
     * the epoch of the account's sessions ({@code address#epoch}). A new
     * password moves the epoch, and every cookie opened before it stops
     * naming anyone.
     */
    public String sessionPrincipal(Compte compte) {
        return compte.email() + "#" + acces.sessionEpoch(compte.id());
    }

    /** The account a session cookie names, if it is still active and the password has not changed since. */
    public Optional<Compte> activeSession(String principal) {
        int hash = principal.lastIndexOf('#');
        if (hash < 0) {
            return Optional.empty();
        }
        Optional<Compte> compte =
                comptes.findByEmail(principal.substring(0, hash)).filter(CompteService::isActive);
        return compte.filter(c -> sessionPrincipal(c).equals(principal));
    }

    /** The address inside a local session's principal, for display. */
    public static String addressOf(String principal) {
        int hash = principal.lastIndexOf('#');
        return hash < 0 ? principal : principal.substring(0, hash);
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
