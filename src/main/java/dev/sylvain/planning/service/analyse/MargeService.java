package dev.sylvain.planning.service.analyse;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.ParametresLegaux;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.service.analyse.MargeAnalyzer.Mode;
import dev.sylvain.planning.service.analyse.MargeAnalyzer.RapportMarge;
import dev.sylvain.planning.service.analyse.StaffingAnalyzer.ReferentielManquant;
import dev.sylvain.planning.service.analyse.TensionAnalyzer.RapportTension;
import dev.sylvain.planning.service.referentiel.ReferenceDataService;
import dev.sylvain.planning.service.solve.PlanningPersistenceService;
import dev.sylvain.planning.service.solve.PlanningService;
import dev.sylvain.planning.service.solve.ProblemBuilder.Seats;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Objects;

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

    private final FragiliteAnalyzer fragiliteAnalyzer;

    @Inject
    public MargeService(
            PlanningService planningService,
            PlanningPersistenceService persistenceService,
            ReferenceDataService referenceDataService,
            MargeAnalyzer margeAnalyzer,
            FragiliteAnalyzer fragiliteAnalyzer) {
        this.planningService = planningService;
        this.persistenceService = persistenceService;
        this.referenceDataService = referenceDataService;
        this.margeAnalyzer = margeAnalyzer;
        this.fragiliteAnalyzer = fragiliteAnalyzer;
    }

    /**
     * The « Tension » reading: the « après » margin and the fragility of the
     * same persisted plan, read <b>once</b> and crossed by
     * {@link TensionAnalyzer}. The fragility is taken untruncated — the map
     * counts every irreplaceable seat of a timeslot, never the twenty the
     * fragility screen details per person.
     */
    public RapportTension tension() {
        ParametresLegaux parametres = referenceDataService.getParametresLegaux();
        PlanningEvenement persiste = persistenceService.loadPersistedPlanning();
        List<Animateur> animateurs = persiste.getAnimateurs() == null ? List.of() : persiste.getAnimateurs();
        List<PosteAffectation> postes = persiste.getPostes() == null ? List.of() : persiste.getPostes();
        RapportMarge apres = margeAnalyzer.analyze(
                Mode.APRES,
                postes,
                animateurs,
                parametres,
                animateurs.isEmpty() ? List.of(ReferentielManquant.ANIMATEURS) : List.of());
        List<Creneau> creneaux = postes.stream()
                .map(PosteAffectation::getCreneau)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        return TensionAnalyzer.compute(
                apres, fragiliteAnalyzer.findings(persiste), creneaux, planningService.pastHorizon());
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
