package dev.sylvain.planning.service.espace;

import dev.sylvain.planning.config.ConfigEspaceCode;
import dev.sylvain.planning.service.RateLimitVerdict;
import dev.sylvain.planning.service.SlidingWindowCounter;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Rate limit on the espace animateur access-code requests.
 *
 * <p>Without it, {@code POST /api/espace-animateur/{token}/code} is a mail send
 * anybody holding the link can trigger in a loop: it drowns the animateur's
 * inbox under codes, exhausts the SMTP server quota, and resets the code
 * attempt counter to five on every request.</p>
 *
 * <p>What is counted are the codes that were <b>never used</b>: opening the
 * session with the code received clears the counter. An animateur logging in
 * normally, even often, therefore never reaches the ceiling — only the
 * accumulation of requests with no follow-up, which is exactly the abuse, leads
 * there. The window slides from the first request left without effect.</p>
 *
 * <p>The arithmetic lives in {@link SlidingWindowCounter}, shared with
 * {@link DeclarationRateLimiter}; what belongs here is the abuse being guarded
 * and the configuration that sizes it.</p>
 */
@ApplicationScoped
public class CodeRequestLimiter {

    @Inject
    ConfigEspaceCode config;

    private final SlidingWindowCounter counter = new SlidingWindowCounter();

    /**
     * Consumes one rate-limit token for {@code key}. A negative verdict carries
     * the delay left before the window reopens, ready to be used as is for
     * {@code Retry-After}.
     */
    public RateLimitVerdict request(String key) {
        return counter.use(key, config.maxDemandes(), config.fenetre());
    }

    /** The code was used: the run of requests with no follow-up stops there. */
    public void oublier(String key) {
        counter.forget(key);
    }
}
