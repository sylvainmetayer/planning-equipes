package dev.sylvain.planning.mcp;

import java.util.List;
import java.util.Optional;

import dev.sylvain.planning.service.journal.ActionJournalisee;
import dev.sylvain.planning.service.journal.Acteur;
import dev.sylvain.planning.service.journal.CatalogueActions;
import dev.sylvain.planning.service.journal.JournalActionService;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.interceptor.AroundInvoke;
import jakarta.interceptor.Interceptor;
import jakarta.interceptor.InvocationContext;
import io.quarkiverse.mcp.server.Tool;

/**
 * Writes one line of the history for every MCP tool that changes something
 * (issue #406).
 *
 * <p>An MCP call never reaches the JAX-RS chain — {@code quarkus-mcp-server-http}
 * registers its routes ahead of it, which is why
 * {@code JournalActionFilter} cannot cover them and this interceptor exists.
 * An assistant creating a stand is an action like any other; a history that
 * showed only the screens would let a stand appear out of nowhere.</p>
 *
 * <p>Bound to {@link EditionCiblee}, the annotation every edition-targeted
 * tool class already carries and which a structural test already enforces:
 * the tools are journalled for the same reason they resolve an edition, and
 * a new tool class that forgot the annotation is caught by that test rather
 * than by this one.</p>
 *
 * <p>Runs <b>inside</b> {@code EditionCibleeInterceptor} (a later priority),
 * so the line is written in the edition the tool argument named rather than in
 * the default one.</p>
 */
@EditionCiblee
@Interceptor
@Priority(Interceptor.Priority.APPLICATION + 10)
public class JournalOutilInterceptor {

    @Inject
    JournalActionService journal;

    @AroundInvoke
    Object journaliser(InvocationContext context) throws Exception {
        Optional<ActionJournalisee> action = context.getMethod().isAnnotationPresent(Tool.class)
                ? CatalogueActions.forTool(context.getMethod().getName())
                : Optional.empty();
        if (action.isEmpty()) {
            return context.proceed();
        }
        try {
            Object resultat = context.proceed();
            journal.record(action.get(), Acteur.ASSISTANT, PRINCIPAL, null, List.of(), 200);
            return resultat;
        } catch (Exception e) {
            // A refused tool call is a fact worth keeping: an assistant that
            // tried to delete an edition and was told no belongs in the
            // history quite as much as one that succeeded.
            journal.record(action.get(), Acteur.ASSISTANT, PRINCIPAL, null, List.of(), 400);
            throw e;
        }
    }

    /** The one identity an MCP call has: a shared key, no per-client name. */
    private static final String PRINCIPAL = "mcp";
}
