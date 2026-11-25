package dev.sylvain.planning.service.compte;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.runtime.QuarkusPrincipal;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The recent sign-in control of a form session, mode by mode, without a container. */
class RecentAuthenticationTest {

    private final SessionOpenings openings = new SessionOpenings();

    private final RecentAuthentication recent = new RecentAuthentication(openings, Duration.ofMinutes(10));

    /** A local account's session carries the attribute its sign-in set; the break-glass one does not. */
    private static SecurityIdentity identity(String principal) {
        QuarkusSecurityIdentity.Builder builder =
                QuarkusSecurityIdentity.builder().setPrincipal(new QuarkusPrincipal(principal));
        if (!"admin".equals(principal)) {
            builder.addAttribute(Compte.LOCAL_ATTRIBUTE, Boolean.TRUE);
        }
        return builder.build();
    }

    private String session(Duration ago) {
        String session = UUID.randomUUID().toString();
        openings.record(session, Instant.now().minus(ago));
        return session;
    }

    @Test
    void aFormSessionOpenedRecentlyPassesWhateverItsMode() {
        for (String principal : new String[] {"admin", "personne@example.org#3"}) {
            assertThat(recent.reauthenticationRequired(identity(principal), session(Duration.ofMinutes(9))))
                    .as(principal)
                    .isEmpty();
        }
    }

    @Test
    void anOldUnknownOrMissingSessionIsRefusedWithTheModeOfTheSession() {
        String old = session(Duration.ofMinutes(11));
        String unknown = UUID.randomUUID().toString();

        assertThat(recent.reauthenticationRequired(identity("admin"), old))
                .contains(RecentAuthentication.Mode.BREAK_GLASS);
        assertThat(recent.reauthenticationRequired(identity("admin"), unknown))
                .contains(RecentAuthentication.Mode.BREAK_GLASS);
        assertThat(recent.reauthenticationRequired(identity("admin"), null))
                .contains(RecentAuthentication.Mode.BREAK_GLASS);
        assertThat(recent.reauthenticationRequired(identity("personne@example.org#3"), old))
                .contains(RecentAuthentication.Mode.LOCAL);
    }

    @Test
    void anInstantInTheFutureIsNotRecent() {
        assertThat(recent.reauthenticationRequired(identity("admin"), session(Duration.ofHours(-1))))
                .isPresent();
    }

    @Test
    void theCookieIsNeverKeptInClear() {
        String session = UUID.randomUUID().toString();
        openings.record(session, Instant.now());

        assertThat(openings.openedAt(session)).isPresent();
        assertThat(openings.openedAt(session + "x")).isEmpty();
        assertThat(openings.openedAt("")).isEmpty();
    }
}
