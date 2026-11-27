package dev.sylvain.planning.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.smallrye.config.SmallRyeConfig;
import io.smallrye.config.SmallRyeConfigBuilder;
import io.smallrye.config.common.MapBackedConfigSource;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** What {@code DEMO_INSTANCE=true} implies, whatever the variables beside it say (ADR 0130). */
class DemoInstanceTest {

    /** An operator who set every one of them the other way. */
    private static final Map<String, String> OPPOSITE = Map.of(
            "planning.public-instance", "false",
            "planning.legal.demo-instance", "false",
            "quarkus.mailer.mock", "false",
            "planning.auth.local.seconde-etape", "true",
            "planning.webhooks.enabled", "true",
            "planning.meteo.enabled", "true",
            "quarkus.oidc.mcptransport.tenant-enabled", "true",
            "planning.auth.oidc.enabled", "true",
            "quarkus.oidc.tenant-enabled", "true",
            "%prod.planning.webhooks.enabled", "true");

    private static SmallRyeConfig config(String demo) {
        Map<String, String> values = new HashMap<>(OPPOSITE);
        if (demo != null) {
            values.put(DemoInstance.VARIABLE, demo);
        }
        return new SmallRyeConfigBuilder()
                .withProfile("prod")
                .withSources(new MapBackedConfigSource("env", values, 300) {})
                .withInterceptors(new DemoInstance())
                .build();
    }

    @Test
    void theFlagForcesEveryOutwardSettingShut() {
        SmallRyeConfig config = config("true");

        assertThat(config.getValue("planning.public-instance", Boolean.class)).isTrue();
        assertThat(config.getValue("planning.legal.demo-instance", Boolean.class))
                .isTrue();
        assertThat(config.getValue("quarkus.mailer.mock", Boolean.class)).isTrue();
        assertThat(config.getValue("planning.auth.local.seconde-etape", Boolean.class))
                .isFalse();
        assertThat(config.getValue("planning.webhooks.enabled", Boolean.class)).isFalse();
        assertThat(config.getValue("planning.meteo.enabled", Boolean.class)).isFalse();
        assertThat(config.getValue("quarkus.oidc.mcptransport.tenant-enabled", Boolean.class))
                .isFalse();
        assertThat(config.getValue("planning.auth.oidc.enabled", Boolean.class)).isFalse();
        assertThat(config.getValue("quarkus.oidc.tenant-enabled", Boolean.class))
                .isFalse();
    }

    @Test
    void everySpellingTheBooleanConverterReadsAsTrueForcesToo() {
        for (String demo : new String[] {"TRUE", " true ", "1", "yes", "on", "y"}) {
            assertThat(config(demo).getValue("planning.webhooks.enabled", Boolean.class))
                    .as(demo)
                    .isFalse();
        }
    }

    @Test
    void withoutTheFlagNothingIsTouched() {
        for (String demo : new String[] {null, "false"}) {
            SmallRyeConfig config = config(demo);

            assertThat(config.getValue("planning.webhooks.enabled", Boolean.class))
                    .isTrue();
            assertThat(config.getValue("quarkus.mailer.mock", Boolean.class)).isFalse();
        }
    }
}
