package dev.sylvain.planning.service.espace;

import dev.sylvain.planning.config.ConfigEspaceDeclaration;
import dev.sylvain.planning.service.RateLimitVerdict;
import dev.sylvain.planning.service.SlidingWindowCounter;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Rate limit on the availability declarations (issue #291), the first route
 * that ever writes from the espace animateur — a space open on the Internet
 * whose credential is a URL.
 *
 * <p>Two guards cover the two abuses, and neither replaces the other. Storage
 * is bounded by the domain itself: <b>one pending declaration per animateur</b>
 * (a partial unique index in {@code V59}), so resending a thousand times leaves
 * one row. What is left is the <b>churn</b> — a loop of writes and of admin
 * notifications, at the pace of the network — and that is what this counter
 * stops. Nobody declares their availability twenty times in ten minutes; the
 * ceiling is set where a human correcting themself never meets it.</p>
 *
 * <p>Counted per animateur, like the access codes, and not per IP: the session
 * already names the animateur, and an IP is what a phone changes between two
 * cells. Over the ceiling the answer is {@code 429} with a {@code Retry-After}.
 * Rate limiting per address stays the reverse proxy's job — see
 * {@code docs/securite.md}.</p>
 */
@ApplicationScoped
public class DeclarationRateLimiter {

    private final ConfigEspaceDeclaration config;

    @Inject
    public DeclarationRateLimiter(ConfigEspaceDeclaration config) {
        this.config = config;
    }

    private final SlidingWindowCounter counter = new SlidingWindowCounter();

    /**
     * The covoiturage request of the espace's Covoiturage tab, counted apart:
     * the same ceiling, but correcting one's car must not eat into the sends
     * of one's declaration, nor the other way round.
     */
    private final SlidingWindowCounter carpoolCounter = new SlidingWindowCounter();

    public RateLimitVerdict submit(String animateurId) {
        return counter.use(animateurId, config.maxEnvois(), config.fenetre());
    }

    public RateLimitVerdict submitCarpool(String animateurId) {
        return carpoolCounter.use(animateurId, config.maxEnvois(), config.fenetre());
    }

    /**
     * The absences reported from the espace (« je ne pourrai pas être là »),
     * the second write the espace opened, counted apart on the same ceiling:
     * each report mails the organisation, and a loop of them is exactly the
     * churn this counter exists for.
     */
    private final SlidingWindowCounter reportCounter = new SlidingWindowCounter();

    public RateLimitVerdict submitReport(String animateurId) {
        return reportCounter.use(animateurId, config.maxEnvois(), config.fenetre());
    }
}
