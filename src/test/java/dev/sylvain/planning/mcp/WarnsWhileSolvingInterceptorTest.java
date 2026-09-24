package dev.sylvain.planning.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.sylvain.planning.service.BusinessError;
import jakarta.interceptor.InvocationContext;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.Test;

/**
 * What the interceptor adds, and when — without a solve: the probe says
 * whether one holds the edition, and the rule behind the probe
 * ({@code SolverJobService.activeJobForCurrentEdition}) is the guard's own.
 */
class WarnsWhileSolvingInterceptorTest {

    @Test
    void aWriteAcceptedDuringASolveOfItsEditionSaysSo() throws Exception {
        Object answer = interceptor(true).warnWhileSolving(context(() -> new SuppressionResult("E1", true)));

        assertThat(((SuppressionResult) answer).avertissements()).containsExactly("RESOLUTION_EN_COURS");
    }

    @Test
    void withoutASolveTheAnswerIsUntouched() throws Exception {
        SuppressionResult written = new SuppressionResult("E1", true);

        assertThat(interceptor(false).warnWhileSolving(context(() -> written))).isSameAs(written);
    }

    /** The warnings the write already raised stay, in their order, and the code is not doubled. */
    @Test
    void theCodeComesAfterTheWriteOwnWarningsAndOnce() throws Exception {
        StandMcpTools.WrittenStandView written =
                new StandMcpTools.WrittenStandView(null, List.of("STAND_JAMAIS_OUVERT", "RESOLUTION_EN_COURS"));

        Object answer = interceptor(true).warnWhileSolving(context(() -> written));

        assertThat(((StandMcpTools.WrittenStandView) answer).avertissements())
                .containsExactly("STAND_JAMAIS_OUVERT", "RESOLUTION_EN_COURS");
    }

    /**
     * A write refused — in 409 by the guard, or by the domain — is not turned
     * into a warned success: nothing was written, there is nothing to warn about.
     */
    @Test
    void aRefusalPropagatesUntouched() {
        BusinessError refusal = new BusinessError.Conflict("Typologie référencée par un stand");

        assertThatThrownBy(() -> interceptor(true).warnWhileSolving(context(() -> {
                    throw refusal;
                })))
                .isSameAs(refusal);
    }

    private static WarnsWhileSolvingInterceptor interceptor(boolean held) {
        WarnsWhileSolvingInterceptor interceptor = new WarnsWhileSolvingInterceptor();
        interceptor.runningSolve = new RunningSolveProbe() {
            @Override
            public boolean holdsCurrentEdition() {
                return held;
            }
        };
        return interceptor;
    }

    private static InvocationContext context(Callable<Object> body) throws Exception {
        Method tool = OutilsMcp.all().getFirst();
        return new InvocationContext() {
            @Override
            public Object getTarget() {
                return null;
            }

            @Override
            public Object getTimer() {
                return null;
            }

            @Override
            public Method getMethod() {
                return tool;
            }

            @Override
            public Constructor<?> getConstructor() {
                return null;
            }

            @Override
            public Object[] getParameters() {
                return new Object[0];
            }

            @Override
            public void setParameters(Object[] parameters) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Map<String, Object> getContextData() {
                return Map.of();
            }

            @Override
            public Object proceed() throws Exception {
                return body.call();
            }
        };
    }
}
