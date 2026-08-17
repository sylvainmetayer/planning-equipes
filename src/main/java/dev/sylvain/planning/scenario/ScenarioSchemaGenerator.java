package dev.sylvain.planning.scenario;

import com.github.victools.jsonschema.generator.CustomDefinition;
import com.github.victools.jsonschema.generator.Option;
import com.github.victools.jsonschema.generator.OptionPreset;
import com.github.victools.jsonschema.generator.SchemaGenerator;
import com.github.victools.jsonschema.generator.SchemaGeneratorConfig;
import com.github.victools.jsonschema.generator.SchemaGeneratorConfigBuilder;
import com.github.victools.jsonschema.generator.SchemaVersion;
import com.github.victools.jsonschema.module.jackson.JacksonModule;
import com.github.victools.jsonschema.module.jackson.JacksonOption;
import com.github.victools.jsonschema.module.jakarta.validation.JakartaValidationModule;
import com.github.victools.jsonschema.module.jakarta.validation.JakartaValidationOption;
import dev.sylvain.planning.scenario.dto.ScenarioDto;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalTime;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Regenerates docs/schema/scenario-schema.json from {@link ScenarioDto} (and
 * the DTOs it references), deriving JSON Schema "required"/type/range
 * constraints straight from the Jackson + Bean Validation annotations on
 * those classes — so the schema can never drift from what the DTOs (and, by
 * construction, ScenarioValidator) actually accept.
 *
 * <p>Run via {@code ./mvnw process-classes -Pgenerate-schema} (see the
 * generate-schema profile in pom.xml) after changing a scenario DTO, then
 * commit the regenerated file. See docs/import-export.md.
 */
public final class ScenarioSchemaGenerator {

    static final Path SCHEMA_PATH = Path.of("docs", "schema", "scenario-schema.json");

    private ScenarioSchemaGenerator() {
    }

    public static void main(String[] args) throws IOException {
        JsonNode schema = generate();
        Files.createDirectories(SCHEMA_PATH.getParent());
        String pretty = new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(schema);
        Files.writeString(SCHEMA_PATH, pretty + System.lineSeparator());
        System.out.println("Schéma écrit dans " + SCHEMA_PATH.toAbsolutePath());
    }

    static JsonNode generate() {
        JacksonModule jacksonModule = new JacksonModule(JacksonOption.RESPECT_JSONPROPERTY_REQUIRED);
        JakartaValidationModule validationModule = new JakartaValidationModule(
                JakartaValidationOption.NOT_NULLABLE_FIELD_IS_REQUIRED,
                JakartaValidationOption.INCLUDE_PATTERN_EXPRESSIONS);

        SchemaGeneratorConfigBuilder configBuilder = new SchemaGeneratorConfigBuilder(
                SchemaVersion.DRAFT_2020_12, OptionPreset.PLAIN_JSON)
                .with(Option.EXTRA_OPEN_API_FORMAT_VALUES)
                .with(Option.DEFINITIONS_FOR_ALL_OBJECTS)
                .with(Option.MAP_VALUES_AS_ADDITIONAL_PROPERTIES)
                .with(jacksonModule)
                .with(validationModule);

        // Represent LocalDate/LocalTime as plain date/time strings instead of
        // letting the generator reflect over their internal fields — this is
        // how they are actually written in scenario YAML (e.g. "2026-07-08",
        // "09:00" or "09:00:00", never as a nested {year, month, day} object).
        configBuilder.forTypesInGeneral().withCustomDefinitionProvider((javaType, context) -> {
            if (javaType.getErasedType() == LocalDate.class) {
                ObjectNode node = context.getGeneratorConfig().createObjectNode()
                        .put("type", "string")
                        .put("format", "date");
                return new CustomDefinition(node);
            }
            if (javaType.getErasedType() == LocalTime.class) {
                ObjectNode node = context.getGeneratorConfig().createObjectNode()
                        .put("type", "string")
                        .put("pattern", "^([01]\\d|2[0-3]):[0-5]\\d(:[0-5]\\d)?$");
                return new CustomDefinition(node);
            }
            return null;
        });

        SchemaGeneratorConfig config = configBuilder.build();
        return new SchemaGenerator(config).generateSchema(ScenarioDto.class);
    }
}
