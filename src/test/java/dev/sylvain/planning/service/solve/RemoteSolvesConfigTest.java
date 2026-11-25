package dev.sylvain.planning.service.solve;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.sylvain.planning.config.ConfigSolverWorker;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** What an instance may say about where it solves (ADR 0086), refused at boot otherwise. */
class RemoteSolvesConfigTest {

    private record Settings(Optional<String> url, Optional<String> secret, Optional<String> tlsFingerprint)
            implements ConfigSolverWorker {

        @Override
        public String defaultPlace() {
            return "local";
        }
    }

    private static Settings permanent(String url, String secret) {
        return new Settings(Optional.ofNullable(url), Optional.ofNullable(secret), Optional.empty());
    }

    @Test
    void nothingConfiguredOrAPermanentWorkerIsAccepted() {
        assertThatCode(() -> RemoteSolves.check(permanent(null, null))).doesNotThrowAnyException();
        assertThatCode(() -> RemoteSolves.check(permanent("https://worker.example:8443", "s")))
                .doesNotThrowAnyException();
        assertThatCode(() -> RemoteSolves.check(permanent("http://localhost:8443", "s")))
                .doesNotThrowAnyException();
    }

    @Test
    void anAddressWithoutItsSecretIsRefused() {
        assertThatThrownBy(() -> RemoteSolves.check(permanent("https://worker.example", null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SOLVER_WORKER_SECRET");
    }

    @Test
    void plainHttpIsRefusedAcrossAnythingPublic() {
        assertThatThrownBy(() -> RemoteSolves.check(permanent("http://10.0.0.2:8443", "s")))
                .as("a worker's secret and problem cross the network: loopback only")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SOLVER_WORKER_URL must be https");
    }
}
