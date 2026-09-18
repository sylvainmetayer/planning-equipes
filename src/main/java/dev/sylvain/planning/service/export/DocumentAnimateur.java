package dev.sylvain.planning.service.export;

import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.service.analyse.PauseAnalyzer;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * One layout of one animateur's planning: the A4 booklet
 * ({@link AnimateurPlanningPdf}) or the folded landscape sheet
 * ({@link AnimateurFeuillePdf}).
 *
 * <p>An interface so {@link PlanningExportService} picks the document by
 * {@link FormatPlanning} and hands it what it reads once, rather than writing
 * the same nine arguments twice. Both implementations build the same
 * {@link AnimateurPlanningView} from them — which is what keeps the two
 * layouts from saying different things.</p>
 */
interface DocumentAnimateur {

    /**
     * @param teammatesByPoste  who else holds a seat on the same stand, window by window
     * @param journeesModifiees what a consigne says of a date (issue #4), empty on an ordinary édition
     */
    byte[] render(
            String animateurName,
            List<PosteAffectation> postes,
            Map<String, List<String>> teammatesByPoste,
            List<PlanningExportService.JourRepos> joursRepos,
            List<PauseAnalyzer.PauseAnimateurView> pauses,
            List<PauseAnalyzer.CoupureAnimateurView> coupures,
            String lienEspaceAnimateur,
            ExportProvenance.Provenance provenance,
            Map<LocalDate, String> journeesModifiees);
}
