package dev.sylvain.planning.mcp;

import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.interceptor.AroundInvoke;
import jakarta.interceptor.Interceptor;
import jakarta.interceptor.InvocationContext;

/**
 * Adds {@link WarningCodes#RESOLUTION_EN_COURS} to the answer of a write
 * accepted while a solve holds its edition — see {@link WarnsWhileSolving}.
 *
 * <p>Asked <b>after</b> the write, and inside {@link EditionCibleeInterceptor}
 * (a higher priority number runs nearer the tool), so the edition compared is
 * the one the tool wrote to, named by its {@code edition} argument: a solve
 * of another edition says nothing about this write, and neither does one that
 * ended while it was being made. A solve still queued holds nothing either —
 * it will read the referential, write included, when its turn comes.</p>
 *
 * <p>A refusal or a failure propagates untouched: there is nothing to warn
 * about a write that did not happen.</p>
 */
@WarnsWhileSolving
@Interceptor
@Priority(Interceptor.Priority.APPLICATION + 20)
public class WarnsWhileSolvingInterceptor {

    @Inject
    RunningSolveProbe runningSolve;

    @AroundInvoke
    Object warnWhileSolving(InvocationContext context) throws Exception {
        Object result = context.proceed();
        if (result instanceof WarningCarrier<?> answer && runningSolve.holdsCurrentEdition()) {
            return answer.withWarning(WarningCodes.RESOLUTION_EN_COURS);
        }
        return result;
    }
}
