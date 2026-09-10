package dev.sylvain.planning.api;

import dev.sylvain.planning.service.BusinessError;
import dev.sylvain.planning.service.publication.PlanPublicationService;

/**
 * Publishes the seeded plan, for the tests that read the espace animateur.
 *
 * <p>Since issue #245 the espace shows the <b>published</b> plan, so a fixture
 * that only persists one leaves the espace legitimately empty. Every suite
 * exercising the espace, its exports or the foire has to say out loud that the
 * plan was communicated — which is exactly the promise the feature makes.</p>
 */
final class PlansPublies {

    private PlansPublies() {}

    /**
     * Publishes what is persisted. A {@link BusinessError.Conflict} means there
     * was nothing new to announce, which is a perfectly good fixture state:
     * the plan on display is already the published one.
     */
    static void publier(PlanPublicationService publication) {
        try {
            publication.publier();
        } catch (BusinessError.Conflict rienAAnnoncer) {
            // See javadoc: nothing to publish is not a failed fixture.
        }
    }
}
