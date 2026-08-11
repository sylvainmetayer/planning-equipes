package dev.sylvain.planning.scenario;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.sylvain.planning.scenario.dto.ScenarioDto;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Standalone structural validator for scenario YAML files (see
 * docs/import-export.md), independent from PlanningService's own
 * hand-written, more permissive parser. It checks the same shape the JSON
 * Schema at docs/schema/scenario-schema.json describes — required sections,
 * field types, non-negative durations — but does not replicate
 * PlanningService's cross-reference checks (e.g. a poste's standId actually
 * matching a declared stand) nor any Timefold constraint.
 *
 * <p>Usage: {@code ./mvnw compile exec:java
 * -Dexec.mainClass=dev.sylvain.planning.scenario.ScenarioValidator
 * -Dexec.args=path/to/scenario.yaml}
 */
public final class ScenarioValidator {

    private ScenarioValidator() {
    }

    /** Returns the Bean Validation violations for the given YAML content; empty if valid. */
    public static List<String> valider(String yamlContent) throws IOException {
        ObjectMapper mapper = ScenarioYamlMapper.create();
        ScenarioDto scenario = mapper.readValue(yamlContent, ScenarioDto.class);
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            Set<ConstraintViolation<ScenarioDto>> violations = validator.validate(scenario);
            return violations.stream()
                    .map(violation -> violation.getPropertyPath() + ": " + violation.getMessage())
                    .sorted()
                    .collect(Collectors.toList());
        }
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 1) {
            System.err.println("Usage: ScenarioValidator <fichier.yaml>");
            System.exit(2);
            return;
        }
        Path fichier = Path.of(args[0]);
        String yamlContent = Files.readString(fichier);

        List<String> erreurs;
        try {
            erreurs = valider(yamlContent);
        } catch (Exception e) {
            System.err.println("YAML invalide : " + e.getMessage());
            System.exit(1);
            return;
        }

        if (erreurs.isEmpty()) {
            System.out.println(fichier + " : valide.");
        } else {
            System.out.println(fichier + " : " + erreurs.size() + " erreur(s)");
            erreurs.forEach(erreur -> System.out.println("  - " + erreur));
            System.exit(1);
        }
    }
}
