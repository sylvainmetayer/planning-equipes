package dev.sylvain.planning.service.demo;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.startsWith;

import dev.sylvain.planning.TestPasswords;
import dev.sylvain.planning.config.ConfigMcp;
import dev.sylvain.planning.service.BusinessError;
import dev.sylvain.planning.service.compte.CompteService;
import dev.sylvain.planning.service.compte.RoleHabilitation;
import dev.sylvain.planning.service.mcp.McpKeyService;
import dev.sylvain.planning.service.weather.WeatherAlertService;
import dev.sylvain.planning.service.webhook.WebhookFormat;
import dev.sylvain.planning.service.webhook.WebhookService;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.Test;

/**
 * A demo instance (ADR 0130), booted as the homelab boots it: the banner is
 * announced to anybody, accounts, rights, passwords and MCP keys are refused
 * with a sentence that says why, and nothing can leave.
 */
@QuarkusTest
@TestProfile(DemoInstanceGuardTest.Profile.class)
class DemoInstanceGuardTest {

    /** The flag alone: everything else it implies must follow from it. */
    public static class Profile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("DEMO_INSTANCE", "true");
        }
    }

    private static final String REFUSAL = "Instance de démonstration : ";

    @Inject
    CompteService comptes;

    @Inject
    McpKeyService mcpKeys;

    @Inject
    ConfigMcp mcp;

    @Inject
    WebhookService webhooks;

    @Inject
    WeatherAlertService weather;

    @Test
    void theConfigurationAnnouncesTheDemoToAnybody() {
        given().get("/api/config").then().statusCode(200).body("demoInstance", equalTo(true));
    }

    @Test
    @TestSecurity(user = "admin", roles = "admin")
    void noAccountIsCreatedAndNoPasswordChosen() {
        given().header("X-Edition-Id", "E1")
                .contentType(ContentType.JSON)
                .body("{\"email\":\"visiteur@example.org\"}")
                .post("/api/comptes")
                .then()
                .statusCode(409)
                .body("message", startsWith(REFUSAL));
        given().contentType(ContentType.JSON)
                .body(Map.of("token", "un-lien", "password", TestPasswords.LONG))
                .post("/api/auth/mot-de-passe")
                .then()
                .statusCode(409)
                .body("message", startsWith(REFUSAL));
        given().contentType(ContentType.JSON)
                .body(Map.of("email", "rh@example.org"))
                .post("/api/auth/mot-de-passe/oubli")
                .then()
                .statusCode(409);
    }

    @Test
    void noRightChanges() {
        assertThatThrownBy(() -> comptes.grant("un-compte", RoleHabilitation.ADMIN, null, null))
                .isInstanceOf(BusinessError.Conflict.class)
                .hasMessageStartingWith(REFUSAL);
        assertThatThrownBy(() -> comptes.withdraw("un-compte", "un-droit"))
                .isInstanceOf(BusinessError.Conflict.class)
                .hasMessageStartingWith(REFUSAL);
        assertThatThrownBy(() -> comptes.deactivate("un-compte"))
                .isInstanceOf(BusinessError.Conflict.class)
                .hasMessageStartingWith(REFUSAL);
    }

    @Test
    void noMcpKeyIsGeneratedNorConfigured() {
        assertThatThrownBy(() -> mcpKeys.rotate(0))
                .isInstanceOf(BusinessError.Conflict.class)
                .hasMessageStartingWith(REFUSAL);
        // The %test profile configures one: on a demo it opens nothing, and is not revealed.
        assertThat(mcp.apiKey()).isPresent();
        assertThat(mcpKeys.configured()).isEmpty();
        assertThat(mcpKeys.accepts(mcp.apiKey().get())).isFalse();
        given().header("X-Edition-Id", "E1")
                .header("X-MCP-Api-Key", mcp.apiKey().get())
                .post("/mcp")
                .then()
                .statusCode(401);
        given().header("X-Edition-Id", "E1")
                .header("Authorization", "Bearer " + mcp.apiKey().get())
                .post("/mcp")
                .then()
                .statusCode(401);
        assertThat(ConfigProvider.getConfig().getValue("quarkus.oidc.mcptransport.tenant-enabled", Boolean.class))
                .isFalse();
    }

    @Test
    void nothingLeaves() {
        assertThat(ConfigProvider.getConfig().getValue("quarkus.mailer.mock", Boolean.class))
                .isTrue();
        assertThatThrownBy(() -> webhooks.create(new WebhookService.WebhookRequest(
                        "n8n",
                        WebhookFormat.GENERIC,
                        "https://hooks.example.org/x",
                        null,
                        null,
                        List.of(),
                        true,
                        null)))
                .isInstanceOf(BusinessError.Conflict.class)
                .hasMessageStartingWith(REFUSAL);
        assertThatThrownBy(() -> weather.test())
                .isInstanceOf(BusinessError.Conflict.class)
                .hasMessageContaining("METEO_ENABLED=false");
    }
}
