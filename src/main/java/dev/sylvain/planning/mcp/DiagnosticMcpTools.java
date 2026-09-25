package dev.sylvain.planning.mcp;

import dev.sylvain.planning.service.BusinessError;
import dev.sylvain.planning.service.analyse.KpiHistoriqueService;
import dev.sylvain.planning.service.analyse.KpiHistoriqueService.KpiHistoriqueEntry;
import dev.sylvain.planning.service.analyse.MargeAnalyzer.Mode;
import dev.sylvain.planning.service.analyse.MargeAnalyzer.RapportMarge;
import dev.sylvain.planning.service.analyse.MargeService;
import dev.sylvain.planning.service.analyse.OuvertureStandsAnalyzer;
import dev.sylvain.planning.service.analyse.OuvertureStandsAnalyzer.LigneStand;
import dev.sylvain.planning.service.analyse.OuvertureStandsAnalyzer.RapportOuvertures;
import dev.sylvain.planning.service.analyse.PauseAnalyzer;
import dev.sylvain.planning.service.analyse.StaffingAnalyzer.StaffingSummary;
import dev.sylvain.planning.service.analyse.StaffingService;
import dev.sylvain.planning.service.referentiel.CoherenceReferentielService;
import dev.sylvain.planning.service.referentiel.CoherenceReferentielService.CoherenceFamily;
import dev.sylvain.planning.service.referentiel.CoherenceReferentielService.CoherenceReport;
import dev.sylvain.planning.service.referentiel.CoherenceReferentielService.CoherenceSeverity;
import dev.sylvain.planning.service.referentiel.CoherenceReferentielService.CoherenceSubject;
import dev.sylvain.planning.service.referentiel.CoherenceReferentielService.FamilyCount;
import dev.sylvain.planning.service.referentiel.ImportImpact;
import dev.sylvain.planning.service.referentiel.ReferenceDataService;
import dev.sylvain.planning.service.referentiel.ReferenceUsage;
import dev.sylvain.planning.service.solve.PlanningPersistenceService;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

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
@RefusMetier
@Journalise
@ApplicationScoped
public class DiagnosticMcpTools {

    private final ReferenceDataService referenceDataService;

    private final StaffingService staffingService;

    private final MargeService margeService;

    private final KpiHistoriqueService kpiHistoriqueService;

    private final PauseAnalyzer pauseAnalyzer;

    private final PlanningPersistenceService persistenceService;

    private final CoherenceReferentielService coherenceService;

    @Inject
    DiagnosticMcpTools(
            ReferenceDataService referenceDataService,
            StaffingService staffingService,
            MargeService margeService,
            KpiHistoriqueService kpiHistoriqueService,
            PauseAnalyzer pauseAnalyzer,
            PlanningPersistenceService persistenceService,
            CoherenceReferentielService coherenceService) {
        this.referenceDataService = referenceDataService;
        this.staffingService = staffingService;
        this.margeService = margeService;
        this.kpiHistoriqueService = kpiHistoriqueService;
        this.pauseAnalyzer = pauseAnalyzer;
        this.persistenceService = persistenceService;
        this.coherenceService = coherenceService;
    }

    /**
     * The same computation as {@code GET /api/staffing}, through the same
     * {@link StaffingService}: on the seats a real solve would have to fill,
     * not on stands × créneaux. Counting them in the
     * caller drifted from the real problem, badly — recurring horaires and the
     * reduced effectif during meal windows both change the count.
     */
    @Tool(
            name = "analyser_effectifs",
            description = "Combien d'animateurs il faut au minimum pour couvrir l'événement, et pourquoi : pic "
                    + "simultané, pic avec pause, charge horaire de la semaine la plus chargée, rotation sur les jours "
                    + "(nul ne travaille plus de six jours par semaine ISO), coupure repas (une grille qui ne s'arrête "
                    + "jamais entre le matin et l'après-midi ne se tient pas à son seul pic ; nulle quand la règle est "
                    + "désactivée), jour et semaine critiques, le détail par jour "
                    + "et par semaine, plus le goulot par typologie : les mêmes bornes sur les sièges d'une seule typologie, "
                    + "face aux animateurs qui la déclarent. Calcul en Java pur, aucune résolution lancée. Le résultat est "
                    + "un plancher optimiste — il ignore compétences et repos quotidien ; minimumAvecIndisponibilites y "
                    + "ajoute, à part, une projection sur les indisponibilités déjà déclarées. Répond avant la saisie "
                    + "d'aucun animateur : les sièges ne dépendent que des stands et des créneaux, et "
                    + "referentielsManquants nomme ce qui n'est pas encore saisi.",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = true,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    StaffingSummary analyzeEffectifs(
            @ToolArg(description = EditionArg.DESCRIPTION, required = false) @EditionArg String edition) {
        return staffingService.analyzeEdition();
    }

    /**
     * The same computation as {@code GET /api/marge}, through the same
     * {@link MargeService}. Where {@code analyser_effectifs} answers « combien
     * faut-il recruter » over the whole event, this one says <em>when</em> the
     * capacity is tight — which is the question a withdrawal, a recruitment or
     * one opening window fewer actually turns on.
     */
    @Tool(
            name = "analyser_marge",
            description = "La marge disponible, jour par jour et tranche horaire par tranche horaire : animateurs "
                    + "disponibles à ce moment-là moins sièges à pourvoir. Les tranches sont les créneaux de la grille, "
                    + "donc lisibles aussi bien en amplitudes qu'en vacations. Deux modes : « avant » (par défaut) "
                    + "compare la capacité brute — qui n'a pas déclaré cette date indisponible — aux sièges qu'une "
                    + "résolution devrait pourvoir, et répond avant toute résolution ; « apres » lit le planning "
                    + "persisté et ne compte libre que celui qui n'est pas déjà sur un siège qui chevauche, qui "
                    + "respecte la pause légale entre vacations de part et d'autre, et que les règles dures laissent "
                    + "prendre un siège de la tranche — face aux seuls sièges restés vides. Chaque journée porte sa "
                    + "pire tranche, et le rapport la pire de l'événement. Calcul en Java pur, aucune résolution "
                    + "lancée, compétences hors périmètre (voir analyser_effectifs pour le goulot par typologie). "
                    + "Lecture optimiste : une tranche annoncée négative l'est, une tranche confortable ne le "
                    + "garantit pas.",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = true,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    RapportMarge analyzeMargin(
            @ToolArg(
                            description = "« avant » (défaut) : capacité brute contre besoin ; « apres » : les "
                                    + "personnes réellement libres sur le planning persisté",
                            required = false)
                    String mode,
            @ToolArg(description = EditionArg.DESCRIPTION, required = false) @EditionArg String edition) {
        return margeService.analyzeEdition("apres".equalsIgnoreCase(mode) ? Mode.APRES : Mode.AVANT);
    }

    @Tool(
            name = "analyser_ouvertures_stands",
            description = "Quand chaque stand est réellement ouvert, jour par jour, après application de ses "
                    + "horaires récurrents et de ses plages datées : amplitude couverte, postes générés, et les anomalies "
                    + "(STAND_JAMAIS_OUVERT, FENETRE_SANS_EFFET, SEGMENT_TROP_COURT ; et, pour information seulement, "
                    + "REGLES_CHEVAUCHANTES : deux règles de même portée et de même mode qui se recouvrent, l'effectif le "
                    + "plus haut l'emporte ; REGLE_MASQUEE : une règle qu'aucun jour n'applique ; FENETRES_CHEVAUCHANTES : "
                    + "deux fenêtres d'une même règle qui se recouvrent à des effectifs différents). C'est ici qu'on voit "
                    + "pourquoi un stand ne génère aucun poste. Filtrable sur un stand ; les totaux restent ceux de "
                    + "l'édition.",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = true,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    RapportOuvertures analyzeStandOpenings(
            @ToolArg(description = "Id de stand pour ne détailler que celui-là", required = false) String standId,
            @ToolArg(description = EditionArg.DESCRIPTION, required = false) @EditionArg String edition) {
        RapportOuvertures rapport = OuvertureStandsAnalyzer.analyze(
                referenceDataService.listSolvedStands(), referenceDataService.listCreneaux());
        if (standId == null) {
            return rapport;
        }
        List<LigneStand> retenus = rapport.stands().stream()
                .filter(ligne -> standId.equals(ligne.standId()))
                .toList();
        return new RapportOuvertures(
                rapport.jours(), retenus, rapport.standsJamaisOuverts(), rapport.postesTotal(), rapport.anomalies());
    }

    /**
     * One line of the coherence checklist as an assistant reads it: what, how
     * serious, about which fiche — never the sentence nor the date. A warning
     * on an animateur dates their majority, their birth date shifted by
     * eighteen years (the rule of {@code WarningCodes}); the code says what the
     * line means, and {@code consulter_*} gives what may be read about the
     * fiche it names.
     */
    public record CoherenceIssueView(
            CoherenceFamily famille, CoherenceSeverity gravite, String code, CoherenceSubject objet, String objetId) {}

    /** The checklist without its sentences: the counts, then one line per anomaly. */
    public record CoherenceListView(
            int bloquants,
            int aVerifier,
            int informations,
            List<FamilyCount> familles,
            List<CoherenceIssueView> anomalies) {}

    @Tool(
            name = "lister_anomalies_referentiel",
            description = "La checklist de cohérence du référentiel : toutes les anomalies déjà détectées ailleurs, "
                    + "recalculées sur toute l'édition — avertissements de saisie rejoués sur chaque animateur, "
                    + "créneau, stand et verrouillage (codes TypeAvertissement), anomalies d'ouverture des stands, "
                    + "contrôle de la grille de créneaux, ajustements manuels contradictoires ou intenables, besoin "
                    + "en animateurs non couvert (BESOIN_NON_COUVERT, TYPOLOGIE_EN_MANQUE). Chaque ligne porte sa "
                    + "famille (STANDS, CRENEAUX, ANIMATEURS, AJUSTEMENTS, CAPACITE), sa gravité (BLOQUANT, "
                    + "A_VERIFIER, INFORMATION), son code et la fiche concernée (objet + objetId) — jamais de phrase "
                    + "ni de date : les détails se lisent avec les outils consulter_* et analyser_*. Aucune règle "
                    + "nouvelle, aucune résolution lancée.",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = true,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    CoherenceListView listReferenceDataAnomalies(
            @ToolArg(description = EditionArg.DESCRIPTION, required = false) @EditionArg String edition) {
        CoherenceReport rapport = coherenceService.report();
        return new CoherenceListView(
                rapport.bloquants(),
                rapport.aVerifier(),
                rapport.informations(),
                rapport.familles(),
                rapport.anomalies().stream()
                        .map(ligne -> new CoherenceIssueView(
                                ligne.famille(), ligne.gravite(), ligne.code(), ligne.objet(), ligne.objetId()))
                        .toList());
    }

    /**
     * Deliberately not edition-scoped, like the endpoint: the history outlives
     * the edition it describes — its rows carry no foreign key and keep the
     * edition's name — precisely so two years can be compared side by side.
     * An {@code edition} argument would suggest a filter that does not exist.
     */
    @Tool(
            name = "lister_kpi_historique",
            description = "Historique des KPI, une ligne par résolution terminée, toutes éditions confondues et de "
                    + "la plus récente à la plus ancienne : score, couverture des postes, heures et violations par "
                    + "contrainte. Sert à comparer une édition à la précédente.",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = true,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    List<KpiHistoriqueEntry> listKpiHistory() {
        return kpiHistoriqueService.list();
    }

    /**
     * Deliberately not edition-scoped either, for the reason
     * {@code lister_kpi_historique} is not: a row survives the edition it
     * describes, and the rows of a deleted edition are exactly the ones
     * nothing else could ever clean up.
     */
    @Tool(
            name = "supprimer_kpi_historique",
            description = "Supprime une ligne de l'historique des KPI, désignée par l'id que renvoie "
                    + "lister_kpi_historique. Sert à retirer une résolution ratée qui fausse la comparaison entre "
                    + "éditions ; l'historique est le seul endroit où elle est stockée, la ligne est perdue.",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = false,
                            destructiveHint = true,
                            idempotentHint = true,
                            openWorldHint = false))
    SuppressionResult deleteKpiHistory(@ToolArg(description = "Id de la ligne d'historique") long id) {
        if (!kpiHistoriqueService.delete(id)) {
            throw new BusinessError.NotFound("Ligne d'historique KPI inconnue : " + id);
        }
        return new SuppressionResult(String.valueOf(id), true);
    }

    @Tool(
            name = "analyser_usages_suppression",
            description = "Ce qu'une suppression emporterait avec elle : pour les ids donnés, le nombre "
                    + "d'affectations du planning enregistré, de contraintes ad hoc et de verrouillages qui les citent. "
                    + "Les compteurs sont agrégés sur toute la sélection, comme la question posée avant une suppression "
                    + "en lot. Ne supprime rien.",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = true,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    UsagesView analyzeDeletionUsages(
            @ToolArg(description = "Ids d'animateurs", required = false) List<String> animateurIds,
            @ToolArg(description = "Ids de stands", required = false) List<String> standIds,
            @ToolArg(description = "Ids de créneaux", required = false) List<String> creneauIds,
            @ToolArg(description = EditionArg.DESCRIPTION, required = false) @EditionArg String edition) {
        return new UsagesView(
                referenceDataService.countAnimateurUsages(animateurIds == null ? List.of() : animateurIds),
                referenceDataService.countStandUsages(standIds == null ? List.of() : standIds),
                referenceDataService.countCreneauUsages(creneauIds == null ? List.of() : creneauIds));
    }

    @Tool(
            name = "previsualiser_import",
            description = "Ce qu'un import de scénario écraserait dans l'édition : nombre d'animateurs, de stands, "
                    + "de postes déjà planifiés, de demandes d'échange et de verrouillages. À appeler avant "
                    + "importer_scenario, qui remplace tout sans prévenir.",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = true,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    ImportImpact previewImport(
            @ToolArg(description = EditionArg.DESCRIPTION, required = false) @EditionArg String edition) {
        return referenceDataService.countImportImpact();
    }

    /** One counter set per family asked about, each aggregated over its ids. */
    public record UsagesView(ReferenceUsage animateurs, ReferenceUsage stands, ReferenceUsage creneaux) {}

    /**
     * Built exactly like {@code PauseResource}: the persisted plan, read under
     * the organiser's <em>current</em> legal parameters and meal windows. Mapped to records of
     * its own so that no name crosses MCP — the analyzer's own views carry the
     * animateur's display name for the screens.
     */
    @Tool(
            name = "analyser_pauses",
            description = "La rotation des pauses légales du planning persisté : pour chaque animateur et chaque "
                    + "jour, les séquences de travail ininterrompu, la pause due (20 min à la sixième heure, 30 min à "
                    + "4 h 30 pour un mineur) posée de telle heure à telle heure — une personne à la fois par stand, au "
                    + "plus tard possible —, le stand tenu et les collègues présents pendant la pause, plus les trous déjà "
                    + "planifiés par la grille, et la coupure repas due par chaque journée à cheval sur une fenêtre "
                    + "repas — avec le plus grand trou libre que la journée y laisse, donc ce qui manque quand la règle "
                    + "coupureRepasObligatoire mord. Lu sous les paramètres légaux et les fenêtres repas courants, "
                    + "sans lancer de résolution. Une pause due est soit un trou dans la grille, soit relayée : une "
                    + "pause sans relais est un écart dur. Filtrable par date, par stand, ou aux seules pauses sans "
                    + "relais.",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = true,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    PausesView analyzePauses(
            @ToolArg(description = "Date (AAAA-MM-JJ) : ne garder que ce jour", required = false) String date,
            @ToolArg(description = "Id de stand : ne garder que les pauses tenues sur ce stand", required = false)
                    String standId,
            @ToolArg(
                            description = "true : ne garder que les pauses sans relais possible sur le stand",
                            required = false)
                    Boolean sansRelaisSeulement,
            @ToolArg(description = EditionArg.DESCRIPTION, required = false) @EditionArg String edition) {
        PauseAnalyzer.RapportPauses rapport = pauseAnalyzer.analyze(
                persistenceService.loadPersistedPlanning(),
                referenceDataService.getParametresLegaux(),
                referenceDataService.fenetresRepas());
        LocalDate jour = McpArgs.date(date, "date");
        boolean sansRelais = Boolean.TRUE.equals(sansRelaisSeulement);
        boolean planifieesVisibles = standId == null && !sansRelais;
        List<JourneePausesView> journees = new ArrayList<>();
        for (PauseAnalyzer.JourneeAnimateurView journee : rapport.journees()) {
            if (jour != null && !jour.equals(journee.date())) {
                continue;
            }
            List<SequencePausesView> sequences = visibleSequences(journee, standId, sansRelais, planifieesVisibles);
            boolean planifiees = planifieesVisibles
                    && (!journee.pausesPlanifiees().isEmpty()
                            || !journee.coupuresRepas().isEmpty());
            if (!sequences.isEmpty() || planifiees) {
                journees.add(new JourneePausesView(
                        journee.animateurId(),
                        journee.mineur(),
                        journee.date(),
                        journee.jour(),
                        sequences,
                        planifieesVisibles ? journee.pausesPlanifiees() : List.of(),
                        planifieesVisibles ? journee.coupuresRepas() : List.of()));
            }
        }
        int pausesDues = 0;
        int relaisManquants = 0;
        for (JourneePausesView journee : journees) {
            for (SequencePausesView sequence : journee.sequences()) {
                pausesDues += sequence.pausesDues().size();
                relaisManquants += (int) sequence.pausesDues().stream()
                        .filter(pause -> !pause.relaisDisponible())
                        .count();
            }
        }
        return new PausesView(
                rapport.journeesAnalysees(),
                pausesDues,
                relaisManquants,
                rapport.coupuresRepasDues(),
                rapport.coupuresRepasManquantes(),
                journees,
                rapport.message());
    }

    /**
     * The sequences of one day the filters keep: a sequence stays when a due
     * pause survives them, or when the planned breaks are shown anyway.
     */
    private static List<SequencePausesView> visibleSequences(
            PauseAnalyzer.JourneeAnimateurView journee,
            String standId,
            boolean sansRelais,
            boolean planifieesVisibles) {
        List<SequencePausesView> sequences = new ArrayList<>();
        for (PauseAnalyzer.SequenceView sequence : journee.sequences()) {
            List<PauseDueMcpView> dues = sequence.pausesDues().stream()
                    .filter(pause -> standId == null || standId.equals(pause.standId()))
                    .filter(pause -> !sansRelais || !pause.relaisDisponible())
                    .map(pause -> new PauseDueMcpView(
                            pause.debut(),
                            pause.fin(),
                            pause.heureLimite(),
                            pause.dureeMinutes(),
                            pause.standId(),
                            pause.relais().stream()
                                    .map(PauseAnalyzer.RelaisView::animateurId)
                                    .toList(),
                            pause.relaisDisponible(),
                            pause.simultanee()))
                    .toList();
            if (!dues.isEmpty() || planifieesVisibles) {
                sequences.add(new SequencePausesView(sequence.debut(), sequence.fin(), sequence.minutes(), dues));
            }
        }
        return sequences;
    }

    /**
     * One break, placed in the stand's rotation ({@code debut}–{@code fin},
     * never starting after {@code heureLimite}); the relays are animateur ids
     * only, and {@code simultanee} says the windows left no room to keep it
     * apart from another break on the stand.
     */
    public record PauseDueMcpView(
            LocalTime debut,
            LocalTime fin,
            LocalTime heureLimite,
            int dureeMinutes,
            String standId,
            List<String> relaisAnimateurIds,
            boolean relaisDisponible,
            boolean simultanee) {}

    public record SequencePausesView(LocalTime debut, LocalTime fin, int minutes, List<PauseDueMcpView> pausesDues) {}

    /**
     * One animateur on one day. {@code pausesPlanifiees} — the gaps the grid
     * already schedules — are given only when no stand or relay filter
     * narrows the answer: they belong to the day, not to a stand.
     */
    public record JourneePausesView(
            String animateurId,
            boolean mineur,
            LocalDate date,
            int jour,
            List<SequencePausesView> sequences,
            List<PauseAnalyzer.PausePlanifieeView> pausesPlanifiees,
            List<PauseAnalyzer.CoupureRepasView> coupuresRepas) {}

    /**
     * @param journeesAnalysees animateur-days holding at least one seat, over the whole plan — not
     *                          reduced by the filters
     * @param pausesDues        breaks kept after the filters; {@code relaisManquants} those without relay
     * @param coupuresRepasDues meal breaks owed over the whole plan, and
     *                          {@code coupuresRepasManquantes} those the plan
     *                          leaves no room for — the days
     *                          {@code coupureRepasObligatoire} penalises. Not
     *                          reduced by the filters, which are about the
     *                          legal breaks and their relays.
     * @param message           the analyzer's own sentence, over the whole plan
     */
    public record PausesView(
            int journeesAnalysees,
            int pausesDues,
            int relaisManquants,
            int coupuresRepasDues,
            int coupuresRepasManquantes,
            List<JourneePausesView> journees,
            String message) {}
}
