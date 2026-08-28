package dev.sylvain.planning.config;

import io.quarkus.runtime.LaunchMode;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Whether this server was launched with {@code quarkus:dev} — the one fact that
 * separates a developer's machine from a deployed instance.
 *
 * <p>It used to be read inline where it was needed, which was fine while the
 * only consumer was "may the interface link to the Dev UI". It stopped being
 * fine the moment a <b>capability</b> hung on it: freezing the server's notion
 * of today (see {@code JourJClock}) has to be impossible on a deployed
 * instance, and a rule that guards something must be testable — a static call
 * to {@link LaunchMode#current()} cannot be, since a {@code @QuarkusTest} runs
 * in {@link LaunchMode#TEST} and no test can make it answer otherwise.</p>
 *
 * <p>As a bean it can be replaced in a test, which is what lets the suite prove
 * both halves of the rule: the capability works where it is allowed, and is
 * refused where it is not. Non-final on purpose, for the same reason.</p>
 */
@ApplicationScoped
public class DevMode {

    /** True only under {@code quarkus:dev}; false in test, in JVM and in native builds. */
    public boolean isActive() {
        return LaunchMode.current() == LaunchMode.DEVELOPMENT;
    }
}
