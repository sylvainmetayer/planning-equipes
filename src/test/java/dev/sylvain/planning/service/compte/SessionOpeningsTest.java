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
        for (int i = 0; i < SessionOpenings.MAX_ENTRIES; i++) {
            openings.record("s" + i, start.plusMillis(i));
        }

        openings.record("admin", start.plusSeconds(10));

        assertThat(openings.openedAt("admin")).isPresent();
        assertThat(openings.openedAt("s0")).isEmpty();
        assertThat(openings.openedAt("s1")).isPresent();
    }

    @Test
    void aLogoutReachesEveryValueTheSessionTookAndNoOtherSession() {
        SessionOpenings openings = new SessionOpenings();
        openings.record("copie", Instant.now());
        openings.renew("copie", "courant");
        openings.renew("courant", "suivant");
        openings.record("autre-appareil", Instant.now());

        openings.logOut("courant");

        assertThat(openings.loggedOut("copie")).isTrue();
        assertThat(openings.loggedOut("courant")).isTrue();
        assertThat(openings.loggedOut("suivant")).isTrue();
        assertThat(openings.loggedOut("autre-appareil")).isFalse();
        assertThat(openings.openedAt("copie")).isPresent();
    }

    @Test
    void aValueNeverNotedIsNotRecorded() {
        SessionOpenings openings = new SessionOpenings();

        openings.logOut("inconnu");
        openings.logOut(null);

        assertThat(openings.loggedOut("inconnu")).isFalse();
        assertThat(openings.openedAt("inconnu")).isEmpty();
        assertThat(openings.loggedOut(null)).isFalse();
    }

    @Test
    void aBurstOfLoginsDoesNotBringALoggedOutSessionBack() {
        SessionOpenings openings = new SessionOpenings();
        Instant start = Instant.now();
        openings.record("deconnecte", start.minusSeconds(60));
        openings.logOut("deconnecte");
        for (int i = 0; i < SessionOpenings.MAX_ENTRIES; i++) {
            openings.record("s" + i, start.plusMillis(i));
        }

        assertThat(openings.loggedOut("deconnecte")).isTrue();
    }
}
