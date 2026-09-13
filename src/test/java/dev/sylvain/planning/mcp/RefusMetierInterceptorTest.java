package dev.sylvain.planning.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.sylvain.planning.service.BusinessError;
import io.quarkiverse.mcp.server.ToolCallException;
import jakarta.interceptor.InvocationContext;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.Test;

/**
 * What the interceptor may and may not translate — the distinction issue #529
 * exists to restore.
 *
 * <p>A refusal comes back as a tool result in error carrying its sentence; a
 * bug keeps « Internal error » and its alert. Confusing the two is what left
 * an assistant unable to tell « tu as mal saisi » from « le serveur est
 * cassé », retrying the same call or giving up.</p>
 */
class RefusMetierInterceptorTest {

    private final RefusMetierInterceptor interceptor = new RefusMetierInterceptor();

    @Test
    void unRefusMetierRepartAvecSaPhrase() throws Exception {
        Method outil = OutilsMcp.all().getFirst();

        assertThatThrownBy(() -> interceptor.reportBusinessError(contexte(outil, () -> {
                    throw new BusinessError.Invalid(
                            "date : date invalide « 18/07/2026 », " + "format attendu AAAA-MM-JJ");
                })))
                .isInstanceOf(ToolCallException.class)
                .hasMessage("date : date invalide « 18/07/2026 », format attendu AAAA-MM-JJ")
                .hasCauseInstanceOf(BusinessError.Invalid.class);
    }

    /**
     * The negative half, and the reason a marker type exists at all: an error
     * nobody meant to throw must keep its generic answer and its alert, here
     * as over HTTP where it keeps its 500.
     */
    @Test
    void unBugResteUnBug() {
        Exception bug = new IllegalArgumentException("null somewhere");

        assertThatThrownBy(() ->
                        interceptor.reportBusinessError(contexte(OutilsMcp.all().getFirst(), () -> {
                            throw bug;
                        })))
                .isSameAs(bug);
    }

    /** Helpers of a tool class are intercepted too; only the tool boundary answers the MCP client. */
    @Test
    void horsDUnOutilRienNEstTraduit() throws Exception {
        Method pasUnOutil = RefusMetierInterceptorTest.class.getDeclaredMethod("horsDUnOutilRienNEstTraduit");
        Exception refus = new BusinessError.NotFound("Stand inconnu : S1");

        assertThatThrownBy(() -> interceptor.reportBusinessError(contexte(pasUnOutil, () -> {
                    throw refus;
                })))
                .isSameAs(refus);
    }

    @Test
    void unRefusSansPhraseNeRendPasUnContenuVide() throws Exception {
        assertThatThrownBy(() ->
                        interceptor.reportBusinessError(contexte(OutilsMcp.all().getFirst(), () -> {
                            throw new BusinessError.Conflict(null);
                        })))
                .isInstanceOf(ToolCallException.class)
                .hasMessage("Demande refusée.");
    }

    @Test
    void unAppelQuiPasseNEstPasTouche() throws Exception {
        assertThat(interceptor.reportBusinessError(contexte(OutilsMcp.all().getFirst(), () -> "ok")))
                .isEqualTo("ok");
    }

    private static InvocationContext contexte(Method methode, Callable<Object> corps) {
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
                return methode;
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
            public void setParameters(Object[] parametres) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Map<String, Object> getContextData() {
                return Map.of();
            }

            @Override
            public Object proceed() throws Exception {
                return corps.call();
            }
        };
    }
}
