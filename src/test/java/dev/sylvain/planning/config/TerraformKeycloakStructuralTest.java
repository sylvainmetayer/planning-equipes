package dev.sylvain.planning.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Holds {@code terraform/keycloak/} — the single description of the
 * production realm — against the realm file the dev stack imports.
 *
 * <p>There are two descriptions of one realm: the JSON {@code
 * docker-compose.yml} imports, and the Terraform an instance gets. Nothing in
 * either build notices them drifting apart, and the drift is invisible until
 * somebody cannot sign in — on the stack its author does not run. Only what is
 * <b>silently</b> wrong is compared: display names and lifetimes may differ
 * between a laptop and production; the roles, the shape of the login flow,
 * PKCE and the length of a description may not.</p>
 *
 * <p>The HCL is read as text — no Terraform binary, no provider — and only the
 * resource shapes {@code main.tf} actually uses are understood. A new shape
 * fails the comparison rather than slipping past it.</p>
 */
class TerraformKeycloakStructuralTest {

    private static final Pattern RESOURCE = Pattern.compile(
            "(?m)^resource \"(?<type>[a-z_]+)\" \"(?<name>[a-z_]+)\" \\{\\n(?<body>.*?)\\n}", Pattern.DOTALL);

    private static String terraform;

    private static JsonNode realm;

    /** Every resource of {@code main.tf}, keyed {@code type.name}. */
    private static Map<String, String> resources;

    @BeforeAll
    static void read() {
        terraform = KeycloakConfigFiles.read(KeycloakConfigFiles.TERRAFORM);
        realm = KeycloakConfigFiles.realm();
        resources = new LinkedHashMap<>();
        Matcher resource = RESOURCE.matcher(terraform);
        while (resource.find()) {
            resources.put(resource.group("type") + "." + resource.group("name"), resource.group("body"));
        }
        assertThat(resources)
                .as("resources parsed from %s", KeycloakConfigFiles.TERRAFORM)
                .isNotEmpty();
    }

    /**
     * The login flow is the same tree on both sides, node for node: same
     * steps, same sub-flows, same requirements, same order, same
     * configurations.
     *
     * <p>This is the assertion that earns the file. The realm JSON's flow is
     * the one a real Keycloak imports on every dev start; if the production
     * one differs, nobody finds out until an administrator signs in with a
     * single factor — or cannot sign in at all.</p>
     */
    /**
     * Production offers passkeys on the address screen like the development
     * realm — an attribute the provider only knows from 5.8 on, hence the pin.
     */
    @Test
    void passkeysAreOfferedFromTheAddressScreenAsInDevelopment() {
        assertThat(realm.path("webAuthnPolicyPasswordlessPasskeysEnabled").asBoolean())
                .isTrue();
        assertThat(resources.get("keycloak_realm.planning"))
                .containsPattern("passwordless_passkeys_enabled\\s*=\\s*true");
        assertThat(KeycloakConfigFiles.read(Path.of("terraform/keycloak/versions.tf")))
                .as("passwordless_passkeys_enabled exists from provider 5.8 on")
                .contains("version = \"~> 5.8\"");
    }

    @Test
    void anInvitationLinkLastsTwoDaysAsInDevelopment() {
        assertThat(realm.path("actionTokenGeneratedByAdminLifespan").asInt()).isEqualTo(48 * 3600);
        assertThat(resources.get("keycloak_realm.planning"))
                .containsPattern("action_token_generated_by_admin_lifespan\\s*=\\s*\"48h\"");
    }

    @Test
    void mailsAndPagesAreInFrenchByDefaultAsInDevelopment() {
        assertThat(realm.path("defaultLocale").asText()).isEqualTo("fr");
        assertThat(resources.get("keycloak_realm.planning")).containsPattern("default_locale\\s*=\\s*\"fr\"");
    }

    @Test
    void theLoginFlowIsTheSameTreeOnBothSides() {
        assertThat(terraformFlowTree())
                .as("terraform/keycloak/main.tf against docker/keycloak/realm-planning.json")
                .containsExactlyElementsOf(KeycloakConfigFiles.realmFlowTree(realm));
    }

    /**
     * The conditional second factor hangs off the <b>forms</b> sub-flow, after
     * the methods — never off the top-level flow, never under one method.
     *
     * <p>Attached one level up, it sits among the flow's alternatives before
     * anybody has identified themselves: the condition on the role never
     * resolves and the sign-in succeeds without the factor one believed
     * mandatory. Attached under the password, the e-mail code lets an
     * administrator in with one factor. Neither fails, neither is logged.</p>
     */
    @Test
    void theSecondFactorHangsOffTheFormsSubflow() {
        assertThat(parentOf("keycloak_authentication_subflow.otp_admin"))
                .isEqualTo("keycloak_authentication_subflow.forms.alias");
        assertThat(parentOf("keycloak_authentication_subflow.methodes"))
                .isEqualTo("keycloak_authentication_subflow.forms.alias");
        assertThat(attribute("keycloak_authentication_execution_config.condition_role_admin", "condUserRole"))
                .isEqualTo("keycloak_role.admin.name");
        assertThat(resources.get("keycloak_authentication_execution_config.condition_role_admin"))
                .contains("negate       = \"false\"");
    }

    /**
     * The Terraform builds the flow itself rather than copying the built-in
     * one: {@code copy_from} gives no way to reach inside the copy's own
     * {@code forms} sub-flow except by guessing the alias Keycloak generates,
     * which is how the conditional sub-flow ends up one level too high.
     */
    @Test
    void theFlowIsWrittenInFullRatherThanCopied() {
        // The assignment, not the word: a comment NAMES copy_from to say why
        // it is not used.
        assertThat(terraform.lines().filter(line -> !line.strip().startsWith("#")))
                .noneMatch(line -> line.matches("\\s*copy_from\\s*=.*"));
        assertThat(attribute("keycloak_authentication_bindings.planning", "browser_flow"))
                .isEqualTo("keycloak_authentication_flow.browser_planning.alias");
    }

    /** The roles described in Terraform are the versioned realm's, no more and no fewer. */
    @Test
    void theSameRolesOnBothSides() {
        List<String> terraformRoles = resourcesOfType("keycloak_role").values().stream()
                .map(body -> literal(body, "name")
                        .orElseGet(() -> "var.role_animateur".equals(raw(body, "name"))
                                ? variableDefault("role_animateur")
                                : raw(body, "name")))
                .toList();
        assertThat(terraformRoles).containsExactlyInAnyOrderElementsOf(KeycloakConfigFiles.realmRoles(realm));
        assertThat(KeycloakConfigFiles.read(Path.of("terraform/keycloak/variables.tf")))
                .as("the animateur role can never be the ordinary one, which every account carries")
                .contains("!contains([\"user\", \"admin\", \"mcp\"], var.role_animateur)");
    }

    /**
     * PKCE is declared on both sides, and the application sends it.
     *
     * <p>Declared on one only, it kills every sign-in <b>before</b> the login
     * screen, on a {@code Missing parameter: code_challenge_method}.</p>
     */
    @Test
    void pkceIsDeclaredLikeInTheRealm() {
        assertThat(attribute("keycloak_openid_client.app", "pkce_code_challenge_method"))
                .isEqualTo("\"S256\"");
        assertThat(attribute("keycloak_openid_client.mcp_public", "pkce_code_challenge_method"))
                .isEqualTo("\"S256\"");
        assertThat(KeycloakConfigFiles.client(realm, "planning-app")
                        .path("attributes")
                        .path("pkce.code.challenge.method")
                        .asText())
                .isEqualTo("S256");
        assertThat(KeycloakConfigFiles.property("quarkus.oidc.authentication.pkce-required"))
                .isEqualTo("true");
    }

    /** The client ids the application defaults to are the ones Terraform creates by default. */
    @Test
    void theClientsAreTheOnesTheApplicationNames() {
        assertThat(variableDefault("client_app")).isEqualTo(KeycloakConfigFiles.property("quarkus.oidc.client-id"));
        assertThat(variableDefault("client_mcp_resource"))
                .isEqualTo(KeycloakConfigFiles.property("quarkus.oidc.mcptransport.token.audience"));
        assertThat(variableDefault("client_provisioning"))
                .isEqualTo(KeycloakConfigFiles.property("planning.auth.oidc.provisioning.client-id"));
        assertThat(variableDefault("realm"))
                .isEqualTo(KeycloakConfigFiles.property("planning.auth.oidc.provisioning.realm"));
        assertThat(attribute("keycloak_openid_audience_protocol_mapper.mcp_audience", "included_client_audience"))
                .as("the audience mapper names the resource server the application checks tokens against")
                .isEqualTo("keycloak_openid_client.mcp_resource.client_id");
        assertThat(attribute("keycloak_openid_client_service_account_realm_role.mcp", "role"))
                .as("the automated MCP client carries mcp, never admin")
                .isEqualTo("keycloak_role.mcp.name");
    }

    /**
     * No secret is written in the Terraform: they come from {@code TF_VAR_*},
     * and the state that ends up holding them is kept out of git.
     */
    @Test
    void noSecretIsWrittenInTheTerraform() {
        for (Map.Entry<String, String> resource : resources.entrySet()) {
            assertThat(resource.getValue())
                    .as(resource.getKey())
                    .doesNotContainPattern("(?m)^\\s*(client_secret|password)\\s*=\\s*\"");
        }
        assertThat(KeycloakConfigFiles.read(Path.of("terraform/keycloak/terraform.tfvars.example")))
                .doesNotContainPattern("(?m)^\\s*[a-z_]*(secret|password)\\s*=");
        assertThat(KeycloakConfigFiles.read(Path.of("terraform/keycloak/.gitignore")))
                .contains("*.tfstate")
                .contains("terraform.tfvars")
                .doesNotContain("\n.terraform.lock.hcl");
    }

    /**
     * No description exceeds what Keycloak can store: past its {@code
     * VARCHAR(255)} the admin API answers a bare {@code 500} with {@code
     * "unknown_error"}, the cause only in the server's log.
     */
    @Test
    void noDescriptionOverrunsTheColumnKeycloakStoresItIn() {
        List<String> tooLong = new ArrayList<>();
        Matcher description = Pattern.compile("(?m)^\\s*description\\s*=\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
                .matcher(terraform);
        int found = 0;
        while (description.find()) {
            found++;
            // An interpolation is counted at the length of its default value.
            String text =
                    description.group(1).replace("${var.client_mcp_resource}", variableDefault("client_mcp_resource"));
            if (text.length() > KeycloakConfigFiles.DESCRIPTION_COLUMN) {
                tooLong.add(text.length() + " characters: " + text.substring(0, 60) + "…");
            }
        }
        assertThat(found).as("descriptions read").isGreaterThan(5);
        assertThat(terraform)
                .as("a heredoc description would escape this check")
                .doesNotContain("description = <<");
        assertThat(tooLong).isEmpty();
    }

    /* ------------------------------------------------------------- the tree -- */

    /** The browser flow of {@code main.tf}, as {@link KeycloakConfigFiles#realmFlowTree} lines. */
    private static List<String> terraformFlowTree() {
        String top = literal(resources.get("keycloak_authentication_flow.browser_planning"), "alias")
                .orElseThrow();
        assertThat(top).isEqualTo(KeycloakConfigFiles.BROWSER_FLOW);
        List<String> lines = new ArrayList<>();
        walk("keycloak_authentication_flow.browser_planning.alias", 0, lines);
        return lines;
    }

    private static void walk(String parentReference, int depth, List<String> out) {
        List<Map.Entry<String, String>> children = resources.entrySet().stream()
                .filter(entry -> entry.getKey().startsWith("keycloak_authentication_execution.")
                        || entry.getKey().startsWith("keycloak_authentication_subflow."))
                .filter(entry -> parentReference.equals(raw(entry.getValue(), "parent_flow_alias")))
                .sorted(Comparator.comparingInt(entry -> Integer.parseInt(raw(entry.getValue(), "priority"))))
                .toList();
        for (Map.Entry<String, String> child : children) {
            String indent = "  ".repeat(depth);
            String requirement = literal(child.getValue(), "requirement").orElseThrow();
            if (child.getKey().startsWith("keycloak_authentication_subflow.")) {
                out.add(indent + "[" + literal(child.getValue(), "alias").orElseThrow() + "] " + requirement);
                walk(child.getKey() + ".alias", depth + 1, out);
            } else {
                String config = configAliasOf(child.getKey());
                out.add(indent + literal(child.getValue(), "authenticator").orElseThrow() + " " + requirement
                        + (config == null ? "" : " {" + config + "}"));
            }
        }
    }

    /** The alias of the execution config pointing at {@code execution}, if any. */
    private static String configAliasOf(String execution) {
        return resourcesOfType("keycloak_authentication_execution_config").values().stream()
                .filter(body -> (execution + ".id").equals(raw(body, "execution_id")))
                .map(body -> literal(body, "alias").orElseThrow())
                .findFirst()
                .orElse(null);
    }

    /* ------------------------------------------------------------ helpers -- */

    private static Map<String, String> resourcesOfType(String type) {
        Map<String, String> found = new LinkedHashMap<>();
        resources.forEach((key, body) -> {
            if (key.startsWith(type + ".")) {
                found.put(key, body);
            }
        });
        return found;
    }

    private static String parentOf(String resource) {
        return attribute(resource, "parent_flow_alias");
    }

    private static String attribute(String resource, String name) {
        String body = resources.get(resource);
        assertThat(body)
                .as("resource %s in %s", resource, KeycloakConfigFiles.TERRAFORM)
                .isNotNull();
        return raw(body, name);
    }

    /** The right-hand side of {@code name = …} at the top level of a resource body, as written. */
    private static String raw(String body, String name) {
        Matcher matcher = Pattern.compile("(?m)^\\s*" + Pattern.quote(name) + "\\s*=\\s*(.+?)\\s*$")
                .matcher(body);
        assertThat(matcher.find()).as("%s = … in\n%s", name, body).isTrue();
        return matcher.group(1);
    }

    private static Optional<String> literal(String body, String name) {
        String value = raw(body, name);
        return value.startsWith("\"") && value.endsWith("\"")
                ? Optional.of(value.substring(1, value.length() - 1))
                : Optional.empty();
    }

    /** The {@code default} of a string variable of {@code variables.tf}. */
    private static String variableDefault(String variable) {
        Matcher block = Pattern.compile("(?ms)^variable \"" + Pattern.quote(variable) + "\" \\{\\n(.*?)\\n}")
                .matcher(KeycloakConfigFiles.read(Path.of("terraform/keycloak/variables.tf")));
        assertThat(block.find()).as("variable %s", variable).isTrue();
        Matcher value = Pattern.compile("(?m)^\\s*default\\s*=\\s*\"([^\"]*)\"").matcher(block.group(1));
        assertThat(value.find()).as("default of %s", variable).isTrue();
        return value.group(1);
    }
}
