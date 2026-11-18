package dev.sylvain.planning.service.solve;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.sylvain.planning.config.ConfigSolverWorker;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** What an instance may say about where it solves (ADR 0086), refused at boot otherwise. */
class RemoteSolvesConfigTest {

    private record Settings(
            Optional<String> url,
            Optional<String> secret,
            Optional<String> tlsFingerprint,
            Optional<String> hookUrl,
            Optional<String> hookSecret)
            implements ConfigSolverWorker {

        @Override
        public String instance() {
            return "planning";
        }

        @Override
        public String defaultPlace() {
            return "local";
        }

        @Override
        public Duration startTimeout() {
            return Duration.ofMinutes(5);
        }

        @Override
        public Duration idleTimeout() {
            return Duration.ofMinutes(45);
        }
    }

    private static Settings permanent(String url, String secret) {
        return new Settings(
                Optional.ofNullable(url),
                Optional.ofNullable(secret),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
    }

    private static Settings onDemand(String hookUrl, String hookSecret) {
        return new Settings(
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.ofNullable(hookUrl),
                Optional.ofNullable(hookSecret));
    }

    @Test
    void nothingConfiguredAPermanentWorkerOrALauncherAreEachAccepted() {
        assertThatCode(() -> RemoteSolves.check(permanent(null, null))).doesNotThrowAnyException();
        assertThatCode(() -> RemoteSolves.check(permanent("https://worker.example:8443", "s")))
                .doesNotThrowAnyException();
        assertThatCode(() -> RemoteSolves.check(permanent("http://localhost:8443", "s")))
                .doesNotThrowAnyException();
        assertThatCode(() -> RemoteSolves.check(onDemand("https://launcher.example", "s")))
                .doesNotThrowAnyException();
        assertThatCode(() -> RemoteSolves.check(onDemand("http://10.0.0.2:8099", "s")))
                .doesNotThrowAnyException();
        assertThatCode(() -> RemoteSolves.check(onDemand("http://127.0.0.1:8099", "s")))
                .doesNotThrowAnyException();
    }

    @Test
    void aWorkerAndALauncherTogetherAreRefused() {
        Settings both = new Settings(
                Optional.of("https://worker.example"),
                Optional.of("s"),
                Optional.empty(),
                Optional.of("https://launcher.example"),
                Optional.of("s"));

        assertThatThrownBy(() -> RemoteSolves.check(both))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exclude each other");
    }

    @Test
    void anAddressWithoutItsSecretIsRefused() {
        assertThatThrownBy(() -> RemoteSolves.check(permanent("https://worker.example", null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SOLVER_WORKER_SECRET");
        assertThatThrownBy(() -> RemoteSolves.check(onDemand("https://launcher.example", null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SOLVER_WORKER_HOOK_SECRET");
    }

    @Test
    void plainHttpIsRefusedAcrossAnythingPublic() {
        assertThatThrownBy(() -> RemoteSolves.check(permanent("http://10.0.0.2:8443", "s")))
                .as("a worker's secret and problem cross the network: loopback only")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SOLVER_WORKER_URL must be https");
        assertThatThrownBy(() -> RemoteSolves.check(onDemand("http://203.0.113.7:8099", "s")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SOLVER_WORKER_HOOK_URL must be https");
    }

    @Test
    void onlyThisMachineAndPrivateNetworksCountAsPrivate() {
        assertThat(RemoteSolves.privateOrLoopback("127.0.0.1")).isTrue();
        assertThat(RemoteSolves.privateOrLoopback("192.168.1.10")).isTrue();
        assertThat(RemoteSolves.privateOrLoopback("169.254.1.1")).isTrue();
        assertThat(RemoteSolves.privateOrLoopback("203.0.113.7")).isFalse();
        assertThat(RemoteSolves.privateOrLoopback("no-such-host.invalid")).isFalse();
        assertThat(RemoteSolves.privateOrLoopback(null)).isFalse();
    }
}
