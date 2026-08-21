package dev.sylvain.planning.mcp;

import java.lang.reflect.Parameter;

import dev.sylvain.planning.service.EditionContext;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.interceptor.AroundInvoke;
import jakarta.interceptor.Interceptor;
import jakarta.interceptor.InvocationContext;

/**
 * Runs an MCP tool call in the edition its {@link EditionArg} argument names
 * (issue #181).
 *
 * <p>An MCP call is not a JAX-RS request: {@code EditionHeaderFilter} never
 * sees it, the request scope stays empty, and every tool would otherwise
 * resolve to the default edition — silently, which is the actual defect. The
 * edition therefore travels as a tool argument, and this interceptor is the
 * single place that gives it effect, through the same
 * {@code EditionContext.executeDans} that solver jobs and scenario imports
 * already use.</p>
 *
 * <p>Binding the edition around the whole call also covers the work that
 * outlives it: {@code lancer_solveur} captures
 * {@code EditionContext.editionIdCourant()} when the job is submitted, so the
 * job stays attached to the edition the call named even if the default changes
 * afterwards.</p>
 */
@EditionCiblee
@Interceptor
@Priority(Interceptor.Priority.APPLICATION)
public class EditionCibleeInterceptor {

    @Inject
    McpEditions editions;

    @Inject
    EditionContext editionContext;

    @AroundInvoke
    Object dansEditionCiblee(InvocationContext context) throws Exception {
        String editionId = editions.resoudre(argumentEdition(context));
        if (editionId == null) {
            return context.proceed();
        }
        return editionContext.executeDans(editionId, context::proceed);
    }

    /** {@code null} for a tool that declares no {@link EditionArg} — most of this package's helpers. */
    private static String argumentEdition(InvocationContext context) {
        Parameter[] parametres = context.getMethod().getParameters();
        Object[] valeurs = context.getParameters();
        for (int i = 0; i < parametres.length; i++) {
            if (parametres[i].isAnnotationPresent(EditionArg.class)) {
                return (String) valeurs[i];
            }
        }
        return null;
    }
}
