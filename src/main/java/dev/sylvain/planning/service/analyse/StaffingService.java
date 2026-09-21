package dev.sylvain.planning.service.analyse;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.FenetreRepas;
import dev.sylvain.planning.domain.ParametresLegaux;
import dev.sylvain.planning.service.analyse.StaffingAnalyzer.StaffingSummary;
import dev.sylvain.planning.service.referentiel.ReferenceDataService;
import dev.sylvain.planning.service.solve.PlanningService;
import dev.sylvain.planning.service.solve.ProblemBuilder.Seats;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;

/**
 * The staffing need of the current edition — what {@code GET /api/staffing}
 * and the MCP tool {@code analyser_effectifs} both return, computed here once
 * so the two callers cannot drift (issue #416).
 *
 * <p>The seats come from the seat-only build, never from the problem a solve
 * would run on: that one refuses an edition without animateur, and the two
 * callers used to swallow the refusal and analyse an empty seat list, which
 * showed zero everywhere on a screen meant to be read before any animateur is
 * entered. Seats depend on the stands and the timeslots only; the animateurs
 * are what the bounds are then compared to, and their absence is reported by
 * name in {@link StaffingSummary#referentielsManquants()} rather than as a
 * zero.</p>
 */
@ApplicationScoped
public class StaffingService {

    @Inject
    PlanningService planningService;

    @Inject
    ReferenceDataService referenceDataService;

    @Inject
    StaffingAnalyzer staffingAnalyzer;

    /** Never fails on an empty edition: this feeds read-only views a new user opens before entering anything. */
    public StaffingSummary analyzeEdition() {
        ParametresLegaux parametres = referenceDataService.getParametresLegaux();
        Seats seats = planningService.buildSeatsFromReferenceData();
        List<Animateur> animateurs = referenceDataService.listAnimateurs();
        return staffingAnalyzer.analyze(
                seats.postes(),
                animateurs,
                referenceDataService.listTypologies(),
                parametres.getDureeHebdomadaireMaxMinutes(),
                parametres.dureePauseMinutes(false),
                StaffingAnalyzer.referentielsManquants(seats.stands(), seats.creneaux(), animateurs),
                fenetresRepas());
    }

    /**
     * The meal windows the floor must account for — none when
     * {@code coupureRepasObligatoire} is switched off for this edition. A rule
     * the solver is not asked to honour must not raise the number this screen
     * tells the organiser to recruit.
     */
    private List<FenetreRepas> fenetresRepas() {
        if (referenceDataService.getContraintesDesactivees().contains("coupureRepasObligatoire")) {
            return List.of();
        }
        return referenceDataService.fenetresRepas();
    }
}
