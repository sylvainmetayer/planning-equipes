package dev.sylvain.planning.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Holds {@code ansible/keycloak-planning.yml} to its one job: the <b>people</b>
 * of the realm — staff accounts, their realm roles, their invitations.
 *
 * <p>The realm itself is Terraform's ({@code terraform/keycloak/}), and two
 * descriptions of one realm drift: the playbook of the first iteration built
 * its own login flow and put the administrators' second factor where it never
 * ran. So the playbook may not describe the realm again, and the rules that
 * make people management safe — never delete, never grant the ordinary role,
 * never re-send an invitation — are pinned here.</p>
 *
 * <p>Read as text: no YAML parser lives on the test classpath, and the shapes
 * checked are regular enough for that.</p>
 */
class PlaybookKeycloakStructuralTest {

    private static final Path EXAMPLE_VARS = Path.of("ansible/group_vars/all.example.yml");

    /** The modules a people-only playbook needs, and nothing that could describe the realm. */
    private static final Set<String> ALLOWED_MODULES = Set.of(
            "ansible.builtin.assert",
            "ansible.builtin.uri",
            "ansible.builtin.debug",
            "middleware_automation.keycloak.keycloak_user",
            "middleware_automation.keycloak.keycloak_user_rolemapping",
            "middleware_automation.keycloak.keycloak_user_execute_actions_email");

    private static String playbook;

    @BeforeAll
    static void readPlaybook() {
        playbook = KeycloakConfigFiles.read(KeycloakConfigFiles.PLAYBOOK);
    }

    /**
     * No realm, role, client, flow, scope, component or identity provider is
     * written by the playbook — Terraform is the single description of those.
     */
    @Test
    void thePlaybookDescribesPeopleNotTheRealm() {
        assertThat(modules())
                .as("modules used by %s", KeycloakConfigFiles.PLAYBOOK)
                .isNotEmpty()
                .isSubsetOf(ALLOWED_MODULES);
        assertThat(codeLines())
                .as("the flow is Terraform's: nothing here copies or builds one")
                .noneMatch(line -> line.contains("copyFrom") || line.contains("authenticationExecutions"));
    }

    /** The realm must exist before people are added to it, and its absence stops the run. */
    @Test
    void thePlaybookChecksTheRealmExistsBeforeTouchingAnyone() {
        int check = playbook.indexOf("/.well-known/openid-configuration");
        int firstAccount = playbook.indexOf("middleware_automation.keycloak.keycloak_user:");
        assertThat(check).as("the realm discovery document is fetched").isNotNegative();
        assertThat(firstAccount).isGreaterThan(check);
    }

    /**
     * Accounts are disabled, never deleted: the only {@code state: absent}
     * allowed takes a <i>role</i> away, and the account task says present.
     */
    @Test
    void noAccountIsEverDeleted() {
        for (String task : tasks()) {
            if (task.contains("state: absent")) {
                assertThat(task)
                        .as("state: absent only ever removes a role mapping")
                        .contains("middleware_automation.keycloak.keycloak_user_rolemapping:");
            }
            if (task.contains("middleware_automation.keycloak.keycloak_user:")) {
                assertThat(task).contains("state: present").contains("enabled:");
                assertThat(task)
                        .as("passing email_verified would reset a confirmed address on every run")
                        .doesNotContain("email_verified");
            }
        }
    }

    /**
     * {@code user} is the role every account carries and it opens nothing, so
     * it is never a privilege to grant — and the animateur role is the
     * application's to grant, with the fiche.
     */
    @Test
    void theOrdinaryRoleIsRefusedAsAPrivilege() {
        assertThat(playbook).contains("'user' not in (item.roles | default([]))");
        Matcher granted = Pattern.compile("keycloak_roles_personnel:\\n((?:\\s+- \\S+\\n)+)")
                .matcher(playbook);
        assertThat(granted.find())
                .as("the list of grantable roles is a play variable")
                .isTrue();
        assertThat(granted.group(1).lines().map(line -> line.strip().substring(2)))
                .containsExactlyInAnyOrder("admin", "mcp");
        assertThat(playbook).contains("difference(keycloak_roles_personnel)");

        String example = KeycloakConfigFiles.read(EXAMPLE_VARS);
        assertThat(example.lines().filter(line -> !line.strip().startsWith("#")))
                .as("the example grants no ordinary role")
                .noneMatch(line -> line.matches("\\s*- user\\s*"));
    }

    /**
     * The invitation module sends a mail on every call, so it is guarded by
     * "created by this run": replaying the playbook must not re-invite anyone.
     */
    @Test
    void anInvitationGoesOnlyToAnAccountThisRunCreated() {
        String invitation = tasks().stream()
                .filter(task -> task.contains("keycloak_user_execute_actions_email:"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no invitation task"));
        assertThat(invitation).contains("item.existing").contains("keycloak_envoyer_invitations");
        assertThat(invitation).as("a disabled account is not invited").contains("item.item.actif");
    }

    /** No secret in the versioned files: the example carries placeholders, real values go through vault. */
    @Test
    void theVersionedFilesCarryNoCredential() {
        assertThat(playbook).doesNotContainPattern("(?m)^\\s*auth_password:\\s*(?!\"\\{\\{)\\S");
        assertThat(KeycloakConfigFiles.read(EXAMPLE_VARS)).contains("keycloak_admin_password: changez-moi");
        assertThat(KeycloakConfigFiles.read(Path.of("ansible/.gitignore")))
                .contains("inventory.yml")
                .contains("group_vars/*.yml")
                .contains("!group_vars/*.example.yml");
    }

    /* ------------------------------------------------------------ helpers -- */

    private static List<String> codeLines() {
        return playbook.lines()
                .filter(line -> !line.stripLeading().startsWith("#"))
                .toList();
    }

    /** The fully-qualified module of every task. */
    private static List<String> modules() {
        List<String> found = new ArrayList<>();
        Pattern module = Pattern.compile("^      ([a-z_]+\\.[a-z_]+\\.[a-z_]+):\\s*$");
        for (String line : codeLines()) {
            Matcher matcher = module.matcher(line);
            if (matcher.matches()) {
                found.add(matcher.group(1));
            }
        }
        return found;
    }

    /** The tasks of the play, each from its {@code - name:} to the next one, comments left out. */
    private static List<String> tasks() {
        String code = String.join("\n", codeLines());
        int start = code.indexOf("\n  tasks:\n");
        assertThat(start).as("the play has tasks").isNotNegative();
        String[] parts = code.substring(start).split("\n    - name: ");
        List<String> found = new ArrayList<>();
        for (int i = 1; i < parts.length; i++) {
            found.add(parts[i]);
        }
        return found;
    }
}
