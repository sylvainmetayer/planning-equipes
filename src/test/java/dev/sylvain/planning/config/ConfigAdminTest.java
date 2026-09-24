package dev.sylvain.planning.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfig;
import io.smallrye.config.SmallRyeConfigBuilder;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The drag and drop switch, read from the shipped {@code application.properties}
 * without booting the application: the default is off, and
 * {@code GLISSER_DEPOSER_ACTIF} is the variable that turns it on.
 *
 * <p>Built from that file and nothing else — neither the environment of whoever
 * runs the suite nor a project {@code .env} — so exporting the variable to run
 * the E2E suite locally cannot make this test lie.</p>
 */
class ConfigAdminTest {

    private static final Path PROPERTIES = Path.of("src/main/resources/application.properties");

    @Test
    void dragAndDropIsOffByDefault() throws IOException {
        assertThat(mapping(Map.of()).dragDropEnabled()).isFalse();
    }

    @Test
    void theEnvironmentVariableTurnsDragAndDropOn() throws IOException {
        assertThat(mapping(Map.of("GLISSER_DEPOSER_ACTIF", "true")).dragDropEnabled())
                .isTrue();
    }

    private static ConfigAdmin mapping(Map<String, String> environment) throws IOException {
        SmallRyeConfig config = new SmallRyeConfigBuilder()
                .addDefaultInterceptors()
                .withSources(new PropertiesConfigSource(PROPERTIES.toUri().toURL(), 100))
                .withSources(new PropertiesConfigSource(environment, "environment", 300))
                .withMapping(ConfigAdmin.class)
                .build();
        return config.getConfigMapping(ConfigAdmin.class);
    }
}
