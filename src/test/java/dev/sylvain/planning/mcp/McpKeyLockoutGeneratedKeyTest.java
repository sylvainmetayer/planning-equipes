package dev.sylvain.planning.mcp;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.oneOf;

import dev.sylvain.planning.service.mcp.McpKeyService;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Somebody else's wrong keys from the same address (a NAT, an undeclared proxy)
 * must not lock the assistant out: a generated key in force passes the lock,
 * while the guesser stays locked.
 */
@QuarkusTest
@TestProfile(McpKeyLockoutGeneratedKeyTest.Profile.class)
class McpKeyLockoutGeneratedKeyTest {

    private static final int CEILING = 3;

    public static class Profile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "planning.mcp.lockout.max-failures",
                    String.valueOf(CEILING),
                    "planning.mcp.lockout.duration",
                    "PT10M");
        }
    }

    @Inject
    McpKeyService keys;

    @Inject
    DataSource dataSource;

    @BeforeEach
    @AfterEach
    void restoreConfiguredKey() throws Exception {
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement("DELETE FROM mcp_cle")) {
            ps.executeUpdate();
        }
        keys.invalidate();
    }

    @Test
    void aGeneratedKeyPassesTheLockItsAddressEarnedFromWrongKeys() {
        String key = keys.rotate(0).key();
        assertThat(keys.accepts(key)).isTrue();

        for (int i = 0; i < CEILING; i++) {
            withKey("mauvaise-cle").then().statusCode(401);
        }
        withKey("mauvaise-cle").then().statusCode(429);

        withKey(key).then().statusCode(not(oneOf(401, 429)));
        withKey(key).then().statusCode(not(oneOf(401, 429)));

        // Its passing cleared nothing: the guesser is still locked out.
        withKey("autre-mauvaise-cle").then().statusCode(429);
    }

    private static Response withKey(String key) {
        return given().header("X-Edition-Id", "E1")
                .header("X-MCP-Api-Key", key)
                .when()
                .post("/mcp");
    }
}
