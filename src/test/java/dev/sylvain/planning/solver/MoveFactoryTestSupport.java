package dev.sylvain.planning.solver;

import ai.timefold.solver.core.impl.score.director.ScoreDirector;
import dev.sylvain.planning.domain.PlanningEvenement;
import java.lang.reflect.Proxy;

/** What the move factory tests share. */
final class MoveFactoryTestSupport {

    private MoveFactoryTestSupport() {}

    /** The only call the factories make on the director: the working solution. */
    @SuppressWarnings("unchecked")
    static ScoreDirector<PlanningEvenement> director(PlanningEvenement solution) {
        return (ScoreDirector<PlanningEvenement>) Proxy.newProxyInstance(
                ScoreDirector.class.getClassLoader(), new Class<?>[] {ScoreDirector.class}, (proxy, method, args) -> {
                    if (method.getName().equals("getWorkingSolution")) {
                        return solution;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }
}
