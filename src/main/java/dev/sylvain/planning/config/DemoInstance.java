package dev.sylvain.planning.config;

import io.smallrye.config.ConfigSourceInterceptor;
import io.smallrye.config.ConfigSourceInterceptorContext;
import io.smallrye.config.ConfigValue;
import java.util.Locale;
import java.util.Map;

/**
 * {@code DEMO_INSTANCE=true}: a public, throw-away demonstration reset from a
 * reference dump every half hour (ADR 0130). Registered in
 * {@code META-INF/services/io.smallrye.config.ConfigSourceInterceptor}, it
 * answers the settings below with a fixed value, whatever the environment
 * says: a demo instance is public, mails nobody, calls nothing outside and
 * hands out no MCP key, and an operator cannot undo one of these by setting
 * its own variable beside the flag.
 *
 * <p>Forced rather than required at boot: one variable cannot be deployed
 * half-way, and the list of what it implies lives here, where its test reads
 * it, instead of in an operator's checklist.</p>
 */
public class DemoInstance implements ConfigSourceInterceptor {

    /** The variable, read by name from the other sources like {@link GroupedVariablesInterceptor} does. */
    public static final String VARIABLE = "DEMO_INSTANCE";

    /** The refusal every gesture closed on a demo instance starts with. */
    public static final String REFUSAL = "Instance de démonstration : ";

    /**
     * What the flag implies, property by property. The configured MCP key is
     * not here — an unset value cannot be forced through the build's recorded
     * defaults — and {@code McpKeyService} ignores it on a demo instead.
     */
    static final Map<String, String> FORCED = Map.of(
            // Public: the solve quota applies, and the boot needs a legal notice or the next key.
            "planning.public-instance", "true",
            "planning.legal.demo-instance", "true",
            // No mail leaves: the mock mailer records them, and a sign-in needs no mailed code.
            "quarkus.mailer.mock", "true",
            "planning.auth.local.seconde-etape", "false",
            // No outgoing call: webhooks refused, weather off.
            "planning.webhooks.enabled", "false",
            "planning.meteo.enabled", "false",
            // No OIDC at all, the two switches OidcConfiguration keeps in step plus the MCP tenant
            // (no key opens /mcp either: McpKeyService): a demo has no provider, and the admin form stays open.
            "planning.auth.oidc.enabled", "false",
            "quarkus.oidc.tenant-enabled", "false",
            "quarkus.oidc.mcptransport.tenant-enabled", "false");

    @Override
    public ConfigValue getValue(ConfigSourceInterceptorContext context, String name) {
        // A profiled lookup (%prod.x) is forced like the plain one, or a profile would undo the flag.
        String forced = FORCED.get(name.startsWith("%") ? name.substring(name.indexOf('.') + 1) : name);
        if (forced == null) {
            return context.proceed(name);
        }
        ConfigValue flag = context.proceed(VARIABLE);
        if (flag == null || flag.getValue() == null || !isTruthy(flag.getValue())) {
            return context.proceed(name);
        }
        return ConfigValue.builder()
                .withName(name)
                .withValue(forced)
                // Quarkus records the raw value of a runtime property at build time: never leave it null.
                .withRawValue(forced)
                .withConfigSourceName(VARIABLE)
                .build();
    }

    /** The spellings the MicroProfile boolean converter reads as true, so {@code planning.demo-instance} and this agree. */
    static boolean isTruthy(String value) {
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "true", "1", "yes", "y", "on" -> true;
            default -> false;
        };
    }
}
