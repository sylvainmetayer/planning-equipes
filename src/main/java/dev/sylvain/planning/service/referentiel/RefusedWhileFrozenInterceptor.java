package dev.sylvain.planning.service.referentiel;

import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.interceptor.AroundInvoke;
import jakarta.interceptor.Interceptor;
import jakarta.interceptor.InvocationContext;

/**
 * Refuses a write of a frozen family before it starts — see
 * {@link RefusedWhileFrozen}. The edition is the one the call works in,
 * already set by the request (or by {@code EditionCibleeInterceptor} for an
 * MCP tool) when the service is reached.
 */
@RefusedWhileFrozen
@Interceptor
@Priority(Interceptor.Priority.APPLICATION + 30)
public class RefusedWhileFrozenInterceptor {

    private final GelReferentielService gel;

    @Inject
    RefusedWhileFrozenInterceptor(GelReferentielService gel) {
        this.gel = gel;
    }

    @AroundInvoke
    Object refuseWhileFrozen(InvocationContext context) throws Exception {
        RefusedWhileFrozen binding = context.getMethod().getAnnotation(RefusedWhileFrozen.class);
        gel.refuseIfFrozen(binding == null ? new ReferentialFamily[0] : binding.value());
        return context.proceed();
    }
}
