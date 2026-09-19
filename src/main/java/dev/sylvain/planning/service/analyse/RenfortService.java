package dev.sylvain.planning.service.analyse;

import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.service.analyse.RenfortAnalyzer.RapportRenforts;
import dev.sylvain.planning.service.solve.PlanningPersistenceService;
import dev.sylvain.planning.service.solve.PlanningService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;

/**
 * The bonus hours of the current edition — what {@code GET /api/renforts}
 * returns, assembled here rather than in the resource, exactly as
 * {@link MargeService} does for the margin.
 *
 * <p>It reads the two things {@link RenfortAnalyzer} puts side by side: the
 * seats a solve would build from the reference data as it stands, which is
 * where the declared capacity lives, and the persisted plan, which is where
 * the staffed renforts live. Neither ever starts a solve.</p>
 *
 * <p>Never fails on an empty edition: this feeds a read-only screen an
 * administrator may open before entering a single stand.</p>
 */
@ApplicationScoped
public class RenfortService {

    @Inject
    PlanningService planningService;

    @Inject
    PlanningPersistenceService persistenceService;

    @Inject
    RenfortAnalyzer renfortAnalyzer;

    public RapportRenforts analyzeEdition() {
        // The seat-only build, like the volumetry: it answers before a single
        // animateur is entered, and an edition with no stand or no timeslot
        // simply has no seat — it does not refuse.
        List<PosteAffectation> seats =
                planningService.buildSeatsFromReferenceData().postes();
        PlanningEvenement persiste = persistenceService.loadPersistedPlanning();
        List<PosteAffectation> postesPersistes = persiste.getPostes() == null ? List.of() : persiste.getPostes();
        return renfortAnalyzer.analyze(seats, postesPersistes);
    }
}
