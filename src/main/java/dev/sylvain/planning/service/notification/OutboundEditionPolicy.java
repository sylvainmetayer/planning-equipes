package dev.sylvain.planning.service.notification;

import dev.sylvain.planning.service.EditionContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Optional;

/**
 * The one answer to « may this edition say something outside the
 * application? » for what the instance itself sends out — an outgoing
 * webhook, a weather forecast fetched for it, the admin mail that follows.
 * Every webhook delivery about an edition, and the weather job, ask here and
 * nowhere else.
 *
 * <p>The answer is the active edition (ADR 0072): it alone speaks outward,
 * and between two events none does. This class only names that rule for its
 * callers — {@link EditionContext} holds it — so that a webhook and the
 * weather job read « may emit » rather than « is active », and a change to
 * who may emit has one place to land.</p>
 */
@ApplicationScoped
public class OutboundEditionPolicy {

    private final EditionContext editionContext;

    @Inject
    public OutboundEditionPolicy(EditionContext editionContext) {
        this.editionContext = editionContext;
    }

    /** Whether {@code editionId} may emit outward: whether it is the active edition. */
    public boolean mayEmit(String editionId) {
        return editionContext.isActive(editionId);
    }

    /**
     * The edition the instance's own outward jobs work for — the weather
     * alert — or empty when none may emit: the active edition, if any.
     */
    public Optional<String> emittingEdition() {
        return editionContext.activeEditionId();
    }
}
