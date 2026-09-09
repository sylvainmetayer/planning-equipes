package dev.sylvain.planning.scenario;

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

/**
 * Structural validator for scenario YAML files (see docs/import-export.md):
 * the beginning of an import, without the import.
 *
 * <p>It reads through {@link ScenarioBinder}, the same door the import walks
 * in by, which is what makes its verdict worth something: a file this screen
 * calls valid is, by construction, a file the import accepts. It used to bind
 * the YAML its own way, so the two could and did disagree.
 *
 * <p>It checks the shape the JSON Schema at docs/schema/scenario-schema.json
 * describes — required sections, field types, non-negative durations — and
 * stops there: cross-reference checks (a poste's standId actually matching a
 * declared stand) belong to the mapping into the domain, and no Timefold
 * constraint is evaluated.
 *
 * <p>Usage: {@code ./mvnw compile exec:java
 * -Dexec.mainClass=dev.sylvain.planning.scenario.ScenarioValidator
 * -Dexec.args=path/to/scenario.yaml}
 */
public final class ScenarioValidator {

    private ScenarioValidator() {
    }

    /**
     * @return the Bean Validation violations for the given YAML content, empty
     *         if valid
     * @throws ScenarioFormatException when the document cannot be bound at all
     *         — a malformed file, or a key the application does not know
     */
    public static List<String> validate(String yamlContent) {
        ScenarioDto scenario = ScenarioBinder.bind(yamlContent);
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            Set<ConstraintViolation<ScenarioDto>> violations = validator.validate(scenario);
            return violations.stream()
                    .map(violation -> violation.getPropertyPath() + ": " + violation.getMessage())
                    .sorted()
                    .toList();
        }
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 1) {
            System.err.println("Usage: ScenarioValidator <fichier.yaml>");
            System.exit(2);
            return;
        }
        Path file = Path.of(args[0]);
        String yamlContent = Files.readString(file);

        List<String> erreurs;
        try {
            erreurs = validate(yamlContent);
        } catch (Exception e) {
            System.err.println("YAML invalide : " + e.getMessage());
            System.exit(1);
            return;
        }

        if (erreurs.isEmpty()) {
            System.out.println(file + " : valide.");
        } else {
            System.out.println(file + " : " + erreurs.size() + " erreur(s)");
            erreurs.forEach(erreur -> System.out.println("  - " + erreur));
            System.exit(1);
        }
    }
}
