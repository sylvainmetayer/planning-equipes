package dev.sylvain.planning.service.demo;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sylvain.planning.service.compte.CompteAccesService;
import dev.sylvain.planning.service.compte.CompteService;
import dev.sylvain.planning.service.mcp.McpKeyService;
import dev.sylvain.planning.service.webhook.WebhookService;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * A demo instance changes no account, no right, no password, hands out no
 * MCP key and configures no webhook (ADR 0130), and a new method of those services cannot opt out by
 * omission: it carries {@link RefusedOnDemoInstance} or is argued open here.
 */
class DemoInstanceStructuralTest {

    private static final List<Class<?>> GUARDED =
            List.of(CompteService.class, CompteAccesService.class, McpKeyService.class, WebhookService.class);

    /** Methods a demo instance keeps open, with the reason. */
    private static final Map<String, String> OPEN = Map.ofEntries(
            Map.entry("CompteService#list", "reads"),
            Map.entry("CompteService#nomAffiche", "reads"),
            Map.entry("CompteService#identify", "binds an OIDC sign-in to an account; a demo has no provider"),
            Map.entry(
                    "CompteService#withdrawForEdition",
                    "the cascade of deleting an edition, which the demo allows; the reset brings both back"),
            Map.entry("CompteAccesService#open", "the password step of a sign-in, which a demo exists for"),
            Map.entry("CompteAccesService#redeem", "the end of a sign-in"),
            Map.entry("CompteAccesService#sessionPrincipal", "reads"),
            Map.entry("CompteAccesService#activeSession", "reads"),
            Map.entry("McpKeyService#accepts", "reads; refuses every key on a demo"),
            Map.entry("McpKeyService#configured", "reads; empty on a demo"),
            Map.entry("McpKeyService#isLiveGeneratedKey", "reads"),
            Map.entry("McpKeyService#status", "reads"),
            Map.entry("McpKeyService#invalidate", "forgets an in-memory cache"),
            Map.entry("WebhookService#list", "reads"),
            Map.entry("WebhookService#deliveries", "reads"),
            Map.entry("WebhookService#delete", "removes, sends nothing"),
            Map.entry("WebhookService#purgeDeliveries", "removes, sends nothing"));

    @Test
    void everyWriteOfTheAccountAndKeyServicesIsRefusedOnADemo() {
        List<String> unguarded = GUARDED.stream()
                .flatMap(type -> Arrays.stream(type.getDeclaredMethods())
                        .filter(m -> Modifier.isPublic(m.getModifiers())
                                && !Modifier.isStatic(m.getModifiers())
                                && !m.isSynthetic())
                        .filter(m -> !m.isAnnotationPresent(RefusedOnDemoInstance.class))
                        .map(DemoInstanceStructuralTest::key)
                        .filter(key -> !OPEN.containsKey(key)))
                .distinct()
                .sorted()
                .toList();
        assertThat(unguarded)
                .as("annotate with @RefusedOnDemoInstance, or argue the method open in OPEN")
                .isEmpty();
    }

    @Test
    void everyArguedMethodStillExists() {
        List<String> existing = GUARDED.stream()
                .flatMap(type -> Arrays.stream(type.getDeclaredMethods()).map(DemoInstanceStructuralTest::key))
                .toList();
        assertThat(existing).containsAll(OPEN.keySet());
    }

    @Test
    void everyRefusalSaysWhatIsRefused() {
        GUARDED.stream()
                .flatMap(type -> Arrays.stream(type.getDeclaredMethods()))
                .filter(m -> m.isAnnotationPresent(RefusedOnDemoInstance.class))
                .forEach(m -> assertThat(
                                m.getAnnotation(RefusedOnDemoInstance.class).value())
                        .as(key(m))
                        .isNotBlank());
    }

    private static String key(Method method) {
        return method.getDeclaringClass().getSimpleName() + "#" + method.getName();
    }
}
