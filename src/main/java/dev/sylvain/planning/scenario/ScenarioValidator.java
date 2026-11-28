package dev.sylvain.planning.scenario;

import dev.sylvain.planning.scenario.dto.ScenarioDto;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Structural validator for scenario YAML files (see site/src/docs/comprendre/scenario-yaml.md):
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

    private ScenarioValidator() {}

    /**
     * @return the Bean Validation violations for the given YAML content, empty
     *         if valid
     * @throws ScenarioFormatException when the document cannot be bound at all
     *         — a malformed file, or a key the application does not know
     */
    public static List<String> validate(String yamlContent) {
        return violations(ScenarioBinder.bind(yamlContent));
    }

    /** The Bean Validation violations of an already bound scenario, one sentence each, empty if valid. */
    public static List<String> violations(ScenarioDto scenario) {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            Set<ConstraintViolation<ScenarioDto>> violations = validator.validate(scenario);
            return violations.stream()
                    .map(violation -> PATH_NOISE
                                    .matcher(violation.getPropertyPath().toString())
                                    .replaceAll("") + ": " + sentence(violation))
                    .sorted()
                    .toList();
        }
    }

    /** The {@code .<map value>} and {@code .<list element>} nodes of a path, which name nothing in the file. */
    private static final Pattern PATH_NOISE = Pattern.compile("\\.<[^>]+>");

    /**
     * What a constraint expects, by kind of constraint — the provider's own
     * message is English and says « must not be null » to somebody writing a
     * scenario. A message the DTO wrote itself is kept: it is already the
     * sentence for that field.
     */
    private static String sentence(ConstraintViolation<?> violation) {
        if (!violation.getMessageTemplate().startsWith("{jakarta.validation.constraints.")) {
            return violation.getMessage();
        }
        Object valeur = violation.getInvalidValue();
        return switch (violation.getConstraintDescriptor().getAnnotation()) {
            case NotNull _ -> "obligatoire, absent du fichier.";
            case NotBlank _ -> "obligatoire, ne peut pas être vide.";
            case NotEmpty _ -> "doit contenir au moins un élément.";
            case Positive _ -> "doit être supérieur à 0, et non « " + valeur + " ».";
            case PositiveOrZero _ -> "doit être positif ou nul, et non « " + valeur + " ».";
            case Min min -> "doit valoir au moins " + min.value() + ", et non « " + valeur + " ».";
            case Max max -> "doit valoir au plus " + max.value() + ", et non « " + valeur + " ».";
            case DecimalMin min ->
                "doit valoir " + (min.inclusive() ? "au moins " : "plus de ") + min.value() + ", et non « " + valeur
                        + " ».";
            case DecimalMax max ->
                "doit valoir " + (max.inclusive() ? "au plus " : "moins de ") + max.value() + ", et non « " + valeur
                        + " ».";
            // The address itself is not repeated: the report may leave over MCP.
            case Email _ -> "n'est pas une adresse e-mail valide.";
            default -> violation.getMessage();
        };
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
        } catch (ScenarioFormatException e) {
            // As-is, like the other two callers: the binder's message is already
            // written for whoever wrote the file.
            System.err.println(e.getMessage());
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
