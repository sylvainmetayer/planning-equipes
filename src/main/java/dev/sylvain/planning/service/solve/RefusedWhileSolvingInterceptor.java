package dev.sylvain.planning.service.solve;

import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.interceptor.AroundInvoke;
import jakarta.interceptor.Interceptor;
import jakarta.interceptor.InvocationContext;

/**
 * Refuses a write a running solve would undo, before it starts — see
 * {@link RefusedWhileSolving}. The edition compared is the one the call works
 * in, already set by the request (or by {@code EditionCibleeInterceptor} for
 * an MCP tool, on the tool's own bean) when the service is reached.
 *
 * <p><b>The solve refusal comes first, before any lookup or validation of the
 * method</b>: while a solve holds the edition, an unknown id ({@code 404}), an
 * invalid input ({@code 400}) or a frozen family ({@code REFERENTIEL_FIGE},
 * below) is answered with the running solve's {@code 409}, and told only on a
 * retry once it has landed. The body has not run yet, so nothing it checks can
 * come earlier: a javadoc of a guarded method promising another order is
 * wrong.</p>
 *
 * <p><b>Its priority is deliberate</b>, on two counts.</p>
 *
 * <ul>
 *   <li><b>Before any transaction the guarded method opens itself.</b> No
 *       service here carries {@code @Transactional} — {@code JdbcEditionScope}
 *       opens the transaction inside the method body, after every interceptor
 *       — but Narayana's sits at {@code PLATFORM_BEFORE + 200}: below it, a
 *       {@code @Transactional} added one day to a guarded method would still
 *       be refused before its transaction is begun, never inside one it then
 *       rolls back. Not before one its caller already holds: the overloads
 *       taking a {@code Connection} —
 *       {@code CreneauService#deleteInBulk(Connection, Collection)},
 *       {@code PlanningPersistenceService#splitAndReassign(Connection, …)} —
 *       run inside the caller's transaction, and a refusal there is an
 *       exception that caller rolls back with whatever it wrote before. Their
 *       callers today ({@code ConsigneService#lever}, {@code #poser},
 *       {@code JourJService#recordAbsence}) are guarded themselves and have
 *       refused before opening it; the annotation on the overload is the net
 *       for a caller that is not. And above {@code @ActivateRequestContext}
 *       ({@code PLATFORM_BEFORE + 100}): {@code EditionContext} needs the
 *       request context that one activates to resolve the edition at all.</li>
 *   <li><b>Before {@code RefusedWhileFrozenInterceptor}</b>
 *       ({@code APPLICATION + 30}), which the number above implies, and which
 *       is the order wanted: this check is in memory, on the
 *       {@code SolverJobService} monitor, while the freeze is read from the
 *       database — a write a solve holds up is turned down without borrowing a
 *       connection. When both apply, the answer names the running solve, the
 *       one the operator can act on at once (wait, or stop it); a retry once
 *       it has landed is then told of the freeze.</li>
 * </ul>
 *
 * <p>The window between this check and the write is the one
 * {@link SolverJobService#refuseIfSolving} already documents: moving the call
 * into an interceptor leaves it on the same monitor and changes nothing to
 * it.</p>
 */
@RefusedWhileSolving
@Interceptor
@Priority(Interceptor.Priority.PLATFORM_BEFORE + 150)
public class RefusedWhileSolvingInterceptor {

    private final SolverJobService solverJobs;

    @Inject
    RefusedWhileSolvingInterceptor(SolverJobService solverJobs) {
        this.solverJobs = solverJobs;
    }

    @AroundInvoke
    Object refuseWhileSolving(InvocationContext context) throws Exception {
        solverJobs.refuseIfSolving();
        return context.proceed();
    }
}
