package dev.sylvain.planning.keycloak;

import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import java.security.SecureRandom;
import java.util.Objects;
import java.util.Set;
import org.keycloak.authentication.AuthenticationFlowContext;
import org.keycloak.authentication.AuthenticationFlowError;
import org.keycloak.authentication.Authenticator;
import org.keycloak.email.EmailException;
import org.keycloak.email.EmailSenderProvider;
import org.keycloak.events.Errors;
import org.keycloak.models.AuthenticatorConfigModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.services.managers.BruteForceProtector;
import org.keycloak.sessions.AuthenticationSessionModel;

/**
 * Signs a person in with a code sent to the address their account already
 * carries — the one method of the four this realm offers that Keycloak has no
 * authenticator for.
 *
 * <p><b>Skeleton.</b> The flow is complete and the guards below are the ones
 * that matter, but two things are deliberately left plain and are called out
 * in {@code docs/keycloak.md}: the code lives in the authentication session
 * rather than in a store shared across nodes, and the message is sent through
 * Keycloak's plain-text sender rather than a theme template. Each is fine for
 * one server and a realm imported from a file; the first is not fine for
 * several replicas behind a load balancer.</p>
 *
 * <p><b>Bounds.</b> A page rendered again keeps its code and its count of wrong
 * tries; a new code is drawn only when none stands, and at most once a minute
 * per account; every wrong code is also reported to the realm's brute-force
 * detection, which locks the account temporarily, so that starting the login
 * over does not wipe the tally.</p>
 *
 * <p>Why an authenticator rather than the "magic link" that circulates in
 * examples: a link in a mailbox is a bearer credential that survives being
 * forwarded, and this application already learned that lesson on the espace
 * link, which is why the espace itself now asks for a Keycloak session. A
 * six-digit code bound to the session that asked for it cannot be
 * replayed from someone else's browser.</p>
 */
public class EmailCodeAuthenticator implements Authenticator {

    private static final String FORM = "code-email.ftl";

    /** Where the expected code is kept, for the lifetime of this login attempt. */
    static final String NOTE_CODE = "planning.code.email";

    /** When that code stops being accepted, as epoch milliseconds. */
    static final String NOTE_EXPIRATION = "planning.code.email.expiration";

    /** How many wrong codes this session has already offered. */
    static final String NOTE_ESSAIS = "planning.code.email.essais";

    static final String CONFIG_VALIDITE_SECONDES = "validiteSecondes";
    static final String CONFIG_ESSAIS_MAX = "essaisMax";

    static final int VALIDITE_SECONDES_DEFAUT = 600;
    static final int ESSAIS_MAX_DEFAUT = 5;

    /**
     * The least time between two codes mailed to one account, whichever login
     * attempts ask for them: held in Keycloak's single-use store, so it
     * survives a fresh session and is shared by every node.
     */
    static final int DELAI_ENTRE_ENVOIS_SECONDES = 60;

    /**
     * The category a wrong code is counted under in the brute-force detection:
     * a one-time code, like the TOTP — one of the three Keycloak accepts.
     */
    private static final Set<String> CATEGORIE_ECHEC = Set.of("otp");

    private static final String CLEF_ENVOI = "planning.code.email.envoi.";

    /**
     * {@link SecureRandom} and not {@code Math.random()}: this value is a
     * credential, and a predictable one is no credential at all.
     */
    private static final SecureRandom ALEA = new SecureRandom();

    @Override
    public void authenticate(AuthenticationFlowContext context) {
        UserModel utilisateur = context.getUser();
        if (utilisateur == null || utilisateur.getEmail() == null || utilisateur.getEmail().isBlank()) {
            // Sans adresse, cette méthode n'a rien à offrir : elle s'efface au
            // lieu d'échouer, pour que les autres alternatives restent jouables.
            context.attempted();
            return;
        }

        BruteForceProtector protecteur = context.getSession().getProvider(BruteForceProtector.class);
        if (protecteur.isTemporarilyDisabled(context.getSession(), context.getRealm(), utilisateur)
                || protecteur.isPermanentlyLockedOut(context.getSession(), context.getRealm(), utilisateur)) {
            // Too many wrong codes (or passwords) on this account: no new code
            // is drawn, and no mail goes out, until the realm's lockout ends.
            context.getEvent().user(utilisateur).error(Errors.USER_TEMPORARILY_DISABLED);
            context.failureChallenge(
                    AuthenticationFlowError.USER_TEMPORARILY_DISABLED,
                    context.form().setError("tooManyAttempts").createErrorPage(Response.Status.FORBIDDEN));
            return;
        }

        AuthenticationSessionModel session = context.getAuthenticationSession();
        if (session.getAuthNote(NOTE_CODE) != null && !expire(session)) {
            // The page was rendered again — a reload, the back button: the code
            // already mailed is still the one expected, and so is its count of
            // wrong tries. Drawing a new one here would hand out five fresh
            // guesses and one more mail per reload.
            context.challenge(context.form().createForm(FORM));
            return;
        }
        if (!context.getSession()
                .singleUseObjects()
                .putIfAbsent(CLEF_ENVOI + utilisateur.getId(), DELAI_ENTRE_ENVOIS_SECONDES)) {
            // A code left for this account a moment ago, from another attempt:
            // the mailbox is not flooded, and this attempt waits.
            context.challenge(context.form().setError("codeDejaEnvoye").createForm(FORM));
            return;
        }

        String code = tireUnCode();
        session.setAuthNote(NOTE_CODE, code);
        session.setAuthNote(
                NOTE_EXPIRATION,
                Long.toString(System.currentTimeMillis() + validiteSecondes(context) * 1000L));
        session.setAuthNote(NOTE_ESSAIS, "0");

        try {
            envoyer(context, utilisateur, code);
        } catch (EmailException erreur) {
            // Le relais est muet : le dire, plutôt que d'attendre un code que
            // personne ne recevra.
            context.failureChallenge(
                    AuthenticationFlowError.INTERNAL_ERROR,
                    context.form()
                            .setError("emailSendError")
                            .createErrorPage(Response.Status.INTERNAL_SERVER_ERROR));
            return;
        }

        context.challenge(context.form().createForm(FORM));
    }

    @Override
    public void action(AuthenticationFlowContext context) {
        MultivaluedMap<String, String> formulaire =
                context.getHttpRequest().getDecodedFormParameters();
        String saisi = formulaire.getFirst("code");
        AuthenticationSessionModel session = context.getAuthenticationSession();

        String attendu = session.getAuthNote(NOTE_CODE);
        if (attendu == null || expire(session)) {
            context.failureChallenge(
                    AuthenticationFlowError.EXPIRED_CODE,
                    context.form().setError("expiredCode").createForm(FORM));
            return;
        }

        int essais = essais(session) + 1;
        session.setAuthNote(NOTE_ESSAIS, Integer.toString(essais));

        // Comparaison à temps constant : le nombre d'essais est borné, mais
        // fuir la longueur du préfixe correct reste une fuite gratuite.
        if (!java.security.MessageDigest.isEqual(
                attendu.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                Objects.requireNonNullElse(saisi, "")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
            // Counted against the account too, so that restarting the login
            // does not wipe the tally: the realm's brute-force detection locks
            // the account after its own number of failures.
            if (context.getUser() != null) {
                context.getSession()
                        .getProvider(BruteForceProtector.class)
                        .failedLogin(
                                context.getRealm(),
                                context.getUser(),
                                context.getConnection(),
                                context.getUriInfo(),
                                CATEGORIE_ECHEC);
            }
            if (essais >= essaisMax(context)) {
                // Le code est brûlé, pas seulement refusé : sans cela, cinq
                // essais par rechargement de page rendraient la borne décorative.
                session.removeAuthNote(NOTE_CODE);
                context.failure(AuthenticationFlowError.ACCESS_DENIED);
                return;
            }
            context.failureChallenge(
                    AuthenticationFlowError.INVALID_CREDENTIALS,
                    context.form().setError("invalidCode").createForm(FORM));
            return;
        }

        session.removeAuthNote(NOTE_CODE);
        // Le bon code est arrivé dans cette boîte : c'est exactement l'aller-
        // retour que prouve « adresse vérifiée ». Sans cela, un compte créé
        // par le provisioning et dont l'invitation n'a pas été suivie se
        // connectait bien, puis l'application refusait l'espace, faute de
        // `email_verified` dans le jeton.
        UserModel utilisateur = context.getUser();
        if (!utilisateur.isEmailVerified()) {
            utilisateur.setEmailVerified(true);
            utilisateur.removeRequiredAction(UserModel.RequiredAction.VERIFY_EMAIL);
        }
        context.success();
    }

    /**
     * Six digits, zero-padded: a million values against five attempts and ten
     * minutes.
     */
    private static String tireUnCode() {
        return String.format("%06d", ALEA.nextInt(1_000_000));
    }

    /**
     * Sent through {@link EmailSenderProvider}, the plain sender, and not
     * through {@link org.keycloak.email.EmailTemplateProvider}.
     *
     * <p>The template provider looks like the friendlier door and is a trap
     * here: its {@code send(String, String, Map)} takes a <b>message key</b>
     * for the subject and a <b>template name</b> for the body, both resolved
     * against the realm's e-mail theme. Handing it the finished sentences —
     * which is what this code did — makes FreeMarker look for a template
     * whose name is the whole message, every send throws {@code
     * EmailException}, and the only thing a person ever sees is
     * {@code emailSendError}. The signature accepts it; the server does not.
     *
     * <p>The plain sender takes the realm's SMTP settings and the finished
     * text, which is exactly what there is to send. Subject and body stay in
     * this file rather than in the theme: an authenticator that ships its own
     * templates would have to ship them for every theme a deployment might
     * choose, and this message is three lines long.
     */
    private void envoyer(AuthenticationFlowContext context, UserModel utilisateur, String code)
            throws EmailException {
        KeycloakSession session = context.getSession();
        RealmModel realm = context.getRealm();
        int minutes = validiteSecondes(context) / 60;

        String texte = "Code de connexion : " + code + "\n\nIl est valable " + minutes
                + " minutes. Si vous n'êtes pas à l'origine de cette demande, ignorez ce message.";

        session.getProvider(EmailSenderProvider.class)
                .send(realm.getSmtpConfig(), utilisateur, "Votre code de connexion", texte, null);
    }

    private static boolean expire(AuthenticationSessionModel session) {
        String limite = session.getAuthNote(NOTE_EXPIRATION);
        return limite == null || System.currentTimeMillis() > Long.parseLong(limite);
    }

    private static int essais(AuthenticationSessionModel session) {
        String compte = session.getAuthNote(NOTE_ESSAIS);
        return compte == null ? 0 : Integer.parseInt(compte);
    }

    private static int validiteSecondes(AuthenticationFlowContext context) {
        return entierConfigure(context, CONFIG_VALIDITE_SECONDES, VALIDITE_SECONDES_DEFAUT);
    }

    private static int essaisMax(AuthenticationFlowContext context) {
        return entierConfigure(context, CONFIG_ESSAIS_MAX, ESSAIS_MAX_DEFAUT);
    }

    private static int entierConfigure(AuthenticationFlowContext context, String clef, int defaut) {
        AuthenticatorConfigModel config = context.getAuthenticatorConfig();
        if (config == null || config.getConfig() == null) {
            return defaut;
        }
        String valeur = config.getConfig().get(clef);
        if (valeur == null || valeur.isBlank()) {
            return defaut;
        }
        try {
            return Integer.parseInt(valeur.trim());
        } catch (NumberFormatException malFormee) {
            // Une configuration illisible ne doit pas fermer la connexion à
            // tout le monde : le défaut reprend la main.
            return defaut;
        }
    }

    /**
     * The address is the account's own, so there is nothing for the person to
     * set up beforehand — which is exactly what makes this method the fallback
     * when a passkey is lost and no recovery code is left.
     */
    @Override
    public boolean configuredFor(KeycloakSession session, RealmModel realm, UserModel user) {
        return user.getEmail() != null && !user.getEmail().isBlank();
    }

    @Override
    public boolean requiresUser() {
        return true;
    }

    @Override
    public void setRequiredActions(KeycloakSession session, RealmModel realm, UserModel user) {
        // Rien à enrôler : c'est tout l'intérêt de cette méthode.
    }

    @Override
    public void close() {
        // Sans état propre à libérer.
    }
}
