package dev.sylvain.planning.service.mail;

import io.vertx.ext.mail.SMTPException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.channels.ClosedChannelException;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeoutException;

/**
 * Why a mail did not leave, in the few categories an organiser can act on.
 *
 * <p>The split that matters is {@link #ADRESSE_REFUSEE} against
 * {@link #TEMPORAIRE}: the relay refused this recipient for good (a 5xx
 * reply), so writing again to the same address is pointless until somebody
 * changes the address — whereas a 4xx is the relay asking to try later, and the
 * next reminder is allowed. The other three are about the relay itself, not
 * the person: every send fails the same way, and the fix is in the
 * deployment's configuration.</p>
 */
public enum MailFailure {

    /** The relay could not be reached: host unknown, connection refused, timeout. */
    RELAIS_INJOIGNABLE,

    /** The relay refused this server's credentials. */
    AUTHENTIFICATION,

    /** The relay refused the recipient for good (5xx). */
    ADRESSE_REFUSEE,

    /** The relay asked to try again later (4xx). */
    TEMPORAIRE,

    /** Anything else. */
    AUTRE;

    /**
     * Reads the category from what the mailer threw, walking the cause chain:
     * the blocking mailer wraps the Vert.x SMTP client's exception, and the
     * reply code sits on the innermost one.
     */
    public static MailFailure classify(Throwable failure) {
        Set<Throwable> seen = new HashSet<>();
        for (Throwable current = failure; current != null && seen.add(current); current = current.getCause()) {
            if (current instanceof SMTPException smtp) {
                return fromReply(smtp.getReplyCode(), smtp.getReplyMessage());
            }
            if (current instanceof ConnectException
                    || current instanceof UnknownHostException
                    || current instanceof NoRouteToHostException
                    || current instanceof SocketTimeoutException
                    || current instanceof TimeoutException
                    || current instanceof ClosedChannelException) {
                return RELAIS_INJOIGNABLE;
            }
            String message = current.getMessage();
            if (message != null && message.toLowerCase(Locale.ROOT).contains("auth")) {
                return AUTHENTIFICATION;
            }
        }
        return AUTRE;
    }

    /**
     * An SMTP reply code turned into a category. 530, 534, 535 and 538 are
     * the authentication replies of RFC 4954; any other 5xx is taken as a
     * refusal of the recipient, the one permanent reply a send to one person
     * can earn on its own.
     */
    static MailFailure fromReply(int code, String message) {
        if (code == 530 || code == 534 || code == 535 || code == 538) {
            return AUTHENTIFICATION;
        }
        if (code >= 500 && code < 600) {
            return ADRESSE_REFUSEE;
        }
        if (code >= 400 && code < 500) {
            return TEMPORAIRE;
        }
        if (message != null && message.toLowerCase(Locale.ROOT).contains("auth")) {
            return AUTHENTIFICATION;
        }
        return AUTRE;
    }
}
