package dev.sylvain.planning.service.compte;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class SessionOpeningsTest {

    @Test
    void aRenewedCookieKeepsItsOpeningAndTheOldOneStaysForParallelRequests() {
        SessionOpenings openings = new SessionOpenings();
        Instant opened = Instant.now().minusSeconds(120);
        openings.record("ancien", opened);

        openings.renew("ancien", "nouveau");
        openings.renew("inconnu", "autre");

        assertThat(openings.openedAt("nouveau")).contains(opened);
        assertThat(openings.openedAt("ancien")).contains(opened);
        assertThat(openings.openedAt("autre")).isEmpty();
        assertThat(openings.openedAt(null)).isEmpty();
    }

    @Test
    void aBurstOfLoginsEvictsTheOldestRatherThanTheNextLogin() {
        SessionOpenings openings = new SessionOpenings();
        Instant start = Instant.now();
        for (int i = 0; i < 2_000; i++) {
            openings.record("s" + i, start.plusMillis(i));
        }

        openings.record("admin", start.plusSeconds(10));

        assertThat(openings.openedAt("admin")).isPresent();
        assertThat(openings.openedAt("s0")).isEmpty();
        assertThat(openings.openedAt("s1")).isPresent();
    }
}
