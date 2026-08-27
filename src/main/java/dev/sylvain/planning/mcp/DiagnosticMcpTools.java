package dev.sylvain.planning.mcp;

import java.util.List;

import dev.sylvain.planning.domain.ParametresLegaux;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.service.ImportImpact;
import dev.sylvain.planning.service.KpiHistoriqueService;
import dev.sylvain.planning.service.KpiHistoriqueService.KpiHistoriqueEntry;
import dev.sylvain.planning.service.OuvertureStandsAnalyzer;
import dev.sylvain.planning.service.OuvertureStandsAnalyzer.LigneStand;
import dev.sylvain.planning.service.OuvertureStandsAnalyzer.RapportOuvertures;
import dev.sylvain.planning.service.PlanningService;
import dev.sylvain.planning.service.ReferenceDataService;
import dev.sylvain.planning.service.StaffingAnalyzer;
import dev.sylvain.planning.service.StaffingAnalyzer.StaffingSummary;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * The read-only diagnostics the screens have and MCP did not: how many
 * animateurs the event needs at all ({@code StaffingResource}), when each
 * stand is actually open ({@code OuvertureStandsResource}), how the editions
 * compare over the years ({@code KpiResource}), and what an import would
 * overwrite ({@code ReferenceDataResource}).
 *
 * <p>None of them carries a personal field: they count seats, hours and
 * stands. The animateurs appear only as totals — "il en faut au moins 148",
 * never who.</p>
 */
@EditionCiblee
@ApplicationScoped
public class DiagnosticMcpTools {

    @Inject
    ReferenceDataService referenceDataService;

    @Inject
    PlanningService planningService;

    @Inject
    StaffingAnalyzer staffingAnalyzer;

    @Inject
    KpiHistoriqueService kpiHistoriqueService;

    /**
     * Built exactly like {@code StaffingResource}: on the seats a real solve
     * would have to fill, not on stands × créneaux. Counting them in the
     * caller drifted from the real problem, badly — recurring horaires,
     * familles de relais and the reduced effectif during meal windows all
     * change the count.
     */
    @Tool(description = "Combien d'animateurs il faut au minimum pour couvrir l'événement, et pourquoi : pic "
            + "simultané, pic avec pause, charge horaire totale, jour critique, et le détail par jour. Calcul en "
            + "Java pur, aucune résolution lancée. Le résultat est un plancher optimiste — il ignore compétences, "
            + "disponibilités individuelles et repos quotidien.",
            annotations = @Tool.Annotations(readOnlyHint = true, destructiveHint = false,
                    idempotentHint = true, openWorldHint = false))
    StaffingSummary analyser_effectifs(
            @ToolArg(description = EditionArg.DESCRIPTION, required = false) @EditionArg String edition) {
        ParametresLegaux parametres = referenceDataService.getParametresLegaux();
        List<PosteAffectation> postes;
        try {
            PlanningEvenement evenement = planningService.buildFromReferenceData();
            postes = evenement.getPostes();
        } catch (IllegalStateException e) {
            postes = List.of();
        }
        return staffingAnalyzer.analyze(postes, parametres.getDureeHebdomadaireMaxMinutes(),
                parametres.getPauseMinimaleEntreVacationsMinutes());
    }

    @Tool(description = "Quand chaque stand est réellement ouvert, jour par jour, après application de ses "
            + "horaires récurrents et de ses plages datées : amplitude couverte, postes générés, et les anomalies "
            + "(stand jamais ouvert, fenêtre sans effet, segment trop court). C'est ici qu'on voit pourquoi un "
            + "stand ne génère aucun poste. Filtrable sur un stand ; les totaux restent ceux de l'édition.",
            annotations = @Tool.Annotations(readOnlyHint = true, destructiveHint = false,
                    idempotentHint = true, openWorldHint = false))
    RapportOuvertures analyser_ouvertures_stands(
            @ToolArg(description = "Id de stand pour ne détailler que celui-là", required = false) String standId,
            @ToolArg(description = EditionArg.DESCRIPTION, required = false) @EditionArg String edition) {
        RapportOuvertures rapport = OuvertureStandsAnalyzer.analyze(
                referenceDataService.listSolvedStands(),
                referenceDataService.listCreneaux());
        if (standId == null) {
            return rapport;
        }
        List<LigneStand> retenus = rapport.stands().stream()
                .filter(ligne -> standId.equals(ligne.standId()))
                .toList();
        return new RapportOuvertures(rapport.jours(), retenus, rapport.standsJamaisOuverts(),
                rapport.postesTotal(), rapport.anomalies());
    }

    /**
     * Deliberately not edition-scoped, like the endpoint: the history outlives
     * the edition it describes — its rows carry no foreign key and keep the
     * edition's name — precisely so two years can be compared side by side.
     * An {@code edition} argument would suggest a filter that does not exist.
     */
    @Tool(description = "Historique des KPI, une ligne par résolution terminée, toutes éditions confondues et de "
            + "la plus récente à la plus ancienne : score, couverture des postes, heures et violations par "
            + "contrainte. Sert à comparer une édition à la précédente.",
            annotations = @Tool.Annotations(readOnlyHint = true, destructiveHint = false,
                    idempotentHint = true, openWorldHint = false))
    List<KpiHistoriqueEntry> lister_kpi_historique() {
        return kpiHistoriqueService.list();
    }

    @Tool(description = "Ce qu'un import de scénario écraserait dans l'édition : nombre d'animateurs, de stands, "
            + "de postes déjà planifiés, de demandes d'échange et de verrouillages. À appeler avant "
            + "importer_scenario, qui remplace tout sans prévenir.",
            annotations = @Tool.Annotations(readOnlyHint = true, destructiveHint = false,
                    idempotentHint = true, openWorldHint = false))
    ImportImpact previsualiser_import(
            @ToolArg(description = EditionArg.DESCRIPTION, required = false) @EditionArg String edition) {
        return referenceDataService.countImportImpact();
    }
}
