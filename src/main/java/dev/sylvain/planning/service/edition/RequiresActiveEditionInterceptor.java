package dev.sylvain.planning.service.edition;

import dev.sylvain.planning.service.BusinessError;
import dev.sylvain.planning.service.EditionContext;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.interceptor.AroundInvoke;
import jakarta.interceptor.Interceptor;
import jakarta.interceptor.InvocationContext;

/**
 * Refuses an outward call from an edition that is not the active one — see
 * {@link RequiresActiveEdition}. The edition compared is the one the call
 * works in, already set by the request (or by {@code EditionCibleeInterceptor}
 * for an MCP tool) when the service is reached.
 *
 * <p>Same priority as {@code RefusedWhileSolvingInterceptor}, for the same
 * reasons: before any transaction the method would open, after the request
 * context {@code EditionContext} needs. Both checks are in memory.</p>
 */
@RequiresActiveEdition
@Interceptor
@Priority(Interceptor.Priority.PLATFORM_BEFORE + 150)
public class RequiresActiveEditionInterceptor {

    private final EditionContext editionContext;

    @Inject
    RequiresActiveEditionInterceptor(EditionContext editionContext) {
        this.editionContext = editionContext;
    }

    @AroundInvoke
    Object requireActiveEdition(InvocationContext context) throws Exception {
        refuseIfInactive(editionContext);
        return context.proceed();
    }

    /** The check itself, for the rare caller that refuses only in one branch. */
    public static void refuseIfInactive(EditionContext editionContext) {
        String edition = editionContext.editionIdCourant();
        if (!editionContext.isActive(edition)) {
            throw new BusinessError.EditionRefused(
                    BusinessError.EditionRefused.Reason.INACTIVE,
                    "L'édition « " + edition + " » n'est pas l'édition active : rien n'en part vers l'extérieur"
                            + " (publication, courriels). Activez-la depuis la page Éditions.");
        }
    }
}
