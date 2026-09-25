package dev.sylvain.planning.service.analyse;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.ParametresLegaux;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.service.analyse.MargeAnalyzer.Mode;
import dev.sylvain.planning.service.analyse.MargeAnalyzer.RapportMarge;
import dev.sylvain.planning.service.analyse.StaffingAnalyzer.ReferentielManquant;
import dev.sylvain.planning.service.referentiel.ReferenceDataService;
import dev.sylvain.planning.service.solve.PlanningPersistenceService;
import dev.sylvain.planning.service.solve.PlanningService;
import dev.sylvain.planning.service.solve.ProblemBuilder.Seats;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;

/**
 * The margin of the current edition — what {@code GET /api/marge} and the MCP
 * tool {@code analyser_marge} both return, computed here once so the two
 * callers cannot drift, exactly as {@link StaffingService} does for the
 * staffing need.
 *
 * <p>The two modes read two different things, and that is the only difference
 * between them here: the « avant » one reads the seats a solve would have to
 * fill — the seat-only build, which answers before a single animateur is
 * entered — and the « après » one reads the plan already persisted. Both are
 * read-only and neither ever starts a solve.</p>
 */
@ApplicationScoped
public class MargeService {

    private final PlanningService planningService;

    private final PlanningPersistenceService persistenceService;

    private final ReferenceDataService referenceDataService;

    private final MargeAnalyzer margeAnalyzer;

    @Inject
    public MargeService(
            PlanningService planningService,
            PlanningPersistenceService persistenceService,
            ReferenceDataService referenceDataService,
            MargeAnalyzer margeAnalyzer) {
        this.planningService = planningService;
        this.persistenceService = persistenceService;
        this.referenceDataService = referenceDataService;
        this.margeAnalyzer = margeAnalyzer;
    }

    /** Never fails on an empty edition: this feeds a read-only screen a new user opens before entering anything. */
    public RapportMarge analyzeEdition(Mode mode) {
        ParametresLegaux parametres = referenceDataService.getParametresLegaux();
        if (mode == Mode.APRES) {
            PlanningEvenement persiste = persistenceService.loadPersistedPlanning();
            List<Animateur> animateurs = persiste.getAnimateurs() == null ? List.of() : persiste.getAnimateurs();
            return margeAnalyzer.analyze(
                    Mode.APRES,
                    persiste.getPostes(),
                    animateurs,
                    parametres,
                    // Seats alone cannot tell a missing stand from a missing
                    // timeslot, so only the roster is reported by name here —
                    // the empty grid is worded by the analyzer's message.
                    animateurs.isEmpty() ? List.of(ReferentielManquant.ANIMATEURS) : List.of());
        }
        Seats seats = planningService.buildSeatsFromReferenceData();
        List<Animateur> animateurs = referenceDataService.listAnimateurs();
        return margeAnalyzer.analyze(
                Mode.AVANT,
                seats.postes(),
                animateurs,
                parametres,
                StaffingAnalyzer.referentielsManquants(seats.stands(), seats.creneaux(), animateurs));
    }
}
