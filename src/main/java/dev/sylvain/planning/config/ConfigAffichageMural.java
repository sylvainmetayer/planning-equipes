package dev.sylvain.planning.config;

import io.smallrye.config.ConfigMapping;
import java.time.Duration;

/**
 * Guards on the public wall display route ({@code GET /api/mural/{token}}):
 * how many refused reads one source address may make before its unknown tokens
 * are turned away, and how many reads one valid link may serve. See
 * {@code AffichageMuralRateLimiter}.
 */
@ConfigMapping(prefix = "planning.affichage-mural")
public interface ConfigAffichageMural {

    /**
     * Refused reads (unknown or revoked token) tolerated per address before the
     * address is locked out of unknown tokens for {@link #window()} after the
     * last one; zero or less switches the lockout off.
     */
    int maxRefused();

    /** Reads one valid link may serve per {@link #window()}; zero or less switches the ceiling off. */
    int maxReadsPerLink();

    Duration window();
}
