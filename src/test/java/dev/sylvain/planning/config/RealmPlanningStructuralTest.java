package dev.sylvain.planning.config;

import static dev.sylvain.planning.config.KeycloakConfigFiles.client;
import static dev.sylvain.planning.config.KeycloakConfigFiles.flow;
import static dev.sylvain.planning.config.KeycloakConfigFiles.stream;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Holds {@code docker/keycloak/realm-planning.json} — the realm the dev stack
 * imports — against what the application expects of a realm.
 *
 * <p>The realm is configuration of another program, so nothing in the Java
 * build would notice it drifting: a role renamed, an audience mapper dropped,
 * a second factor quietly turned into a suggestion. The failure then shows up
 * as "I can't sign in" on someone's machine, or — much worse for the last one
 * — as nothing at all.</p>
 *
 * <p>This is not a substitute for starting Keycloak: only a real import proves
 * the file's semantics, which is the browser suite's job. What is checked here
 * is the half that belongs to this repository — the names and values on which
 * the two programs have to agree. It reads files only, so it runs in
 * {@code -Punit} with neither Docker nor a database.</p>
 */
class RealmPlanningStructuralTest {

    /** Keycloak's ordinary "this person exists" role, which must stay powerless. */
    private static final String ORDINARY_ROLE = "user";

    private static JsonNode realm;

    @BeforeAll
    static void readRealm() {
        assertThat(KeycloakConfigFiles.REALM).as("the dev realm is versioned").exists();
        realm = KeycloakConfigFiles.realm();
    }

    /**
     * The constraint the identity model rests on. Keycloak refuses to combine
     * these two, so one account is one <b>person</b> and never one fiche.
     * Flipping either line would silently make the other design the one in
     * force.
     */
    @Test
    void theRealmLetsPeopleSignInWithTheirEmailAndKeepsAddressesUnique() {
        assertThat(realm.path("realm").asText())
                .isEqualTo(KeycloakConfigFiles.devProperty("planning.auth.oidc.provisioning.realm"));
        assertThat(realm.path("loginWithEmailAllowed").asBoolean()).isTrue();
        assertThat(realm.path("duplicateEmailsAllowed").asBoolean()).isFalse();
        // Nobody registers themselves into the realm of a staffing tool.
        assertThat(realm.path("registrationAllowed").asBoolean()).isFalse();
    }

    /** The four realm roles of the contract, no more and no fewer. */
    @Test
    void theRealmDeclaresExactlyTheFourRolesOfTheContract() {
        assertThat(KeycloakConfigFiles.realmRoles(realm))
                .containsExactlyInAnyOrder(
                        "admin",
                        "mcp",
                        KeycloakConfigFiles.property("planning.auth.oidc.animateur-role"),
                        ORDINARY_ROLE);
    }

    /** Every role an HTTP policy names has to exist, or the policy guards a door nobody can open. */
    @Test
    void everyRoleTheApplicationRequiresIsDeclared() {
        List<String> declared = KeycloakConfigFiles.realmRoles(realm);
        assertThat(KeycloakConfigFiles.listProperty("quarkus.http.auth.policy.role-admin.roles-allowed"))
                .as("/api/* is admin only")
                .containsExactly("admin");
        assertThat(KeycloakConfigFiles.listProperty("quarkus.http.auth.policy.cle-mcp.roles-allowed"))
                .as("/mcp is mcp only, never admin")
                .containsExactly("mcp");
        for (String policy : KeycloakConfigFiles.keys("quarkus.http.auth.policy.", ".roles-allowed")) {
            assertThat(declared).as("roles named by %s", policy).containsAll(KeycloakConfigFiles.listProperty(policy));
        }
    }

    /**
     * The realm's ordinary role grants nothing — checked against every policy
     * rather than trusted.
     *
     * <p>This is the guarantee least privilege rests on: adding a role to the
     * realm must not add access anywhere. If {@code user} ever appeared in a
     * {@code roles-allowed}, every account the realm holds would gain that
     * access at once, and nothing else in the build would notice.</p>
     */
    @Test
    void theOrdinaryRealmRoleOpensNothing() {
        for (String policy : KeycloakConfigFiles.keys("quarkus.http.auth.policy.", ".roles-allowed")) {
            assertThat(KeycloakConfigFiles.listProperty(policy)).as(policy).doesNotContain(ORDINARY_ROLE);
        }
        assertThat(KeycloakConfigFiles.property("planning.auth.oidc.animateur-role"))
                .as("the espace is not built on the realm's ordinary role")
                .isNotEqualTo(ORDINARY_ROLE);
        assertThat(KeycloakConfigFiles.realmRoles(realm))
                .as("the ordinary role stays declared: it is what an account with no access carries")
                .contains(ORDINARY_ROLE);
    }

    @Test
    void theLoginClientIsTheOneTheApplicationUses() {
        JsonNode app = client(realm, KeycloakConfigFiles.property("quarkus.oidc.client-id"));
        assertThat(app.path("publicClient").asBoolean())
                .as("a confidential client")
                .isFalse();
        assertThat(app.path("standardFlowEnabled").asBoolean()).isTrue();
        assertThat(app.path("directAccessGrantsEnabled").asBoolean())
                .as("the application never receives anybody's password")
                .isFalse();
    }

    /**
     * A client on which the realm enforces PKCE, and an application that does
     * not send a challenge, make a pair that cannot sign anybody in.
     *
     * <p>Keycloak reads {@code pkce.code.challenge.method} as "refuse an
     * authorization request without a code challenge"; Quarkus sends one only
     * under {@code quarkus.oidc.authentication.pkce-required}. Set one without
     * the other and Keycloak answers {@code Missing parameter:
     * code_challenge_method} before showing its login screen — the reason is
     * in the server log of the <i>other</i> program.</p>
     */
    @Test
    void thePkceTheRealmDemandsIsThePkceTheApplicationSends() {
        JsonNode app = client(realm, KeycloakConfigFiles.property("quarkus.oidc.client-id"));
        assertThat(app.path("attributes").path("pkce.code.challenge.method").asText())
                .isEqualTo("S256");
        assertThat(KeycloakConfigFiles.property("quarkus.oidc.authentication.pkce-required"))
                .as("the realm demands PKCE on the login client, the application must send it")
                .isEqualTo("true");
    }

    /**
     * Keycloak puts {@code realm_access.roles} in the <i>access</i> token only,
     * while Quarkus reads a web-app's roles from the ID token by default.
     * Without these two lines everybody signs in with zero roles — the
     * administrator clears the second factor and lands on "Access denied",
     * which looks like a rights problem and is not one.
     */
    @Test
    void theApplicationReadsTheRolesWhereKeycloakPutsThem() {
        assertThat(KeycloakConfigFiles.property("quarkus.oidc.roles.source")).isEqualTo("accesstoken");
        assertThat(KeycloakConfigFiles.property("quarkus.oidc.roles.role-claim-path"))
                .isEqualTo("realm_access/roles");
    }

    /**
     * Quarkus encrypts the PKCE code verifier into the state cookie with the
     * client secret as the key — but only if that secret reaches 32
     * characters. Below that it draws a random key at startup, and a login
     * begun on one instance can no longer be finished on another.
     */
    @Test
    void theDevelopmentSecretIsLongEnoughToKeyTheStateCookie() {
        assertThat(client(realm, "planning-app").path("secret").asText()).hasSizeGreaterThanOrEqualTo(32);
    }

    /**
     * The dev defaults — of {@code quarkus:dev} and of the compose stack alike
     * — must be the realm's own secrets, character for character.
     *
     * <p>Nothing else holds these files together, and the drift is invisible:
     * the application starts cleanly and Keycloak refuses the code exchange
     * afterwards with an {@code invalid_client_credentials} logged by the
     * <i>other</i> program, which reads as a broken login rather than a
     * missing default.</p>
     */
    @Test
    void theDevelopmentDefaultsCarryTheSecretsOfTheVersionedRealm() {
        String appSecret = client(realm, "planning-app").path("secret").asText();
        String provisioningSecret =
                client(realm, "planning-provisioning").path("secret").asText();

        assertThat(KeycloakConfigFiles.devProperty("quarkus.oidc.credentials.secret"))
                .as("%dev web client secret")
                .isEqualTo(appSecret);
        assertThat(KeycloakConfigFiles.devProperty("planning.auth.oidc.provisioning.client-secret"))
                .as("%dev provisioning secret")
                .isEqualTo(provisioningSecret);

        String compose = KeycloakConfigFiles.read(Path.of("docker-compose.yml"));
        assertThat(composeDefault(compose, "OIDC_CLIENT_SECRET")).isEqualTo(appSecret);
        assertThat(composeDefault(compose, "OIDC_PROVISIONING_CLIENT_SECRET")).isEqualTo(provisioningSecret);
        assertThat(composeDefault(compose, "OIDC_CLIENT_ID")).isEqualTo("planning-app");
        assertThat(composeDefault(compose, "OIDC_PROVISIONING_CLIENT_ID")).isEqualTo("planning-provisioning");
    }

    /** The {@code default} of a {@code VARIABLE: ${VARIABLE:-default}} line of a compose file. */
    private static String composeDefault(String compose, String variable) {
        Matcher found = Pattern.compile(
                        "^\\s+" + variable + ": \\$\\{" + variable + ":-(?<value>[^}]*)}$", Pattern.MULTILINE)
                .matcher(compose);
        assertThat(found.find())
                .as("docker-compose.yml sets a default for %s", variable)
                .isTrue();
        return found.group("value");
    }

    /**
     * Audience validation is what stops a token obtained for any other client
     * of the same realm from opening the MCP tools. It needs two halves: a
     * client to name, and a mapper that puts its name in the tokens.
     */
    @Test
    void theMcpAudienceIsBothDeclaredAndMapped() {
        String audience = KeycloakConfigFiles.property("quarkus.oidc.mcptransport.token.audience");
        client(realm, audience);

        List<String> mapped = stream(realm.path("clients"))
                .flatMap(candidate -> stream(candidate.path("protocolMappers")))
                .filter(mapper -> "oidc-audience-mapper"
                        .equals(mapper.path("protocolMapper").asText()))
                .map(mapper ->
                        mapper.path("config").path("included.client.audience").asText())
                .toList();
        assertThat(mapped).contains(audience);

        JsonNode automated = client(realm, "planning-mcp-client");
        assertThat(stream(automated.path("protocolMappers"))
                        .map(mapper -> mapper.path("config")
                                .path("included.client.audience")
                                .asText()))
                .as("the client_credentials client gets the audience")
                .contains(audience);
        JsonNode interactive = client(realm, "planning-mcp-public");
        assertThat(interactive.path("publicClient").asBoolean()).isTrue();
        assertThat(interactive
                        .path("attributes")
                        .path("pkce.code.challenge.method")
                        .asText())
                .as("a public client without PKCE hands its code to whoever intercepts it")
                .isEqualTo("S256");
    }

    /**
     * The realm must declare <b>no</b> {@code clientScopes} array — which reads
     * like an omission and is the opposite.
     *
     * <p>Keycloak builds {@code email}, {@code profile}, {@code roles},
     * {@code acr}, {@code basic} and {@code web-origins} itself on import, but
     * only when the file leaves the key out entirely. Listing a scope of one's
     * own therefore <i>replaces</i> them all — tokens then carry no
     * {@code email} and no {@code realm_access.roles}, and every role check
     * stops working over an import that reports success. Hence the audience
     * mapper sits on the MCP clients themselves.</p>
     */
    @Test
    void theRealmLeavesKeycloakToBuildItsOwnClientScopes() {
        assertThat(realm.has("clientScopes")).isFalse();
        assertThat(realm.has("defaultDefaultClientScopes")).isFalse();

        List<String> builtIn = List.of("acr", "basic", "email", "profile", "roles", "web-origins");
        for (JsonNode candidate : realm.path("clients")) {
            assertThat(stream(candidate.path("defaultClientScopes")).map(JsonNode::asText))
                    .as("default scopes of %s", candidate.path("clientId").asText())
                    .isSubsetOf(builtIn);
            assertThat(stream(candidate.path("optionalClientScopes")).map(JsonNode::asText))
                    .as("optional scopes of %s", candidate.path("clientId").asText())
                    .isSubsetOf(builtIn);
        }
    }

    /**
     * Same trap as {@code clientScopes}: declaring {@code requiredActions}
     * <b>replaces</b> the built-in set, taking {@code VERIFY_EMAIL} and
     * {@code UPDATE_PASSWORD} with it — the two actions every invitation asks
     * for.
     */
    @Test
    void theRealmLeavesKeycloakToBuildItsOwnRequiredActions() {
        assertThat(realm.has("requiredActions")).isFalse();
    }

    /**
     * Keycloak stores every {@code description} in a {@code VARCHAR(255)}, and
     * an import that overruns it does not truncate — it dies, with the cause
     * only in the server's own log.
     */
    @Test
    void noDescriptionOverrunsTheColumnKeycloakStoresItIn() {
        assertThat(KeycloakConfigFiles.overlongDescriptions(realm)).isEmpty();
    }

    /**
     * Identity first, then a method: the address alone, then whatever this
     * account has — a passkey, a code sent by e-mail, or a password.
     */
    @Test
    void theBrowserFlowAsksForTheAddressFirstThenOffersThreeMethods() {
        assertThat(realm.path("browserFlow").asText()).isEqualTo(KeycloakConfigFiles.BROWSER_FLOW);
        assertThat(KeycloakConfigFiles.realmFlowTree(realm))
                .containsExactly(
                        "auth-cookie ALTERNATIVE",
                        "identity-provider-redirector ALTERNATIVE",
                        "[browser-planning-forms] ALTERNATIVE",
                        "  auth-username-form REQUIRED",
                        "  [browser-planning-methodes] REQUIRED",
                        "    webauthn-authenticator-passwordless ALTERNATIVE",
                        "    [browser-planning-code-email] ALTERNATIVE",
                        "      planning-code-email REQUIRED {code-email-planning}",
                        "    auth-password-form ALTERNATIVE",
                        "  [browser-planning-otp-admin] CONDITIONAL",
                        "    conditional-user-role REQUIRED {condition-role-admin}",
                        "    auth-otp-form REQUIRED");
    }

    /**
     * The second factor is <b>mandatory for administrators, whatever the
     * method</b>, and that sentence holds only if the conditional sub-flow
     * hangs from the forms sub-flow, <i>after</i> the methods.
     *
     * <p>Two other places look plausible and are both wrong. At the top level
     * the condition runs before anybody is identified, never resolves, and the
     * OTP step never runs. Under the password alone, an administrator signing
     * in with the e-mail code — one factor, the mailbox — skips it. Either way
     * the realm still signs everyone in, which is exactly what makes the
     * regression look like success.</p>
     */
    @Test
    void administratorsGiveASecondFactorWhateverTheMethod() {
        assertThat(subflowsOf(KeycloakConfigFiles.BROWSER_FLOW)).doesNotContain("browser-planning-otp-admin");
        assertThat(subflowsOf("browser-planning-methodes")).doesNotContain("browser-planning-otp-admin");
        assertThat(subflowsOf("browser-planning-forms"))
                .containsExactly("browser-planning-methodes", "browser-planning-otp-admin");

        JsonNode config = stream(realm.path("authenticatorConfig"))
                .filter(candidate ->
                        "condition-role-admin".equals(candidate.path("alias").asText()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("the role condition has no configuration"));
        assertThat(config.path("config").path("condUserRole").asText()).isEqualTo("admin");
        assertThat(config.path("config").path("negate").asText()).isEqualTo("false");
    }

    /**
     * The e-mail code step ships in the repository's image, and the flow must
     * name it by the identifier the extension declares — a mismatch leaves
     * the method on screen and dead on use.
     */
    @Test
    void theEmailCodeStepIsTheOneTheExtensionDeclares() {
        String factory = KeycloakConfigFiles.read(Path.of(
                "keycloak/code-email/src/main/java/dev/sylvain/planning/keycloak/EmailCodeAuthenticatorFactory.java"));
        assertThat(factory).contains("public static final String ID = \"planning-code-email\";");

        JsonNode config = stream(realm.path("authenticatorConfig"))
                .filter(candidate ->
                        "code-email-planning".equals(candidate.path("alias").asText()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("the e-mail code step has no configuration"));
        assertThat(config.path("config").path("validiteSecondes").asInt()).isPositive();
        assertThat(config.path("config").path("essaisMax").asInt()).isPositive();
    }

    /**
     * The demo administrator carries a seeded TOTP secret so a browser suite
     * can compute a valid code instead of needing a phone. Defensible only
     * here: a demonstration account, in a realm rebuilt from this file on every
     * {@code docker compose down -v}. The production realm is Terraform's, and
     * seeds no credential at all.
     */
    @Test
    void theDemoAdministratorHasASeededSecondFactor() {
        JsonNode admin = user("admin@planning.local");
        assertThat(stream(admin.path("realmRoles")).map(JsonNode::asText)).contains("admin");
        assertThat(stream(admin.path("credentials"))
                        .anyMatch(credential ->
                                "otp".equals(credential.path("type").asText())))
                .isTrue();
    }

    /**
     * Demonstration people live at {@code admin@planning.local} and under
     * {@code example.org} (RFC 2606), and nowhere else: a realm file is
     * committed, so a real address in it is published, and a mail sent to it
     * by a curious developer reaches a real person.
     */
    @Test
    void demonstrationAccountsUseReservedAddressesOnly() {
        stream(realm.path("users"))
                .filter(candidate -> !candidate.has("serviceAccountClientId"))
                .forEach(person -> assertThat(person.path("email").asText())
                        .as(person.path("username").asText())
                        .matches("admin@planning\\.local|[a-z.]+@example\\.org"));
    }

    /**
     * Keycloak sends invitations and e-mail codes through the compose stack's
     * Mailpit, which catches everything and delivers nothing.
     */
    @Test
    void theDevelopmentRealmMailsThroughMailpit() {
        assertThat(realm.path("smtpServer").path("host").asText()).isEqualTo("mailpit");
        assertThat(KeycloakConfigFiles.read(Path.of("docker-compose.yml"))).contains("\n  mailpit:\n");
    }

    /**
     * The provisioning service account gets {@code manage-users} on the realm
     * and nothing else: it must be able to create animateur accounts, never to
     * read tokens or touch clients.
     */
    @Test
    void theProvisioningServiceAccountCanOnlyManageUsers() {
        JsonNode account =
                user("service-account-" + KeycloakConfigFiles.property("planning.auth.oidc.provisioning.client-id"));
        assertThat(stream(account.path("clientRoles").path("realm-management")).map(JsonNode::asText))
                .contains("manage-users")
                .doesNotContain("realm-admin", "manage-realm", "manage-clients", "view-realm");
        assertThat(account.path("clientRoles").properties()).hasSize(1);
        assertThat(account.path("realmRoles").isMissingNode()).isTrue();
    }

    /** The automated MCP client carries {@code mcp}, never {@code admin}. */
    @Test
    void theAutomatedMcpClientCarriesTheMcpRoleOnly() {
        JsonNode account = user("service-account-planning-mcp-client");
        assertThat(stream(account.path("realmRoles")).map(JsonNode::asText)).containsExactly("mcp");
    }

    /**
     * What separates a passkey from an ordinary security key: the credential
     * carries the identity, and unlocking it proves a person is there. Relax
     * either and "identity first, then a method" has nothing to offer.
     */
    @Test
    void theWebAuthnPolicyDescribesAPasskeyRatherThanASecondFactorKey() {
        assertThat(realm.path("webAuthnPolicyPasswordlessRequireResidentKey").asText())
                .isEqualTo("Yes");
        assertThat(realm.path("webAuthnPolicyPasswordlessUserVerificationRequirement")
                        .asText())
                .isEqualTo("required");
    }

    /**
     * Google must not be believed about who owns an address: with
     * {@code trustEmail} on, anyone able to create a Google account bearing an
     * animateur's address would be handed that animateur's account.
     */
    @Test
    void googleIsNotBelievedAboutTheAddressItClaims() {
        JsonNode google = stream(realm.path("identityProviders"))
                .filter(idp -> "google".equals(idp.path("alias").asText()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("the google provider is missing"));
        assertThat(google.path("enabled").asBoolean()).isFalse();
        assertThat(google.path("trustEmail").asBoolean()).isFalse();
        assertThat(google.path("firstBrokerLoginFlowAlias").asText()).isEqualTo("first broker login");
        assertThat(google.path("config").path("clientSecret").asText()).isEmpty();
    }

    private static List<String> subflowsOf(String alias) {
        return stream(flow(realm, alias).path("authenticationExecutions"))
                .map(execution -> execution.path("flowAlias").asText(""))
                .filter(subflow -> !subflow.isEmpty())
                .toList();
    }

    private static JsonNode user(String username) {
        return stream(realm.path("users"))
                .filter(candidate -> username.equals(candidate.path("username").asText()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("account missing from the realm: " + username));
    }
}
