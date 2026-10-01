package dev.sylvain.planning.testing;

import dev.sylvain.planning.service.EditionContext;
import dev.sylvain.planning.service.edition.EditionActivationService;
import io.quarkus.arc.Arc;

/**
 * Runs a piece of a test with another edition active (ADR 0072), then gives
 * the activation back to the edition that held it — the test database's own,
 * which every other test expects to find active.
 *
 * <p>Only the active edition publishes, mails and opens the espace, the ICS
 * feed and the wall display: a test that does any of that in an edition of
 * its own has to activate it first, and must not leave it active.</p>
 */
public final class ActiveEdition {

    private ActiveEdition() {}

    public static void during(String editionId, Runnable work) {
        EditionContext context = Arc.container().instance(EditionContext.class).get();
        EditionActivationService activation =
                Arc.container().instance(EditionActivationService.class).get();
        String previous = context.activeEditionId().orElse(null);
        activation.activate(editionId);
        try {
            work.run();
        } finally {
            if (previous != null) {
                activation.activate(previous);
            } else {
                activation.deactivate(editionId);
            }
        }
    }
}
