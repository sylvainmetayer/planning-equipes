package dev.sylvain.planning.mcp;

import dev.sylvain.planning.service.BusinessError;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolCallException;
import jakarta.annotation.Priority;
import jakarta.interceptor.AroundInvoke;
import jakarta.interceptor.Interceptor;
import jakarta.interceptor.InvocationContext;

/**
 * Turns a refusal from the domain into an answer the assistant can act on,
 * once, for every MCP tool — the counterpart of {@code BusinessErrorMapper}
 * on the other side of the application (issue #529).
 *
 * <p>Without it a {@link BusinessError} leaves as a generic JSON-RPC failure,
 * {@code {"code": -32603, "message": "Internal error"}}: « date invalide
 * « 18/07/2026 », format attendu AAAA-MM-JJ » never reaches the caller, which
 * then has nothing to correct and retries the same call. A
 * {@link ToolCallException} is what the MCP extension renders as a tool result
 * in error ({@code isError}) carrying the sentence, which is the shape the
 * protocol has for « you asked for something I refuse ».</p>
 *
 * <p>Only {@link BusinessError} is translated. Anything else — a plain
 * {@code IllegalArgumentException}, an {@code NullPointerException}, a
 * database failure — keeps its « Internal error », exactly as the REST side
 * keeps its 500: telling a deliberate refusal apart from a bug is the whole
 * point of having a type for the first one, and an assistant that cannot tell
 * them apart either retries a broken server or gives up on a typo.</p>
 *
 * <p><b>The message travels as-is, and that is only safe because no refusal
 * names anybody.</b> Running it through {@code AnonymisationViolations} was
 * the other candidate and it does not hold here: that rewrite targets the
 * « Prénom Nom (id) » shape of a violation line, and a refusal such as « Deux
 * horaires de même portée (MONDAY,TUESDAY) … » matches it by accident. The
 * rule is therefore held at the source — {@code
 * McpRefusMetierStructurelleTest} fails on a {@code BusinessError} built from
 * an animateur's nom, prénom, date de naissance or adresse — which is also
 * what makes the per-tool rewording {@code envoyer_planning_animateur} used to
 * carry unnecessary.</p>
 *
 * <p>Runs <b>outside</b> {@link EditionCibleeInterceptor} and
 * {@code JournalOutilInterceptor} (a lower priority): an unknown edition is
 * refused by the first of those before the tool body ever runs, and that
 * refusal — the one that enumerates the existing editions — is precisely the
 * message an assistant needs; the history still records the refused call
 * against the original error.</p>
 */
@RefusMetier
@Interceptor
@Priority(Interceptor.Priority.APPLICATION - 10)
public class RefusMetierInterceptor {

    /** What a refusal that forgot to say anything comes back as, rather than a null content. */
    private static final String SANS_MESSAGE = "Demande refusée.";

    @AroundInvoke
    Object reportBusinessError(InvocationContext context) throws Exception {
        if (!context.getMethod().isAnnotationPresent(Tool.class)) {
            return context.proceed();
        }
        try {
            return context.proceed();
        } catch (BusinessError refusal) {
            String message = refusal.getMessage();
            throw new ToolCallException(message == null || message.isBlank() ? SANS_MESSAGE : message, refusal);
        }
    }
}
