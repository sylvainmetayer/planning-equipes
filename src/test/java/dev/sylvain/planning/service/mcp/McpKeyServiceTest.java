package dev.sylvain.planning.service.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.sylvain.planning.config.ConfigMcp;
import dev.sylvain.planning.service.BusinessError;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.time.Instant;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The rotation rules, against the real table. */
@QuarkusTest
class McpKeyServiceTest {

    @Inject
    McpKeyService keys;

    @Inject
    ConfigMcp config;

    @Inject
    DataSource dataSource;

    @BeforeEach
    @AfterEach
    void clean() throws Exception {
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement("DELETE FROM mcp_cle")) {
            ps.executeUpdate();
        }
        keys.invalidate();
    }

    private String configured() {
        return config.apiKey().orElseThrow();
    }

    @Test
    void theConfiguredKeyWorksUntilOneIsGenerated() {
        assertThat(keys.accepts(configured())).isTrue();
        assertThat(keys.status().origin()).isEqualTo(McpKeyService.Origin.CONFIG);
        assertThat(keys.accepts("autre")).isFalse();
        assertThat(keys.accepts(null)).isFalse();
        assertThat(keys.accepts("")).isFalse();
    }

    @Test
    void immediateRotationStopsTheOldKeyAtOnce() {
        McpKeyService.Rotation rotation = keys.rotate(0);

        assertThat(rotation.key()).startsWith("mcp_");
        assertThat(keys.accepts(rotation.key())).isTrue();
        assertThat(keys.accepts(configured())).isFalse();
        assertThat(keys.status().origin()).isEqualTo(McpKeyService.Origin.GENERATED);
        assertThat(keys.status().previousExpiresAt()).isNull();
    }

    @Test
    void anOverlapKeepsTheOldKeyForItsDuration() {
        McpKeyService.Rotation rotation = keys.rotate(30);

        assertThat(keys.accepts(configured())).isTrue();
        assertThat(keys.accepts(rotation.key())).isTrue();
        assertThat(rotation.previousExpiresAt())
                .isBetween(
                        Instant.now().plus(Duration.ofMinutes(29)),
                        Instant.now().plus(Duration.ofMinutes(31)));
        assertThat(keys.status().previousExpiresAt())
                .isBetween(
                        rotation.previousExpiresAt().minusSeconds(1),
                        rotation.previousExpiresAt().plusSeconds(1));
    }

    @Test
    void aSecondRotationRetiresTheFirstGeneratedKeyAndNeverRevivesTheConfiguredOne() {
        McpKeyService.Rotation first = keys.rotate(0);
        McpKeyService.Rotation second = keys.rotate(0);

        assertThat(keys.accepts(first.key())).isFalse();
        assertThat(keys.accepts(second.key())).isTrue();
        assertThat(keys.accepts(configured())).isFalse();
    }

    @Test
    void anImmediateRotationCutsAnOverlapShort() {
        McpKeyService.Rotation first = keys.rotate(60);
        keys.rotate(0);

        assertThat(keys.accepts(first.key())).isFalse();
        assertThat(keys.accepts(configured())).isFalse();
    }

    @Test
    void theOverlapIsBounded() {
        assertThatThrownBy(() -> keys.rotate(-1)).isInstanceOf(BusinessError.Invalid.class);
        assertThatThrownBy(() -> keys.rotate(McpKeyService.MAX_OVERLAP_MINUTES + 1))
                .isInstanceOf(BusinessError.Invalid.class);
    }

    @Test
    void onlyTheFingerprintIsStored() throws Exception {
        String key = keys.rotate(0).key();
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement("SELECT string_agg(empreinte || id, ',') FROM mcp_cle");
                var rs = ps.executeQuery()) {
            rs.next();
            assertThat(rs.getString(1)).doesNotContain(key);
        }
    }
}
