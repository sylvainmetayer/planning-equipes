package dev.sylvain.planning.scenario;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/** Shared Jackson setup for reading scenario YAML into {@code fr...scenario.dto} records. */
final class ScenarioYamlMapper {

    private ScenarioYamlMapper() {
    }

    static ObjectMapper create() {
        return new ObjectMapper(new YAMLFactory()).registerModule(new JavaTimeModule());
    }
}
