package dev.sylvain.planning.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.function.Consumer;
import java.util.stream.StreamSupport;

/**
 * The files the Keycloak structural tests read, and the few ways they read
 * them — all without booting the application.
 *
 * <p>Those tests hold configuration of <i>another</i> program against this
 * one's, so the application's side is read from {@code application.properties}
 * as text rather than injected: injecting would need a {@code @QuarkusTest},
 * hence a database, for what is a comparison of two files. A property is read
 * the way a deployment with no environment variable set would see it — the
 * default of its {@code ${VARIABLE:default}} expression.</p>
 */
final class KeycloakConfigFiles {

    static final Path REALM = Path.of("docker/keycloak/realm-planning.json");
    static final Path PROPERTIES = Path.of("src/main/resources/application.properties");
    static final Path TERRAFORM = Path.of("terraform/keycloak/main.tf");
    static final Path PLAYBOOK = Path.of("ansible/keycloak-planning.yml");

    /** Width of the column Keycloak stores every description in. Beyond it, it fails rather than truncates. */
    static final int DESCRIPTION_COLUMN = 255;

    /** The flow the realm binds to browser sign-ins, on both sides. */
    static final String BROWSER_FLOW = "browser-planning";

    private KeycloakConfigFiles() {}

    static JsonNode realm() {
        try {
            return new ObjectMapper().readTree(REALM.toFile());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Properties properties() {
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(PROPERTIES, StandardCharsets.UTF_8)) {
            properties.load(reader);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return properties;
    }

    /**
     * The production value of {@code key}: its {@code ${VARIABLE:default}}
     * expressions resolved to their defaults, a reference to another property
     * resolved to that property.
     */
    static String property(String key) {
        return optionalProperty(key).orElseThrow(() -> new AssertionError(key + " is missing from " + PROPERTIES));
    }

    static Optional<String> optionalProperty(String key) {
        Properties properties = properties();
        return Optional.ofNullable(properties.getProperty(key)).map(raw -> resolve(raw, properties));
    }

    /** The {@code %dev} value of {@code key}, or its production one when no {@code %dev} line overrides it. */
    static String devProperty(String key) {
        Properties properties = properties();
        String raw = properties.getProperty("%dev." + key, properties.getProperty(key));
        assertThat(raw).as("%s (or %%dev.%s) in %s", key, key, PROPERTIES).isNotNull();
        return resolve(raw, properties);
    }

    /** Every production key starting with {@code prefix} and ending with {@code suffix}. */
    static List<String> keys(String prefix, String suffix) {
        return properties().stringPropertyNames().stream()
                .filter(key -> key.startsWith(prefix) && key.endsWith(suffix))
                .sorted()
                .toList();
    }

    /** A comma-separated property as a list, the way Quarkus reads {@code roles-allowed}. */
    static List<String> listProperty(String key) {
        return Arrays.stream(property(key).split(","))
                .map(String::strip)
                .filter(value -> !value.isEmpty())
                .toList();
    }

    private static String resolve(String raw, Properties properties) {
        String value = raw.strip();
        int start = value.indexOf("${");
        while (start >= 0) {
            int end = matchingBrace(value, start + 2);
            String expression = value.substring(start + 2, end);
            int colon = topLevelColon(expression);
            String name = colon < 0 ? expression : expression.substring(0, colon);
            String replacement;
            if (properties.getProperty(name) != null) {
                replacement = resolve(properties.getProperty(name), properties);
            } else {
                replacement = colon < 0 ? "" : resolve(expression.substring(colon + 1), properties);
            }
            value = value.substring(0, start) + replacement + value.substring(end + 1);
            start = value.indexOf("${", start + replacement.length());
        }
        return value;
    }

    private static int matchingBrace(String value, int from) {
        int depth = 1;
        for (int i = from; i < value.length(); i++) {
            if (value.startsWith("${", i)) {
                depth++;
                i++;
            } else if (value.charAt(i) == '}' && --depth == 0) {
                return i;
            }
        }
        throw new AssertionError("unbalanced expression: " + value);
    }

    private static int topLevelColon(String expression) {
        int depth = 0;
        for (int i = 0; i < expression.length(); i++) {
            if (expression.startsWith("${", i)) {
                depth++;
                i++;
            } else if (expression.charAt(i) == '}') {
                depth--;
            } else if (expression.charAt(i) == ':' && depth == 0) {
                return i;
            }
        }
        return -1;
    }

    /* ------------------------------------------------------------ realm JSON -- */

    static JsonNode client(JsonNode realm, String clientId) {
        return stream(realm.path("clients"))
                .filter(candidate -> clientId.equals(candidate.path("clientId").asText()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("client missing from the realm: " + clientId));
    }

    static JsonNode flow(JsonNode realm, String alias) {
        return stream(realm.path("authenticationFlows"))
                .filter(candidate -> alias.equals(candidate.path("alias").asText()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("flow missing from the realm: " + alias));
    }

    static List<String> realmRoles(JsonNode realm) {
        return stream(realm.path("roles").path("realm"))
                .map(role -> role.path("name").asText())
                .toList();
    }

    /**
     * The browser flow of the realm file as an indented tree, one line per
     * node, children in priority order: {@code "  [browser-planning-forms]
     * ALTERNATIVE"} for a sub-flow, {@code "    auth-username-form REQUIRED"}
     * for a step, and {@code {alias}} after a step that carries a
     * configuration. {@link TerraformKeycloakStructuralTest} builds the same
     * lines from the HCL, so the two descriptions compare as two lists.
     */
    static List<String> realmFlowTree(JsonNode realm) {
        List<String> lines = new ArrayList<>();
        walk(realm, BROWSER_FLOW, 0, lines::add);
        return lines;
    }

    private static void walk(JsonNode realm, String alias, int depth, Consumer<String> out) {
        List<JsonNode> executions = stream(flow(realm, alias).path("authenticationExecutions"))
                .sorted(Comparator.comparingInt(
                        execution -> execution.path("priority").asInt()))
                .toList();
        for (JsonNode execution : executions) {
            String indent = "  ".repeat(depth);
            String requirement = execution.path("requirement").asText();
            String subflow = execution.path("flowAlias").asText("");
            if (!subflow.isEmpty()) {
                out.accept(indent + "[" + subflow + "] " + requirement);
                walk(realm, subflow, depth + 1, out);
            } else {
                String config = execution.path("authenticatorConfig").asText("");
                out.accept(indent + execution.path("authenticator").asText() + " " + requirement
                        + (config.isEmpty() ? "" : " {" + config + "}"));
            }
        }
    }

    /** Every {@code description} longer than Keycloak's column, with where it sits. */
    static List<String> overlongDescriptions(JsonNode node) {
        List<String> found = new ArrayList<>();
        collectDescriptions(node, "", found);
        return found;
    }

    private static void collectDescriptions(JsonNode node, String path, List<String> found) {
        if (node.isObject()) {
            node.properties().forEach(entry -> {
                JsonNode value = entry.getValue();
                if ("description".equals(entry.getKey()) && value.isTextual()) {
                    if (value.asText().length() > DESCRIPTION_COLUMN) {
                        found.add(path + " (" + value.asText().length() + " characters)");
                    }
                } else {
                    collectDescriptions(value, path + "/" + entry.getKey(), found);
                }
            });
        } else if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                collectDescriptions(node.get(i), path + "[" + i + "]", found);
            }
        }
    }

    static java.util.stream.Stream<JsonNode> stream(JsonNode array) {
        return StreamSupport.stream(array.spliterator(), false);
    }
}
