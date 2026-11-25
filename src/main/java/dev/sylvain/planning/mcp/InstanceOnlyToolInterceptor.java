package dev.sylvain.planning.mcp;

import dev.sylvain.planning.service.BusinessError;
import dev.sylvain.planning.service.EditionContext;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.interceptor.AroundInvoke;
import jakarta.interceptor.Interceptor;
import jakarta.interceptor.InvocationContext;

/** Refuses an {@link InstanceOnlyTool} to a caller who is only an organiser. */
@InstanceOnlyTool
@Interceptor
@Priority(Interceptor.Priority.APPLICATION - 5)
public class InstanceOnlyToolInterceptor {

    private final EditionContext editionContext;

    @Inject
    InstanceOnlyToolInterceptor(EditionContext editionContext) {
        this.editionContext = editionContext;
    }

    @AroundInvoke
    Object refuseOrganiser(InvocationContext context) throws Exception {
        if (editionContext.callerIsOrganiser()) {
            throw new BusinessError.Invalid(
                    "Cet outil concerne toute l'instance : il est réservé aux administrateurs.");
        }
        return context.proceed();
    }
}
