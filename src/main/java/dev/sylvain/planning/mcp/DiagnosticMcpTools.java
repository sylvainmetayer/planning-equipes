package dev.sylvain.planning.mcp;

import com.fasterxml.jackson.annotation.JsonInclude;
import dev.sylvain.planning.domain.NiveauCompetence;
import dev.sylvain.planning.service.BusinessError;
import dev.sylvain.planning.service.analyse.FeasibilityAnalyzer;
import dev.sylvain.planning.service.analyse.FeasibilityAnalyzer.FeasibilityReport;
import dev.sylvain.planning.service.analyse.FormationAnalyzer.PlanFormation;
import dev.sylvain.planning.service.analyse.FormationAnalyzer.TypologieAFormer;
import dev.sylvain.planning.service.analyse.FormationService;
import dev.sylvain.planning.service.analyse.FragiliteAnalyzer;
import dev.sylvain.planning.service.analyse.FragiliteAnalyzer.AnimateurFragilite;
import dev.sylvain.planning.service.analyse.FragiliteAnalyzer.CompetenceRare;
import dev.sylvain.planning.service.analyse.FragiliteAnalyzer.PosteFragile;
import dev.sylvain.planning.service.analyse.FragiliteAnalyzer.RapportFragilite;
import dev.sylvain.planning.service.analyse.FragiliteAnalyzer.SeveriteFragilite;
import dev.sylvain.planning.service.analyse.GroupedArrivalAnalyzer;
import dev.sylvain.planning.service.analyse.KpiHistoriqueService;
import dev.sylvain.planning.service.analyse.KpiHistoriqueService.KpiHistoriqueEntry;
import dev.sylvain.planning.service.analyse.MargeAnalyzer.Mode;
import dev.sylvain.planning.service.analyse.MargeService;
import dev.sylvain.planning.service.analyse.MarginReading;
import dev.sylvain.planning.service.analyse.OuvertureStandsAnalyzer;
import dev.sylvain.planning.service.analyse.OuvertureStandsAnalyzer.LigneStand;
import dev.sylvain.planning.service.analyse.OuvertureStandsAnalyzer.RapportOuvertures;
import dev.sylvain.planning.service.analyse.PauseAnalyzer;
import dev.sylvain.planning.service.analyse.StaffingAnalyzer.StaffingSummary;
import dev.sylvain.planning.service.analyse.StaffingService;
import dev.sylvain.planning.service.analyse.TypologieAnalyzer;
import dev.sylvain.planning.service.analyse.TypologieAnalyzer.AnimateurTypologie;
import dev.sylvain.planning.service.analyse.TypologieAnalyzer.LigneTypologie;
import dev.sylvain.planning.service.analyse.TypologieAnalyzer.RapportTypologies;
import dev.sylvain.planning.service.analyse.WalkSequenceAnalyzer;
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
import dev.sylvain.planning.service.solve.PlanningService;
import dev.sylvain.planning.service.solve.ProblemScaleService;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The read-only diagnostics the screens have and MCP did not: how many
 * animateurs the event needs at all ({@code StaffingResource}), when each
 * stand is actually open ({@code OuvertureStandsResource}), how the editions
 * compare over the years ({@code MesuresEditionResource}), what an import would
 * overwrite ({@code ReferenceDataResource}), and the plan read by game
 * category ({@code TypologieAnalyseResource}). The Besoin and Risques tabs and
 * the Repas et pauses report are each one tool whose {@code section} is the
 * part read (ADR 0127).
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

    private final FormationService formationService;

    private final KpiHistoriqueService kpiHistoriqueService;

    private final PauseAnalyzer pauseAnalyzer;

    private final PlanningPersistenceService persistenceService;

    private final CoherenceReferentielService coherenceService;

    private final WalkSequenceAnalyzer walkSequenceAnalyzer;

    private final GroupedArrivalAnalyzer groupedArrivalAnalyzer;

    private final TypologieAnalyzer typologieAnalyzer;

    private final FragiliteAnalyzer fragiliteAnalyzer;

    private final FeasibilityAnalyzer feasibilityAnalyzer;

    private final ProblemScaleService problemScaleService;

    private final PlanningService planningService;

    @Inject
    DiagnosticMcpTools(
            ReferenceDataService referenceDataService,
            StaffingService staffingService,
            MargeService margeService,
            FormationService formationService,
            KpiHistoriqueService kpiHistoriqueService,
            PauseAnalyzer pauseAnalyzer,
            PlanningPersistenceService persistenceService,
            CoherenceReferentielService coherenceService,
            WalkSequenceAnalyzer walkSequenceAnalyzer,
            GroupedArrivalAnalyzer groupedArrivalAnalyzer,
            TypologieAnalyzer typologieAnalyzer,
            FragiliteAnalyzer fragiliteAnalyzer,
            FeasibilityAnalyzer feasibilityAnalyzer,
            ProblemScaleService problemScaleService,
            PlanningService planningService) {
        this.referenceDataService = referenceDataService;
        this.staffingService = staffingService;
        this.margeService = margeService;
        this.formationService = formationService;
        this.kpiHistoriqueService = kpiHistoriqueService;
        this.pauseAnalyzer = pauseAnalyzer;
        this.persistenceService = persistenceService;
        this.coherenceService = coherenceService;
        this.walkSequenceAnalyzer = walkSequenceAnalyzer;
        this.groupedArrivalAnalyzer = groupedArrivalAnalyzer;
        this.typologieAnalyzer = typologieAnalyzer;
        this.fragiliteAnalyzer = fragiliteAnalyzer;
        this.feasibilityAnalyzer = feasibilityAnalyzer;
        this.problemScaleService = problemScaleService;
        this.planningService = planningService;
    }

    private static final String MARGE = "marge";
    private static final String PAUSES = "pauses";

    /**
     * The Besoin tab of Diagnostic in one tool (ADR 0127): the section is the
     * part of the tab read, each answered by the tool it replaces.
     */
    @Tool(
            name = "consulter_besoin",
            description = "L'onglet Besoin du Diagnostic, sous la section demandée ; calcul en Java pur, aucune "
                    + "résolution lancée, aucun nom. borne (défaut) : combien d'animateurs il faut au minimum et "
                    + "pourquoi — la borne exacte (pic simultané, pic avec pause, charge de la semaine la plus "
                    + "chargée, rotation sur les jours, coupure repas), jour et semaine critiques, détail par jour et "
                    + "par semaine, goulot par typologie, projection sur les indisponibilités déclarées, et "
                    + "referentielsManquants ; elle répond avant la saisie d'aucun animateur. marge : animateurs "
                    + "disponibles moins sièges à pourvoir, jour par jour et créneau par créneau, mode « avant » "
                    + "(défaut, capacité brute contre besoin) ou « apres » (les seules personnes libres sur le planning "
                    + "persisté, face aux sièges restés vides) ; lecture optimiste. typologie : le planning persisté "
                    + "lu par typologie de jeu, heures et postes tenus, heures par jour, et les animateurs affectés, "
                    + "compétents, compétents jamais affectés et affectés sans la compétence, par id. volumetrie : la "
                    + "taille du problème que construirait la prochaine résolution (animateurs, postes, contraintes "
                    + "ad hoc, heures à pourvoir et offertes). faisabilite : les causes structurellement bloquantes "
                    + "avant résolution (créneau en sous-effectif, contraintes ad hoc contradictoires, affectation "
                    + "forcée intenable, plafond de jours d'affilée intenable ou tendu). mode ne vaut que pour la "
                    + "section marge.",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = true,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    BesoinView consultNeed(
            @ToolArg(description = "borne (défaut), marge, typologie, volumetrie ou faisabilite", required = false)
                    String section,
            @ToolArg(description = "Section marge : « avant » (défaut) ou « apres »", required = false) String mode,
            @ToolArg(description = EditionArg.DESCRIPTION) @EditionArg String edition) {
        String choisie =
                McpArgs.choix(section, "section", "borne", "borne", MARGE, "typologie", "volumetrie", "faisabilite");
        if (mode != null && !mode.isBlank() && !MARGE.equals(choisie)) {
            throw new BusinessError.Invalid("mode ne vaut que pour la section marge");
        }
        return switch (choisie) {
            case "borne" -> new BesoinView(choisie, analyzeEffectifs(edition), null, null, null, null);
            case MARGE -> {
                // Tension is a section of Risques: refused here rather than answered under the wrong tab.
                String lu = McpArgs.choix(mode, "mode", "avant", "avant", "apres");
                yield new BesoinView(choisie, null, analyzeMargin(lu, edition), null, null, null);
            }
            case "typologie" -> new BesoinView(choisie, null, null, analyzeGameCategories(edition), null, null);
            case "volumetrie" -> new BesoinView(choisie, null, null, null, volumes(edition), null);
            default -> new BesoinView(choisie, null, null, null, null, analyzeFeasibility(edition));
        };
    }

    /**
     * The Risques tab of Diagnostic in one tool (ADR 0127). Fragility had no
     * tool before: its report names people — in each line and in its
     * sentence — so it crosses as {@link FragiliteView}, ids and counts only.
     */
    @Tool(
            name = "consulter_risques",
            description = "L'onglet Risques du Diagnostic, sous la section demandée, lu sur le planning persisté "
                    + "sans lancer de résolution ; animateurs par id seul, aucun nom ni phrase. fragilite (défaut) : "
                    + "les points de rupture — par animateur affecté, les postes qu'un désistement effondrerait et "
                    + "ceux que personne d'autre ne peut reprendre (irremplacable, avec le nombre de remplaçants), et "
                    + "les couples stand × créneau qu'au plus un spécialiste peut tenir (specialistes, renforts "
                    + "polyvalents, sévérité) ; aucunAnimateur dit qu'il n'y a rien à mesurer. former : le plan de "
                    + "formation suggéré, typologie par typologie en déficit, avec les candidats DEBUTANT ou AUTONOME "
                    + "à former, triés par jours en tension où ils sont disponibles, souhait puis niveau ; une "
                    + "typologie sans candidat relève du recrutement. tension : la marge « apres » croisée avec la "
                    + "fragilité, chaque créneau noté CRITIQUE, ELEVEE, SURVEILLEE ou CALME par des règles nommées, "
                    + "avec tous les sièges irremplaçables de la tranche ; une tranche commencée est passee.",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = true,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    RisquesView consultRisks(
            @ToolArg(description = "fragilite (défaut), former ou tension", required = false) String section,
            @ToolArg(description = EditionArg.DESCRIPTION) @EditionArg String edition) {
        String choisie = McpArgs.choix(section, "section", "fragilite", "fragilite", "former", "tension");
        return switch (choisie) {
            case "fragilite" ->
                new RisquesView(
                        choisie,
                        FragiliteView.of(fragiliteAnalyzer.analyze(persistenceService.loadPersistedPlanning())),
                        null,
                        null);
            case "former" -> new RisquesView(choisie, null, suggestTrainingPlan(edition), null);
            default -> new RisquesView(choisie, null, null, margeService.tension());
        };
    }

    /** The Repas et pauses report in one tool (ADR 0127): the legal breaks, and the walks between two shifts. */
    @Tool(
            name = "consulter_repas_pauses",
            description = "Le rapport Repas et pauses du planning persisté, sous la section demandée, lu sous les "
                    + "réglages courants sans lancer de résolution ; animateurs par id seul. pauses (défaut) : pour "
                    + "chaque animateur et chaque jour, les séquences de travail ininterrompu, la pause légale due et "
                    + "son placement (une personne à la fois par stand, au plus tard possible), le relais sur le "
                    + "stand, les trous déjà planifiés par la grille, et la coupure repas due par chaque journée à "
                    + "cheval sur une fenêtre repas avec le plus grand trou libre qu'elle y laisse ; une pause sans "
                    + "relais est un écart dur. Filtrable par stand ou aux seules pauses sans relais. enchainements : "
                    + "deux postes consécutifs d'un même animateur sur deux emplacements géolocalisés dont le "
                    + "battement ne laisse pas le temps d'aller de l'un à l'autre à pied, ou seulement en mangeant la "
                    + "pause légale (walkOnBreak) ; geolocated=false dit qu'aucun trajet n'était calculable. Les deux "
                    + "sections se filtrent par date ; standId et sansRelaisSeulement ne valent que pour pauses.",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = true,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    RepasPausesView consultMealsAndBreaks(
            @ToolArg(description = "pauses (défaut) ou enchainements", required = false) String section,
            @ToolArg(description = "Date (AAAA-MM-JJ) : ne garder que ce jour", required = false) String date,
            @ToolArg(
                            description = "Section pauses : id de stand, ne garder que les pauses tenues sur ce stand",
                            required = false)
                    String standId,
            @ToolArg(
                            description = "Section pauses : true, ne garder que les pauses sans relais possible",
                            required = false)
                    Boolean sansRelaisSeulement,
            @ToolArg(description = EditionArg.DESCRIPTION) @EditionArg String edition) {
        String choisie = McpArgs.choix(section, "section", PAUSES, PAUSES, "enchainements");
        if (PAUSES.equals(choisie)) {
            return new RepasPausesView(choisie, analyzePauses(date, standId, sansRelaisSeulement, edition), null);
        }
        if ((standId != null && !standId.isBlank()) || Boolean.TRUE.equals(sansRelaisSeulement)) {
            throw new BusinessError.Invalid("standId et sansRelaisSeulement ne valent que pour la section pauses");
        }
        return new RepasPausesView(choisie, null, analyzeWalks(date, edition));
    }

    @Deprecated
    @Tool(
            name = "volumes",
            description =
                    "Ancien nom, gardé jusqu'en 5.0 : utiliser consulter_besoin (section volumetrie). "
                            + "Volumétrie réelle du problème que construirait la prochaine résolution : nombre "
                            + "d'animateurs, de postes à pourvoir et de contraintes ad hoc, heures à pourvoir (somme des durées "
                            + "effectives des postes) et heures offertes (plafond légal de ce que les animateurs peuvent travailler "
                            + "sur l'événement, jours d'indisponibilité déduits). Les postes ne dépendent que des stands et des créneaux : "
                            + "ils sont comptés avant la saisie d'aucun animateur, et tout est à zéro sans stand ou sans créneau.",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = true,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    ProblemScaleService.ProblemScale volumes(
            @ToolArg(description = EditionArg.DESCRIPTION) @EditionArg String edition) {
        return problemScaleService.compute();
    }

    @Deprecated
    @Tool(
            name = "analyser_faisabilite",
            description = "Ancien nom, gardé jusqu'en 5.0 : utiliser consulter_besoin (section faisabilite). "
                    + "Diagnostic de faisabilité avant résolution : calcul de capacité en Java pur (aucune "
                    + "résolution lancée) sur les données de référence courantes, listant les causes structurellement "
                    + "bloquantes : créneau en sous-effectif, contraintes ad hoc contradictoires, affectation forcée "
                    + "intenable (jour déclaré indisponible, règle dure sur toutes les places de sa portée, "
                    + "emploi du temps verrouillé), plafond de jours travaillés d'affilée intenable ou tendu sur "
                    + "la grille ou le plan en place.",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = true,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    FeasibilityReport analyzeFeasibility(@ToolArg(description = EditionArg.DESCRIPTION) @EditionArg String edition) {
        return feasibilityAnalyzer.analyze(
                referenceDataService.listAnimateurs(),
                referenceDataService.listSolvedStands(),
                referenceDataService.listCreneaux(),
                referenceDataService.listContraintesAdHoc(),
                FeasibilityAnalyzer.encadrementMineursActif(referenceDataService.getContraintesDesactivees()),
                FeasibilityAnalyzer.ConsecutiveDaysRule.of(
                        referenceDataService.getContraintesDesactivees(),
                        referenceDataService.getNiveauxObligatoires(),
                        referenceDataService.getParametresQualite()),
                new FeasibilityAnalyzer.PlanContext(
                        planningService.pastHorizon(), persistenceService::loadEmployedByDay));
    }

    /** The same reading as {@code GET /api/planning/arrivees-groupees}: ids only. */
    @Tool(
            name = "analyser_arrivees_groupees",
            description = "Pour chaque arrivée groupée (ajustement ARRIVEE_GROUPEE, covoiturage de 2 à 4 "
                    + "animateurs) du planning persisté, jour par jour : qui travaille, qui ne travaille pas alors "
                    + "qu'un autre membre travaille, l'écart entre les premières arrivées et entre les derniers "
                    + "départs, et si le jour est aligné (tous présents, écarts dans la tolérance de l'édition). "
                    + "misalignedDays compte les jours à revoir. Lu sans lancer de résolution ; animateurs par id "
                    + "seul.",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = true,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    GroupedArrivalAnalyzer.GroupedArrivalReport analyzeGroupedArrivals(
            @ToolArg(description = EditionArg.DESCRIPTION) @EditionArg String edition) {
        return groupedArrivalAnalyzer.analyze(
                persistenceService.loadPersistedPlanning(),
                referenceDataService.listContraintesAdHoc(),
                referenceDataService.getParametresQualite());
    }

    /**
     * The same reading as {@code GET /api/planning/enchainements}: animateurs
     * by id, stands and emplacements by id, nothing else about a person.
     */
    @Deprecated
    @Tool(
            name = "analyser_enchainements",
            description = "Ancien nom, gardé jusqu'en 5.0 : utiliser consulter_repas_pauses (section enchainements). "
                    + "Les enchaînements serrés du planning persisté : deux postes consécutifs d'un même "
                    + "animateur le même jour, sur deux emplacements géolocalisés différents, dont le battement ne "
                    + "laisse pas le temps d'aller de l'un à l'autre à pied (distance à vol d'oiseau × facteur de "
                    + "détour ÷ vitesse de marche, arrondi à la minute supérieure), ou ne le laisse qu'en mangeant la "
                    + "pause légale (walkOnBreak). Pour chacun : ids de l'animateur, des postes, des stands et "
                    + "des emplacements, heures, distance, trajet, battement et minutes manquantes au-delà de la "
                    + "tolérance. Toutes les paires, battement nul compris, et le passé aussi. Lu sous les réglages "
                    + "de trajet courants, sans lancer de résolution ; geolocated=false dit qu'aucun "
                    + "trajet n'était calculable. Filtrable par date.",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = true,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    WalkSequenceAnalyzer.WalkSequenceReport analyzeWalks(
            @ToolArg(description = "Date (AAAA-MM-JJ) : ne garder que ce jour", required = false) String date,
            @ToolArg(description = EditionArg.DESCRIPTION) @EditionArg String edition) {
        WalkSequenceAnalyzer.WalkSequenceReport rapport = walkSequenceAnalyzer.analyze(
                persistenceService.loadPersistedPlanning(),
                referenceDataService.getParametresQualite(),
                referenceDataService.getParametresLegaux());
        LocalDate jour = McpArgs.date(date, "date");
        if (jour == null) {
            return rapport;
        }
        return new WalkSequenceAnalyzer.WalkSequenceReport(
                rapport.walkingSpeedKmH(),
                rapport.detourFactor(),
                rapport.toleranceMinutes(),
                rapport.geolocated(),
                rapport.walks().stream()
                        .filter(walk -> jour.equals(walk.date()))
                        .toList());
    }

    /**
     * The same computation as {@code GET /api/staffing}, through the same
     * {@link StaffingService}: on the seats a real solve would have to fill,
     * not on stands × créneaux. Counting them in the
     * caller drifted from the real problem, badly — recurring horaires and the
     * reduced effectif during meal windows both change the count.
     */
    @Deprecated
    @Tool(
            name = "analyser_effectifs",
            description = "Ancien nom, gardé jusqu'en 5.0 : utiliser consulter_besoin (section borne). "
                    + "Combien d'animateurs il faut au minimum pour couvrir l'événement, et pourquoi : pic "
                    + "simultané, pic avec pause, charge horaire de la semaine la plus chargée, rotation sur les jours "
                    + "(nul ne travaille plus de six jours par semaine ISO), coupure repas (une grille qui ne s'arrête "
                    + "jamais entre le matin et l'après-midi ne se tient pas à son seul pic ; nulle quand la règle est "
                    + "désactivée), jour et semaine critiques, le détail par jour "
                    + "et par semaine, plus le goulot par typologie : les mêmes bornes sur les sièges d'une seule typologie, "
                    + "face aux animateurs qui la déclarent. Calcul en Java pur, aucune résolution lancée. Le minimum "
                    + "est la borne exacte (flot maximal sur les jours de repos) que l'écran Besoin donne pour réponse "
                    + "par défaut ; il ignore compétences et repos quotidien, que seul un calcul par équipe fictive "
                    + "(option avancée de l'écran) vérifie ; minimumAvecIndisponibilites y "
                    + "ajoute, à part, une projection sur les indisponibilités déjà déclarées. Répond avant la saisie "
                    + "d'aucun animateur : les sièges ne dépendent que des stands et des créneaux, et "
                    + "referentielsManquants nomme ce qui n'est pas encore saisi.",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = true,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    StaffingSummary analyzeEffectifs(@ToolArg(description = EditionArg.DESCRIPTION) @EditionArg String edition) {
        return staffingService.analyzeEdition();
    }

    /**
     * The same computation as {@code GET /api/formation}, through the same
     * {@link FormationService}, with the candidates reduced to their ids: the
     * screen and the CSV name them, an assistant reads who they are through
     * the tools that anonymise.
     */
    @Deprecated
    @Tool(
            name = "plan_formation",
            description = "Ancien nom, gardé jusqu'en 5.0 : utiliser consulter_risques (section former). "
                    + "Plan de formation suggéré, typologie par typologie : pour chaque typologie en déficit — "
                    + "manque signalé par consulter_besoin (section borne), ou au moins un couple stand × créneau tenu par un seul "
                    + "spécialiste ou aucun dans la fragilité du planning persisté —, les chiffres de ces deux "
                    + "analyses (manque, competencesRares, groupesSansSpecialiste, postesIrremplacables), les jours "
                    + "en tension, et les candidats à former : animateurs DEBUTANT ou AUTONOME sur la typologie, "
                    + "jamais un REFERENT, un polyvalent ni une personne sans la compétence. Tri affiché : jours en "
                    + "tension où le candidat est disponible, puis souhait, puis niveau (AUTONOME avant DEBUTANT). "
                    + "Une typologie sans candidat relève du recrutement. Ids seulement, aucun nom. Lecture seule, "
                    + "aucune résolution ni simulation ; sans planning persisté, seule la partie besoin parle "
                    + "(planPersiste = false).",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = true,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    PlanFormationView suggestTrainingPlan(@ToolArg(description = EditionArg.DESCRIPTION) @EditionArg String edition) {
        return PlanFormationView.of(formationService.plan());
    }

    /**
     * The same reading as {@code GET /api/planning/typologies}, through the
     * same {@link TypologieAnalyzer}, every list of people reduced to ids and
     * a count: the screen names who holds a game, an assistant reads who they
     * are through the tools that anonymise.
     */
    @Deprecated
    @Tool(
            name = "analyser_typologies",
            description = "Ancien nom, gardé jusqu'en 5.0 : utiliser consulter_besoin (section typologie). "
                    + "Le planning persisté lu par typologie de jeu, sur toute l'édition : pour chaque typologie "
                    + "(celles du référentiel, tenues ou non, et celles qu'un stand propose sans que le référentiel "
                    + "les déclare), les heures et les postes tenus, les heures jour par jour (heuresParJour, un jour "
                    + "sans poste tenu n'y figure pas), et quatre listes d'animateurs par id avec leur compte : "
                    + "affectés (assis à ce jeu par le plan), compétents (le référentiel les y habilite), compétents "
                    + "jamais affectés (une réserve inemployée) et affectés sans la compétence. Les mêmes chiffres "
                    + "que la page Planning, par typologie. Lu sans lancer de résolution ; ids seulement, aucun nom.",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = true,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    TypologiesView analyzeGameCategories(@ToolArg(description = EditionArg.DESCRIPTION) @EditionArg String edition) {
        return TypologiesView.of(typologieAnalyzer.rapport());
    }

    /**
     * The same computation as {@code GET /api/marge}, through the same
     * {@link MargeService}. Where {@code analyser_effectifs} answers « combien
     * faut-il recruter » over the whole event, this one says <em>when</em> the
     * capacity is tight — which is the question a withdrawal, a recruitment or
     * one opening window fewer actually turns on.
     */
    @Deprecated
    @Tool(
            name = "analyser_marge",
            description =
                    "Ancien nom, gardé jusqu'en 5.0 : utiliser consulter_besoin (section marge) ou consulter_risques (section tension). "
                            + "La marge disponible, jour par jour et tranche horaire par tranche horaire : animateurs "
                            + "disponibles à ce moment-là moins sièges à pourvoir. Les tranches sont les créneaux de la grille, "
                            + "donc lisibles aussi bien en amplitudes qu'en vacations. Deux modes : « avant » (par défaut) "
                            + "compare la capacité brute — qui n'a pas déclaré cette date indisponible — aux sièges qu'une "
                            + "résolution devrait pourvoir, et répond avant toute résolution ; « apres » lit le planning "
                            + "persisté et ne compte libre que celui qui n'est pas déjà sur un siège qui chevauche, qui "
                            + "respecte la pause légale entre vacations de part et d'autre, et que les règles dures laissent "
                            + "prendre un siège de la tranche — face aux seuls sièges restés vides. Chaque journée porte sa "
                            + "pire tranche, et le rapport la pire de l'événement. Calcul en Java pur, aucune résolution "
                            + "lancée, compétences hors périmètre (voir consulter_besoin, section borne, pour le goulot par typologie). "
                            + "Lecture optimiste : une tranche annoncée négative l'est, une tranche confortable ne le "
                            + "garantit pas. Troisième mode, « tension » : la marge « apres » croisée avec la fragilité "
                            + "du même planning persisté, chaque tranche notée CRITIQUE, ELEVEE, SURVEILLEE ou CALME par "
                            + "des règles nommées (motifs : SIEGES_VIDES_NON_COUVRABLES, SIEGE_IRREMPLACABLE, "
                            + "STAND_SANS_SPECIALISTE, MARGE_NULLE_AVEC_SIEGES_VIDES, FRAGILES_AU_DELA_DE_LA_MARGE, "
                            + "SIEGES_FRAGILES, SPECIALISTE_UNIQUE_SANS_RENFORT), avec tous les sièges irremplaçables de la "
                            + "tranche, sans troncature, et les animateurs et stands en cause par id seulement ; une "
                            + "tranche déjà commencée est passee, sans gravité.",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = true,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    MarginReading analyzeMargin(
            @ToolArg(
                            description = "« avant » (défaut) : capacité brute contre besoin ; « apres » : les "
                                    + "personnes réellement libres sur le planning persisté ; « tension » : la "
                                    + "marge « apres » croisée avec la fragilité, en quatre niveaux de gravité",
                            required = false)
                    String mode,
            @ToolArg(description = EditionArg.DESCRIPTION) @EditionArg String edition) {
        if ("tension".equalsIgnoreCase(mode)) {
            return margeService.tension();
        }
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
                    + "pourquoi un stand ne génère aucun poste. Chaque jour porte ferie, le libellé du jour férié "
                    + "qui tombe ce jour-là (null sinon). Filtrable sur un stand ; les totaux restent ceux de "
                    + "l'édition.",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = true,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    RapportOuvertures analyzeStandOpenings(
            @ToolArg(description = "Id de stand pour ne détailler que celui-là", required = false) String standId,
            @ToolArg(description = EditionArg.DESCRIPTION) @EditionArg String edition) {
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
            @ToolArg(description = EditionArg.DESCRIPTION) @EditionArg String edition) {
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
            name = "lister_mesures_edition",
            description = "Historique des KPI, une ligne par résolution terminée, toutes éditions confondues et de "
                    + "la plus récente à la plus ancienne : score, couverture des postes, heures et écarts par "
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
     * {@code lister_mesures_edition} is not: a row survives the edition it
     * describes, and the rows of a deleted edition are exactly the ones
     * nothing else could ever clean up.
     */
    @Tool(
            name = "supprimer_mesure_edition",
            description = "Supprime une ligne de l'historique des KPI, désignée par l'id que renvoie "
                    + "lister_mesures_edition. Sert à retirer une résolution ratée qui fausse la comparaison entre "
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
            @ToolArg(description = EditionArg.DESCRIPTION) @EditionArg String edition) {
        return new UsagesView(
                referenceDataService.countAnimateurUsages(animateurIds == null ? List.of() : animateurIds),
                referenceDataService.countStandUsages(standIds == null ? List.of() : standIds),
                referenceDataService.countCreneauUsages(creneauIds == null ? List.of() : creneauIds));
    }

    @Computes
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
    ImportImpact previewImport(@ToolArg(description = EditionArg.DESCRIPTION) @EditionArg String edition) {
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
    @Deprecated
    @Tool(
            name = "analyser_pauses",
            description = "Ancien nom, gardé jusqu'en 5.0 : utiliser consulter_repas_pauses (section pauses). "
                    + "La rotation des pauses légales du planning persisté : pour chaque animateur et chaque "
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
            @ToolArg(description = EditionArg.DESCRIPTION) @EditionArg String edition) {
        PauseAnalyzer.RapportPauses rapport = pauseAnalyzer.analyze(
                persistenceService.loadPersistedPlanning(),
                referenceDataService.getParametresLegaux(),
                referenceDataService.fenetresRepas());
        LocalDate jour = McpArgs.date(date, "date");
        boolean sansRelais = Boolean.TRUE.equals(sansRelaisSeulement);
        boolean planifieesVisibles = standId == null && !sansRelais;
        List<JourneePausesView> journees = rapport.journees().stream()
                .filter(journee -> jour == null || jour.equals(journee.date()))
                .map(journee -> visibleDay(journee, standId, sansRelais, planifieesVisibles))
                .flatMap(Optional::stream)
                .toList();
        List<PauseDueMcpView> dues = journees.stream()
                .flatMap(journee -> journee.sequences().stream())
                .flatMap(sequence -> sequence.pausesDues().stream())
                .toList();
        int relaisManquants =
                (int) dues.stream().filter(pause -> !pause.relaisDisponible()).count();
        return new PausesView(
                rapport.journeesAnalysees(),
                dues.size(),
                relaisManquants,
                rapport.coupuresRepasDues(),
                rapport.coupuresRepasManquantes(),
                journees,
                rapport.message());
    }

    /** One day as the filters leave it, or empty when they leave nothing of it. */
    private static Optional<JourneePausesView> visibleDay(
            PauseAnalyzer.JourneeAnimateurView journee,
            String standId,
            boolean sansRelais,
            boolean planifieesVisibles) {
        List<SequencePausesView> sequences = visibleSequences(journee, standId, sansRelais, planifieesVisibles);
        boolean planifiees = planifieesVisibles
                && (!journee.pausesPlanifiees().isEmpty()
                        || !journee.coupuresRepas().isEmpty());
        if (sequences.isEmpty() && !planifiees) {
            return Optional.empty();
        }
        return Optional.of(new JourneePausesView(
                journee.animateurId(),
                journee.mineur(),
                journee.date(),
                journee.jour(),
                sequences,
                planifieesVisibles ? journee.pausesPlanifiees() : List.of(),
                planifieesVisibles ? journee.coupuresRepas() : List.of()));
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

    /** {@link PlanFormation} without a name: the candidates by id. */
    public record PlanFormationView(
            List<TypologieAFormerView> typologies,
            boolean planPersiste,
            boolean aucuneCompetence,
            boolean aucunAnimateur) {

        static PlanFormationView of(PlanFormation plan) {
            return new PlanFormationView(
                    plan.typologies().stream().map(TypologieAFormerView::of).toList(),
                    plan.planPersiste(),
                    plan.aucuneCompetence(),
                    plan.aucunAnimateur());
        }
    }

    /** {@link RapportTypologies}, its people by id and count. */
    public record TypologiesView(List<TypologieView> typologies, List<String> jours) {

        static TypologiesView of(RapportTypologies rapport) {
            return new TypologiesView(
                    rapport.typologies().stream().map(TypologieView::of).toList(), rapport.jours());
        }
    }

    /**
     * {@link LigneTypologie} without a name: each of its four lists of people
     * becomes the ids and how many they are.
     */
    public record TypologieView(
            String typologie,
            String label,
            boolean polyvalent,
            Integer maxCreneauxParAnimateur,
            double heures,
            int postes,
            Map<String, Double> heuresParJour,
            AnimateursView affectes,
            AnimateursView competents,
            AnimateursView competentsJamaisAffectes,
            AnimateursView affectesSansCompetence) {

        static TypologieView of(LigneTypologie ligne) {
            return new TypologieView(
                    ligne.typologie(),
                    ligne.label(),
                    ligne.polyvalent(),
                    ligne.maxCreneauxParAnimateur(),
                    ligne.heures(),
                    ligne.postes(),
                    ligne.heuresParJour(),
                    AnimateursView.of(ligne.animateursAffectes()),
                    AnimateursView.of(ligne.animateursCompetents()),
                    AnimateursView.of(ligne.competentsJamaisAffectes()),
                    AnimateursView.of(ligne.affectesSansCompetence()));
        }
    }

    /** A list of people of the typologie reading: how many, and who by id. */
    public record AnimateursView(int nombre, List<String> animateurIds) {

        static AnimateursView of(List<AnimateurTypologie> animateurs) {
            List<String> ids = animateurs.stream()
                    .map(AnimateurTypologie::animateurId)
                    .sorted()
                    .toList();
            return new AnimateursView(ids.size(), ids);
        }
    }

    /** {@link TypologieAFormer}, its candidates by id. */
    public record TypologieAFormerView(
            String typologie,
            String label,
            boolean polyvalent,
            int manque,
            int specialistes,
            int competencesRares,
            int groupesSansSpecialiste,
            int postesIrremplacables,
            List<LocalDate> joursTension,
            List<CandidatFormationView> candidats) {

        static TypologieAFormerView of(TypologieAFormer ligne) {
            return new TypologieAFormerView(
                    ligne.typologie(),
                    ligne.label(),
                    ligne.polyvalent(),
                    ligne.manque(),
                    ligne.specialistes(),
                    ligne.competencesRares(),
                    ligne.groupesSansSpecialiste(),
                    ligne.postesIrremplacables(),
                    ligne.joursTension(),
                    ligne.candidats().stream()
                            .map(candidat -> new CandidatFormationView(
                                    candidat.animateurId(),
                                    candidat.niveau(),
                                    candidat.souhait(),
                                    candidat.joursTensionDisponibles()))
                            .toList());
        }
    }

    /** One candidate, by id. */
    public record CandidatFormationView(
            String animateurId, NiveauCompetence niveau, boolean souhait, int joursTensionDisponibles) {}

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

    /** What {@code consulter_besoin} answers: the section asked, the others left out. */
    public record BesoinView(
            String section,
            @JsonInclude(JsonInclude.Include.NON_NULL) StaffingSummary borne,
            @JsonInclude(JsonInclude.Include.NON_NULL) MarginReading marge,
            @JsonInclude(JsonInclude.Include.NON_NULL) TypologiesView typologie,
            @JsonInclude(JsonInclude.Include.NON_NULL) ProblemScaleService.ProblemScale volumetrie,
            @JsonInclude(JsonInclude.Include.NON_NULL) FeasibilityReport faisabilite) {}

    /** What {@code consulter_risques} answers: the section asked, the others left out. */
    public record RisquesView(
            String section,
            @JsonInclude(JsonInclude.Include.NON_NULL) FragiliteView fragilite,
            @JsonInclude(JsonInclude.Include.NON_NULL) PlanFormationView former,
            @JsonInclude(JsonInclude.Include.NON_NULL) MarginReading tension) {}

    /** What {@code consulter_repas_pauses} answers: the section asked, the other left out. */
    public record RepasPausesView(
            String section,
            @JsonInclude(JsonInclude.Include.NON_NULL) PausesView pauses,
            @JsonInclude(JsonInclude.Include.NON_NULL) WalkSequenceAnalyzer.WalkSequenceReport enchainements) {}

    /**
     * {@link RapportFragilite} without a name: the lines lose their display
     * name, and the report its sentence, which names the most fragile person.
     */
    public record FragiliteView(
            List<AnimateurFragiliteView> animateurs,
            List<CompetenceRareView> competencesRares,
            int totalCompetencesRares,
            int groupesSansSpecialiste,
            int groupesAnalyses,
            int groupesDejaSousEffectif,
            int animateursIrremplacables,
            boolean polyvalentConfigure,
            boolean aucunAnimateur) {

        static FragiliteView of(RapportFragilite rapport) {
            return new FragiliteView(
                    rapport.animateurs().stream()
                            .map(AnimateurFragiliteView::of)
                            .toList(),
                    rapport.competencesRares().stream()
                            .map(CompetenceRareView::of)
                            .toList(),
                    rapport.totalCompetencesRares(),
                    rapport.groupesSansSpecialiste(),
                    rapport.groupesAnalyses(),
                    rapport.groupesDejaSousEffectif(),
                    rapport.animateursIrremplacables(),
                    rapport.polyvalentConfigure(),
                    rapport.aucunAnimateur());
        }
    }

    /** {@link AnimateurFragilite} without the display name. */
    public record AnimateurFragiliteView(
            String animateurId,
            boolean polyvalent,
            int affectations,
            int postesEffondres,
            int postesIrremplacables,
            int competencesRares,
            SeveriteFragilite severite,
            List<PosteFragile> postes,
            int postesNonDetailles) {

        static AnimateurFragiliteView of(AnimateurFragilite ligne) {
            return new AnimateurFragiliteView(
                    ligne.animateurId(),
                    ligne.polyvalent(),
                    ligne.affectations(),
                    ligne.postesEffondres(),
                    ligne.postesIrremplacables(),
                    ligne.competencesRares(),
                    ligne.severite(),
                    ligne.postes(),
                    ligne.postesNonDetailles());
        }
    }

    /** {@link CompetenceRare} without the single specialist's display name. */
    public record CompetenceRareView(
            String standId,
            String standNom,
            long creneauId,
            LocalDate date,
            int jour,
            LocalTime heureDebut,
            LocalTime heureFin,
            List<String> typologies,
            int specialistes,
            String animateurId,
            int renforts,
            boolean pourvu,
            SeveriteFragilite severite) {

        static CompetenceRareView of(CompetenceRare ligne) {
            return new CompetenceRareView(
                    ligne.standId(),
                    ligne.standNom(),
                    ligne.creneauId(),
                    ligne.date(),
                    ligne.jour(),
                    ligne.heureDebut(),
                    ligne.heureFin(),
                    ligne.typologies(),
                    ligne.specialistes(),
                    ligne.animateurId(),
                    ligne.renforts(),
                    ligne.pourvu(),
                    ligne.severite());
        }
    }
}
