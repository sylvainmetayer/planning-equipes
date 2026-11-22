package dev.sylvain.planning.service.solve;

import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfigBuilder;
import java.util.Map;
import org.eclipse.microprofile.config.Config;

/**
 * The configuration of a test that checks something only Timefold's own search
 * does (the two stages of a published plan): the instance's engine is pinned
 * to {@code timefold} instead of following the default, which is {@code hand}
 * since ADR 0088. Everything else reads the real default.
 */
public final class TimefoldEngineConfig {

    private TimefoldEngineConfig() {}

    public static Config get() {
        return new SmallRyeConfigBuilder()
                .addDefaultSources()
                .addDiscoveredSources()
                .withSources(new PropertiesConfigSource(
                        Map.of(SolverConfiguration.ENGINE_PROPERTY, SolverEngine.TIMEFOLD.configValue()),
                        "timefold-engine",
                        1000))
                .build();
    }
}
