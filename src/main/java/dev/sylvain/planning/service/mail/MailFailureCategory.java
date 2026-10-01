package dev.sylvain.planning.service.mail;

import io.vertx.ext.mail.SMTPException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.net.ssl.SSLException;

/**
 * Why a mail could not leave, as one of five short codes an organiser can act
 * on — never the mail server's own sentence, which routinely quotes the
 * address it refused.
 *
 * <p>Read off the exception the mailer threw, in that order: a network
 * failure on the way to the relay, then the SMTP reply code — the one the
 * Vert.x client carries on {@link SMTPException}, or the three digits a
 * message of another client starts with. The enhanced status ({@code 5.1.1})
 * refines a reply code that says too little on its own.</p>
 *
 * <p>Only what the relay answers <b>immediately</b> can be read here. A relay
 * that accepts the recipient and bounces later sends its notice to
 * {@code MAIL_FROM}, which nothing reads yet — see ADR 0073.</p>
 */
public enum MailFailureCategory {

    /** The relay could not be reached: refused connection, unknown host, timeout, failed TLS handshake. */
    RELAIS_INJOIGNABLE,

    /**
     * The relay refused the application's credentials (535, 530, 5.7.8), or
     * refused to relay for lack of them (5.7.x naming authentication).
     */
    AUTHENTIFICATION,

    /**
     * The relay refused the recipient for good: the address is wrong or no
     * longer exists (5.1.x; 550, 551, 553 when no enhanced status says
     * otherwise). The one category a reminder does not insist on until the
     * fiche is edited.
     */
    ADRESSE_REFUSEE,

    /**
     * A failure the relay itself calls temporary (every 4xx), or a full
     * mailbox: the next send may well go through, so nothing holds it back.
     */
    TEMPORAIRE,

    /**
     * Anything else: a refusal the codes above do not describe — the sender
     * or the content refused (5.7.x) —, or a failure before the relay was
     * asked.
     */
    AUTRE;

    /**
     * A reply code at the head of a message or of one of its lines, or after a
     * blank: « 550 5.1.1 … », « …: 421 … ». Always followed by a blank or a
     * dash, as SMTP writes it — never preceded by a colon, so the port of a
     * {@code host:465} quoted by a network failure is not read as a reply.
     */
    private static final Pattern REPLY_CODE = Pattern.compile("(?:^|\\s)([245]\\d\\d)(?=[\\s-])");

    /** An enhanced status code, RFC 3463: class.subject.detail. */
    private static final Pattern ENHANCED_STATUS = Pattern.compile("\\b([245])\\.(\\d{1,3})\\.(\\d{1,3})\\b");

    /** Reply codes that only ever mean refused credentials. */
    private static final Set<Integer> AUTHENTICATION_REPLIES = Set.of(530, 534, 535, 538);

    /** Reply codes that name the recipient, when no enhanced status says more. */
    private static final Set<Integer> RECIPIENT_REPLIES = Set.of(550, 551, 553);

    /** Deep enough for any wrapping a mailer does, short enough to stop a pathological chain. */
    private static final int MAX_CHAIN = 16;

    /**
     * Classifies a failed send. Pure: the same exception always gives the same
     * category, and nothing is read but the exception chain.
     *
     * <p>The types are read before any message, over the whole chain: the
     * blocking mailer wraps the Vert.x failure in a {@code CompletionException}
     * whose message is its cause's {@code toString()}, host and port included,
     * and a message is only ever a fallback for a client whose exceptions say
     * nothing structured.</p>
     */
    public static MailFailureCategory of(Throwable failure) {
        List<Throwable> chain = chain(failure);
        for (Throwable current : chain) {
            if (isNetwork(current)) {
                return RELAIS_INJOIGNABLE;
            }
            if (current instanceof SMTPException smtp && smtp.getReplyCode() > 0) {
                return ofReply(smtp.getReplyCode(), smtp.getMessage());
            }
        }
        for (Throwable current : chain) {
            MailFailureCategory category = ofMessage(current.getMessage());
            if (category != null) {
                return category;
            }
        }
        return AUTRE;
    }

    /**
     * What a bare message says, {@code null} when it says nothing: a reply
     * code, or a sentence about authentication — which is also how
     * {@code jakarta.mail}'s {@code AuthenticationFailedException} reads, a
     * client this application does not ship.
     */
    private static MailFailureCategory ofMessage(String message) {
        if (message == null) {
            return null;
        }
        Matcher code = REPLY_CODE.matcher(message);
        if (code.find()) {
            return ofReply(Integer.parseInt(code.group(1)), message);
        }
        return mentionsAuthentication(message) ? AUTHENTIFICATION : null;
    }

    /**
     * The category of one SMTP reply.
     *
     * <p>When the reply carries an enhanced status, its subject decides
     * whether the <b>recipient</b> was refused: only {@code 5.1.x} says so. A
     * {@code 550 5.7.1 Relaying denied} or a {@code 553 5.7.1 Sender address
     * rejected} is the relay refusing the application — a wrong
     * {@code MAIL_FROM}, a missing authorisation — and reading it as a refused
     * address would mark the whole roster as unreachable and hold every
     * reminder back. Without an enhanced status, 550, 551 and 553 keep their
     * usual meaning.</p>
     *
     * @param message the whole reply, read only for its enhanced status code
     *                and never stored
     */
    static MailFailureCategory ofReply(int replyCode, String message) {
        String text = message == null ? "" : message;
        EnhancedStatus enhanced = enhancedStatus(replyCode, text);
        int subject = enhanced == null ? -1 : enhanced.subject();
        int detail = enhanced == null ? -1 : enhanced.detail();
        if (AUTHENTICATION_REPLIES.contains(replyCode) || (subject == 7 && mentionsAuthentication(text))) {
            return AUTHENTIFICATION;
        }
        if (replyCode >= 400 && replyCode < 500) {
            return TEMPORAIRE;
        }
        if (replyCode == 552 || (subject == 2 && detail == 2)) {
            // Over quota: permanent by the letter of RFC 5321, emptied by
            // next week in practice — the next reminder is worth sending.
            return TEMPORAIRE;
        }
        if (enhanced != null) {
            return subject == 1 ? ADRESSE_REFUSEE : AUTRE;
        }
        return RECIPIENT_REPLIES.contains(replyCode) ? ADRESSE_REFUSEE : AUTRE;
    }

    /**
     * The subject and detail of the reply's enhanced status, {@code null}
     * without one. A status whose class is not the reply code's own — a
     * version number, an address — is no status at all.
     */
    private static EnhancedStatus enhancedStatus(int replyCode, String text) {
        Matcher enhanced = ENHANCED_STATUS.matcher(text);
        while (enhanced.find()) {
            if (Integer.parseInt(enhanced.group(1)) == replyCode / 100) {
                return new EnhancedStatus(Integer.parseInt(enhanced.group(2)), Integer.parseInt(enhanced.group(3)));
            }
        }
        return null;
    }

    /** The two numbers of an enhanced status that say something once its class is known. */
    private record EnhancedStatus(int subject, int detail) {}

    /**
     * A failure on the way to the relay. Netty's {@code ConnectTimeoutException}
     * needs no line of its own: it is a {@link ConnectException}.
     */
    private static boolean isNetwork(Throwable failure) {
        return failure instanceof ConnectException
                || failure instanceof UnknownHostException
                || failure instanceof NoRouteToHostException
                || failure instanceof SocketTimeoutException
                || failure instanceof SSLException;
    }

    private static boolean mentionsAuthentication(String message) {
        String lower = message.toLowerCase(Locale.ROOT);
        return lower.contains("authentication") || lower.contains("auth failed") || lower.contains("credentials");
    }

    /** The exception and its causes, outermost first, each one once: a chain may loop back on itself. */
    private static List<Throwable> chain(Throwable failure) {
        List<Throwable> chain = new ArrayList<>();
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable current = failure;
                current != null && chain.size() < MAX_CHAIN && seen.add(current);
                current = current.getCause()) {
            chain.add(current);
        }
        return chain;
    }
}
