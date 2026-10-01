package dev.sylvain.planning.mcp;

import dev.sylvain.planning.service.BusinessError;
import dev.sylvain.planning.service.EditionContext;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.interceptor.AroundInvoke;
import jakarta.interceptor.Interceptor;
import jakarta.interceptor.InvocationContext;
import java.lang.reflect.Parameter;

/**
 * Runs an MCP tool call in the edition its {@link EditionArg} argument names
 * (issue #181).
 *
 * <p>An MCP call is not a JAX-RS request: {@code EditionHeaderFilter} never
 * sees it, so the edition travels as a tool argument, and this interceptor is
 * the single place that gives it effect, through the same
 * {@code EditionContext.executeIn} that solver jobs and scenario imports
 * already use.</p>
 *
 * <p><b>The argument is required</b> (ADR 0072): a tool that declares an
 * {@link EditionArg} and is called without it is refused
 * ({@code EDITION_REQUISE}), rather than run in a default edition the
 * assistant never named — which used to be the silent defect.</p>
 *
 * <p>Binding the edition around the whole call also covers the work that
 * outlives it: {@code lancer_solveur} captures
 * {@code EditionContext.editionIdCourant()} when the job is submitted, so the
 * job stays attached to the edition the call named whatever happens
 * afterwards.</p>
 */
@EditionCiblee
@Interceptor
@Priority(Interceptor.Priority.APPLICATION)
public class EditionCibleeInterceptor {

    private final McpEditions editions;

    private final EditionContext editionContext;

    @Inject
    EditionCibleeInterceptor(McpEditions editions, EditionContext editionContext) {
        this.editions = editions;
        this.editionContext = editionContext;
    }

    @AroundInvoke
    Object dansEditionCiblee(InvocationContext context) throws Exception {
        int index = editionArgIndex(context);
        if (index < 0) {
            // A method that declares no EditionArg — most of this package's helpers.
            return context.proceed();
        }
        String editionId = editions.solve((String) context.getParameters()[index]);
        if (editionId == null) {
            throw new BusinessError.EditionRefused(
                    BusinessError.EditionRefused.Reason.REQUISE,
                    "L'argument « edition » est obligatoire : son id ou son nom (voir lister_editions).");
        }
        return editionContext.executeIn(editionId, context::proceed);
    }

    /** Position of the {@link EditionArg} parameter, {@code -1} when the method declares none. */
    private static int editionArgIndex(InvocationContext context) {
        Parameter[] parametres = context.getMethod().getParameters();
        for (int i = 0; i < parametres.length; i++) {
            if (parametres[i].isAnnotationPresent(EditionArg.class)) {
                return i;
            }
        }
        return -1;
    }
}
