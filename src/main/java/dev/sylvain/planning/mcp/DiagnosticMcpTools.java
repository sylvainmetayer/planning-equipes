package dev.sylvain.planning.mcp;


import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

import dev.sylvain.planning.domain.ParametresLegaux;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.service.BusinessError;
import dev.sylvain.planning.service.referentiel.ImportImpact;
import dev.sylvain.planning.service.analyse.KpiHistoriqueService;
import dev.sylvain.planning.service.analyse.KpiHistoriqueService.KpiHistoriqueEntry;
import dev.sylvain.planning.service.analyse.OuvertureStandsAnalyzer;
import dev.sylvain.planning.service.analyse.OuvertureStandsAnalyzer.LigneStand;
import dev.sylvain.planning.service.analyse.OuvertureStandsAnalyzer.RapportOuvertures;
import dev.sylvain.planning.service.analyse.PauseAnalyzer;
import dev.sylvain.planning.service.solve.PlanningPersistenceService;
import dev.sylvain.planning.service.solve.PlanningService;
import dev.sylvain.planning.service.referentiel.ReferenceDataService;
import dev.sylvain.planning.service.referentiel.ReferenceUsage;
import dev.sylvain.planning.service.analyse.StaffingAnalyzer;
import dev.sylvain.planning.service.analyse.StaffingAnalyzer.StaffingSummary;
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
@Journalise
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

    @Inject
    PauseAnalyzer pauseAnalyzer;

    @Inject
    PlanningPersistenceService persistenceService;

    /**
     * Built exactly like {@code StaffingResource}: on the seats a real solve
     * would have to fill, not on stands × créneaux. Counting them in the
     * caller drifted from the real problem, badly — recurring horaires,
     * familles de relais and the reduced effectif during meal windows all
     * change the count.
     */
    @Tool(description = "Combien d'animateurs il faut au minimum pour couvrir l'événement, et pourquoi : pic "
            + "simultané, pic avec pause, charge horaire de la semaine la plus chargée, rotation sur les jours "
            + "(nul ne travaille plus de six jours par semaine ISO), jour et semaine critiques, le détail par jour "
            + "et par semaine, plus le goulot par typologie : les mêmes bornes sur les sièges d'une seule typologie, "
            + "face aux animateurs qui la déclarent. Calcul en Java pur, aucune résolution lancée. Le résultat est "
            + "un plancher optimiste — il ignore compétences et repos quotidien ; minimumAvecIndisponibilites y "
            + "ajoute, à part, une projection sur les indisponibilités déjà déclarées.",
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
        return staffingAnalyzer.analyze(postes, referenceDataService.listAnimateurs(),
                referenceDataService.listTypologies(), parametres.getDureeHebdomadaireMaxMinutes(),
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

    /**
     * Deliberately not edition-scoped either, for the reason
     * {@code lister_kpi_historique} is not: a row survives the edition it
     * describes, and the rows of a deleted edition are exactly the ones
     * nothing else could ever clean up.
     */
    @Tool(description = "Supprime une ligne de l'historique des KPI, désignée par l'id que renvoie "
            + "lister_kpi_historique. Sert à retirer une résolution ratée qui fausse la comparaison entre "
            + "éditions ; l'historique est le seul endroit où elle est stockée, la ligne est perdue.",
            annotations = @Tool.Annotations(readOnlyHint = false, destructiveHint = true,
                    idempotentHint = true, openWorldHint = false))
    SuppressionResult supprimer_kpi_historique(
            @ToolArg(description = "Id de la ligne d'historique") long id) {
        if (!kpiHistoriqueService.delete(id)) {
            throw new BusinessError.NotFound("Ligne d'historique KPI inconnue : " + id);
        }
        return new SuppressionResult(String.valueOf(id), true);
    }

    @Tool(description = "Ce qu'une suppression emporterait avec elle : pour les ids donnés, le nombre "
            + "d'affectations du planning enregistré, de contraintes ad hoc et de verrouillages qui les citent. "
            + "Les compteurs sont agrégés sur toute la sélection, comme la question posée avant une suppression "
            + "en lot. Ne supprime rien.",
            annotations = @Tool.Annotations(readOnlyHint = true, destructiveHint = false,
                    idempotentHint = true, openWorldHint = false))
    UsagesView analyser_usages_suppression(
            @ToolArg(description = "Ids d'animateurs", required = false) List<String> animateurIds,
            @ToolArg(description = "Ids de stands", required = false) List<String> standIds,
            @ToolArg(description = "Ids de créneaux", required = false) List<String> creneauIds,
            @ToolArg(description = EditionArg.DESCRIPTION, required = false) @EditionArg String edition) {
        return new UsagesView(
                referenceDataService.countAnimateurUsages(animateurIds == null ? List.of() : animateurIds),
                referenceDataService.countStandUsages(standIds == null ? List.of() : standIds),
                referenceDataService.countCreneauUsages(creneauIds == null ? List.of() : creneauIds));
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

    /** One counter set per family asked about, each aggregated over its ids. */
    public record UsagesView(ReferenceUsage animateurs, ReferenceUsage stands, ReferenceUsage creneaux) {
    }

    /**
     * Built exactly like {@code PauseResource}: the persisted plan, read under
     * the organiser's <em>current</em> legal parameters. Mapped to records of
     * its own so that no name crosses MCP — the analyzer's own views carry the
     * animateur's display name for the screens.
     */
    @Tool(description = "La rotation des pauses légales du planning persisté : pour chaque animateur et chaque "
            + "jour, les séquences de travail ininterrompu, la pause due (20 min à la sixième heure, 30 min à "
            + "4 h 30 pour un mineur) posée de telle heure à telle heure — une personne à la fois par stand, au "
            + "plus tard possible —, le stand tenu et les collègues présents pendant la pause, plus les trous déjà "
            + "planifiés par la grille. Lu sous les paramètres légaux courants — "
            + "pauseSurPoste déclaré ou non — sans lancer de résolution. Filtrable par date, par stand, ou aux "
            + "seules pauses sans relais.",
            annotations = @Tool.Annotations(readOnlyHint = true, destructiveHint = false,
                    idempotentHint = true, openWorldHint = false))
    PausesView analyser_pauses(
            @ToolArg(description = "Date (AAAA-MM-JJ) : ne garder que ce jour", required = false) String date,
            @ToolArg(description = "Id de stand : ne garder que les pauses tenues sur ce stand", required = false) String standId,
            @ToolArg(description = "true : ne garder que les pauses sans relais possible sur le stand", required = false) Boolean sansRelaisSeulement,
            @ToolArg(description = EditionArg.DESCRIPTION, required = false) @EditionArg String edition) {
        PauseAnalyzer.RapportPauses rapport = pauseAnalyzer.analyze(persistenceService.loadPersistedPlanning(),
                referenceDataService.getParametresLegaux());
        LocalDate jour = McpArgs.date(date, "date");
        boolean sansRelais = Boolean.TRUE.equals(sansRelaisSeulement);
        boolean planifieesVisibles = standId == null && !sansRelais;
        List<JourneePausesView> journees = new ArrayList<>();
        for (PauseAnalyzer.JourneeAnimateurView journee : rapport.journees()) {
            if (jour != null && !jour.equals(journee.date())) {
                continue;
            }
            List<SequencePausesView> sequences = new ArrayList<>();
            for (PauseAnalyzer.SequenceView sequence : journee.sequences()) {
                List<PauseDueMcpView> dues = sequence.pausesDues().stream()
                        .filter(pause -> standId == null || standId.equals(pause.standId()))
                        .filter(pause -> !sansRelais || !pause.relaisDisponible())
                        .map(pause -> new PauseDueMcpView(pause.debut(), pause.fin(), pause.heureLimite(),
                                pause.dureeMinutes(), pause.standId(),
                                pause.relais().stream().map(PauseAnalyzer.RelaisView::animateurId).toList(),
                                pause.relaisDisponible(), pause.simultanee()))
                        .toList();
                if (!dues.isEmpty() || planifieesVisibles) {
                    sequences.add(new SequencePausesView(sequence.debut(), sequence.fin(), sequence.minutes(), dues));
                }
            }
            boolean planifiees = planifieesVisibles && !journee.pausesPlanifiees().isEmpty();
            if (sequences.isEmpty() && !planifiees) {
                continue;
            }
            journees.add(new JourneePausesView(journee.animateurId(), journee.mineur(), journee.date(),
                    journee.jour(), sequences, planifieesVisibles ? journee.pausesPlanifiees() : List.of()));
        }
        int pausesDues = 0;
        int relaisManquants = 0;
        for (JourneePausesView journee : journees) {
            for (SequencePausesView sequence : journee.sequences()) {
                pausesDues += sequence.pausesDues().size();
                relaisManquants += (int) sequence.pausesDues().stream()
                        .filter(pause -> !pause.relaisDisponible()).count();
            }
        }
        return new PausesView(rapport.pauseSurPoste(), rapport.journeesAnalysees(), pausesDues, relaisManquants,
                journees, rapport.message());
    }

    /**
     * One break, placed in the stand's rotation ({@code debut}–{@code fin},
     * never starting after {@code heureLimite}); the relays are animateur ids
     * only, and {@code simultanee} says the windows left no room to keep it
     * apart from another break on the stand.
     */
    public record PauseDueMcpView(LocalTime debut, LocalTime fin, LocalTime heureLimite, int dureeMinutes,
            String standId, List<String> relaisAnimateurIds, boolean relaisDisponible, boolean simultanee) {
    }

    public record SequencePausesView(LocalTime debut, LocalTime fin, int minutes, List<PauseDueMcpView> pausesDues) {
    }

    /**
     * One animateur on one day. {@code pausesPlanifiees} — the gaps the grid
     * already schedules — are given only when no stand or relay filter
     * narrows the answer: they belong to the day, not to a stand.
     */
    public record JourneePausesView(String animateurId, boolean mineur, LocalDate date, int jour,
            List<SequencePausesView> sequences, List<PauseAnalyzer.PausePlanifieeView> pausesPlanifiees) {
    }

    /**
     * @param journeesAnalysees animateur-days holding at least one seat, over the whole plan — not
     *                          reduced by the filters
     * @param pausesDues        breaks kept after the filters; {@code relaisManquants} those without relay
     * @param message           the analyzer's own sentence, over the whole plan
     */
    public record PausesView(boolean pauseSurPoste, int journeesAnalysees, int pausesDues, int relaisManquants,
            List<JourneePausesView> journees, String message) {
    }

}
