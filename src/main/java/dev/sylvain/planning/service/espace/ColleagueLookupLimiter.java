package dev.sylvain.planning.service.espace;

import dev.sylvain.planning.config.ConfigEspaceCollegues;
import dev.sylvain.planning.service.RateLimitVerdict;
import dev.sylvain.planning.service.SlidingWindowCounter;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Rate limit on a colleague's seats, the « son créneau que je veux en
 * échange » picker of a directed swap.
 *
 * <p>Each answer is harmless on its own — slots and stands, what the printed
 * global planning already circulates — and the roster comes with the espace
 * anyway. What one session could do is ask for every colleague in a row and
 * rebuild the whole event's grid. The ceiling stops that, and only that.</p>
 *
 * <p>It counts <b>distinct</b> colleagues, not requests: picking the same
 * person again, reloading the page, hesitating between three — none of it
 * costs anything. Per animateur, like the declarations: the session names
 * them, and an IP is what a phone changes between two cells. Over the ceiling
 * the picker stays empty with a delay, and the swap request itself still goes
 * through without a seat wanted in return.</p>
 */
@ApplicationScoped
public class ColleagueLookupLimiter {

    @Inject
    ConfigEspaceCollegues config;

    private final SlidingWindowCounter counter = new SlidingWindowCounter();

    public RateLimitVerdict lookUp(String animateurId, String collegueId) {
        return counter.useDistinct(animateurId, collegueId, config.maxCollegues(), config.fenetre());
    }
}
