package dev.sylvain.planning.mcp;

import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.service.ReferenceDataChangeTracker;
import dev.sylvain.planning.service.analyse.EquiteService;
import dev.sylvain.planning.service.analyse.EquiteService.ColonneSolveur;
import dev.sylvain.planning.service.analyse.EquiteService.LigneEquite;
import dev.sylvain.planning.service.analyse.EquiteService.RapportEquite;
import dev.sylvain.planning.service.analyse.EquiteService.SyntheseColonne;
import dev.sylvain.planning.service.analyse.FeasibilityAnalyzer;
import dev.sylvain.planning.service.analyse.FeasibilityAnalyzer.FeasibilityReport;
import dev.sylvain.planning.service.analyse.PlanningHoursService;
import dev.sylvain.planning.service.analyse.PlanningHoursService.HeuresAnimateur;
import dev.sylvain.planning.service.referentiel.ReferenceDataService;
import dev.sylvain.planning.service.solve.DeplacementService;
import dev.sylvain.planning.service.solve.PlanningPersistenceService;
import dev.sylvain.planning.service.solve.PlanningService;
import dev.sylvain.planning.service.solve.PlanningWhatIf.AffectationExplanation;
import dev.sylvain.planning.service.solve.PlanningWhatIf.ContrainteImpact;
import dev.sylvain.planning.service.solve.PlanningWhatIf.DeplacementSimulation;
import dev.sylvain.planning.service.solve.PlanningWhatIf.SuggestionsReparation;
import dev.sylvain.planning.service.solve.PlanningWhatIf.SwapSimulation;
import dev.sylvain.planning.service.solve.ProblemScaleService;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * MCP tools around the planning itself: the size of the problem the next
 * solve will build, the pre-solve feasibility report, what is persisted from
 * the last solve, the hours worked per animateur, and the per-poste
 * explanation/swap simulation of {@code AffectationExplanationResource}.
 *
 * <p>The explanation/swap/hours REST endpoints take a whole
 * {@code PlanningEvenement} in their body; here they always run against the
 * planning persisted by the last solve, since an assistant has no practical
 * way to send back a payload that can weigh dozens of MB.
 *
 * <p>Nothing returned here carries a name: hours are reported per animateur
 * id, and the human-readable constraint details are passed through
 * {@link AnonymisationViolations} first — {@code ViolationFormatter} labels
 * animateurs as "Prénom Nom (id)" for the web UI, which must not leak here.
 */
@EditionCiblee
@RefusMetier
@Journalise
@ApplicationScoped
public class PlanningMcpTools {

    /**
     * Seats returned by {@code lister_affectations} when the caller does not
     * say. The persisted plan of a real festival holds a few thousand of them;
     * the questions actually asked of it are about one stand, one animateur or
     * one créneau, and the whole-plan question is answered by
     * {@code synthese_affectations} instead.
     */
    static final int LIMITE_AFFECTATIONS_DEFAUT = 200;

    @Inject
    DeplacementService deplacementService;

    @Inject
    PlanningService planningService;

    @Inject
    PlanningPersistenceService persistenceService;

    @Inject
    ReferenceDataService referenceDataService;

    @Inject
    FeasibilityAnalyzer feasibilityAnalyzer;

    @Inject
    PlanningHoursService heuresPlanningService;

    @Inject
    EquiteService equiteService;

    @Inject
    ReferenceDataChangeTracker changeTracker;

    @Inject
    ProblemScaleService problemScaleService;

    @Tool(
            description =
                    "Volumétrie réelle du problème que construirait la prochaine résolution : nombre "
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
            @ToolArg(description = EditionArg.DESCRIPTION, required = false) @EditionArg String edition) {
        return problemScaleService.compute();
    }

    @Tool(
            description = "Diagnostic de faisabilité avant résolution : calcul de capacité en Java pur (aucune "
                    + "résolution lancée) sur les données de référence courantes, listant les causes structurellement "
                    + "bloquantes : créneau en sous-effectif, contraintes ad hoc contradictoires.",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = true,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    FeasibilityReport analyser_faisabilite(
            @ToolArg(description = EditionArg.DESCRIPTION, required = false) @EditionArg String edition) {
        return feasibilityAnalyzer.analyze(
                referenceDataService.listAnimateurs(),
                referenceDataService.listSolvedStands(),
                referenceDataService.listCreneaux(),
                referenceDataService.listContraintesAdHoc());
    }

    @Tool(
            description = "État du planning persisté : pour quel groupe de créneaux la dernière résolution a "
                    + "tourné, quand, combien d'affectations sont stockées, et quand les données de référence ont été "
                    + "modifiées pour la dernière fois (si c'est après la résolution, le planning affiché est périmé).",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = true,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    EtatPlanningView etat_planning(
            @ToolArg(description = EditionArg.DESCRIPTION, required = false) @EditionArg String edition) {
        PlanningPersistenceService.PlanningResolution resolution = persistenceService.loadResolution();
        int affectations = persistenceService.countPersistedAssignments();
        Instant derniereModificationDonnees = changeTracker.lastModifiedAt();
        if (resolution == null) {
            return new EtatPlanningView(false, null, affectations, derniereModificationDonnees);
        }
        return new EtatPlanningView(true, resolution.resoluLe(), affectations, derniereModificationDonnees);
    }

    @Tool(
            description = "Affectations du dernier planning persisté, filtrables par stand, par créneau ou par "
                    + "animateur. Ne renvoie que des ids, jamais de données personnelles. Les postes non pourvus ont un "
                    + "animateurId nul. La liste est plafonnée (200 par défaut) : total dit combien de postes "
                    + "correspondent réellement aux filtres. Pour une vue d'ensemble, préférer synthese_affectations.",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = true,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    AffectationsView lister_affectations(
            @ToolArg(description = "Id de stand pour filtrer", required = false) String standId,
            @ToolArg(description = "Id de créneau pour filtrer", required = false) Long creneauId,
            @ToolArg(description = "Id d'animateur pour filtrer", required = false) String animateurId,
            @ToolArg(description = "Ne garder que les postes non pourvus", required = false)
                    Boolean seulementNonPourvus,
            @ToolArg(description = "Nombre maximum d'affectations renvoyées (défaut 200)", required = false)
                    Integer limite,
            @ToolArg(description = EditionArg.DESCRIPTION, required = false) @EditionArg String edition) {
        List<PosteAffectation> retenus = postesFiltres(standId, creneauId, animateurId, seulementNonPourvus);
        return new AffectationsView(
                retenus.size(),
                retenus.stream()
                        .limit(McpArgs.limite(limite, LIMITE_AFFECTATIONS_DEFAUT))
                        .map(PlanningMcpTools::toView)
                        .toList());
    }

    /**
     * The one answer that scales with the event instead of with the plan: a
     * real festival persists thousands of seats, and "où ça coince ?" asked
     * with {@code lister_affectations} costs an assistant its whole context
     * before it can even see that one stand is short on Saturday.
     */
    @Tool(
            description = "Synthèse du dernier planning persisté : combien de postes sont pourvus, au total puis "
                    + "par stand et par jour. Quelques dizaines de lignes au lieu de plusieurs milliers d'affectations, "
                    + "pour repérer d'un coup d'œil où il manque du monde.",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = true,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    SyntheseView synthese_affectations(
            @ToolArg(description = EditionArg.DESCRIPTION, required = false) @EditionArg String edition) {
        List<PosteAffectation> postes = postesFiltres(null, null, null, null);
        Map<String, LigneSynthese> parStand = new LinkedHashMap<>();
        Map<LocalDate, LigneSynthese> parJour = new TreeMap<>();
        Set<String> animateurs = new HashSet<>();
        for (PosteAffectation poste : postes) {
            boolean pourvu = poste.getAnimateur() != null;
            if (pourvu) {
                animateurs.add(poste.getAnimateur().getId());
            }
            if (poste.getStand() != null) {
                parStand.computeIfAbsent(
                                poste.getStand().getId(),
                                id -> new LigneSynthese(poste.getStand().getNom()))
                        .ajouter(pourvu);
            }
            if (poste.getCreneau() != null && poste.getCreneau().getDate() != null) {
                parJour.computeIfAbsent(poste.getCreneau().getDate(), date -> new LigneSynthese(null))
                        .ajouter(pourvu);
            }
        }
        int pourvus = (int)
                postes.stream().filter(poste -> poste.getAnimateur() != null).count();
        return new SyntheseView(
                postes.size(),
                pourvus,
                postes.size() - pourvus,
                animateurs.size(),
                parStand.entrySet().stream()
                        .map(entree -> new StandSyntheseView(
                                entree.getKey(),
                                entree.getValue().nom,
                                entree.getValue().postes,
                                entree.getValue().pourvus,
                                entree.getValue().postes - entree.getValue().pourvus))
                        .toList(),
                parJour.entrySet().stream()
                        .map(entree -> new JourSyntheseView(
                                entree.getKey(),
                                entree.getValue().postes,
                                entree.getValue().pourvus,
                                entree.getValue().postes - entree.getValue().pourvus))
                        .toList());
    }

    private List<PosteAffectation> postesFiltres(
            String standId, Long creneauId, String animateurId, Boolean seulementNonPourvus) {
        PlanningEvenement planning = persistenceService.loadPersistedPlanning();
        if (planning == null || planning.getPostes() == null) {
            return List.of();
        }
        return planning.getPostes().stream()
                .filter(poste -> standId == null
                        || (poste.getStand() != null
                                && standId.equals(poste.getStand().getId())))
                .filter(poste -> creneauId == null
                        || (poste.getCreneau() != null
                                && creneauId.equals(poste.getCreneau().getId())))
                .filter(poste -> animateurId == null
                        || (poste.getAnimateur() != null
                                && animateurId.equals(poste.getAnimateur().getId())))
                .filter(poste -> !Boolean.TRUE.equals(seulementNonPourvus) || poste.getAnimateur() == null)
                .toList();
    }

    @Tool(
            description = "Heures travaillées par animateur d'après le dernier planning persisté, par semaine ISO "
                    + "et au total. Les animateurs sont désignés par id seul.",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = true,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    HeuresView heures_travaillees(
            @ToolArg(description = EditionArg.DESCRIPTION, required = false) @EditionArg String edition) {
        PlanningEvenement planning = persistenceService.loadPersistedPlanning();
        if (planning == null || planning.getPostes() == null) {
            return new HeuresView(List.of(), List.of());
        }
        PlanningHoursService.HeuresRapport rapport = heuresPlanningService.compute(planning);
        return new HeuresView(
                rapport.semaines(),
                rapport.animateurs().stream().map(PlanningMcpTools::toView).toList());
    }

    @Tool(
            description =
                    "Tableau d'équité du dernier planning persisté : par animateur affecté, heures totales "
                            + "et par semaine ISO, heures de soirée (après l'heure paramétrée), de week-end et de jour férié, "
                            + "postes et postes pénibles, stands, typologies et emplacements distincts, part des postes sur une "
                            + "typologie souhaitée ou appréciée, jours travaillés, de repos et plus longue série ; par colonne, "
                            + "médiane, min, max et écart-type, et si le solveur la mesure. Les animateurs sont désignés par id seul.",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = true,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    EquiteView equite_planning(
            @ToolArg(description = EditionArg.DESCRIPTION, required = false) @EditionArg String edition) {
        return toView(equiteService.rapport());
    }

    @Tool(
            description = "Explique le score d'un poste du dernier planning persisté : contraintes violées et "
                    + "contraintes respectées le concernant. Ne relance aucune résolution.",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = true,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    ExplicationView expliquer_affectation(
            @ToolArg(description = "Id du poste") String posteId,
            @ToolArg(description = EditionArg.DESCRIPTION, required = false) @EditionArg String edition) {
        AffectationExplanation explication = planningService.explainAffectation(persistedPlanning(), posteId);
        return new ExplicationView(
                explication.posteId(),
                explication.animateurId(),
                String.valueOf(explication.score()),
                toViews(explication.contraintesViolees()),
                toViews(explication.contraintesRespectees()));
    }

    @Tool(
            description = "Simule l'affectation d'un poste à un autre animateur sur le dernier planning persisté, "
                    + "et renvoie l'impact sur le score. Ne persiste rien et ne relance aucune résolution.",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = true,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    SwapView simuler_swap(
            @ToolArg(description = "Id du poste") String posteId,
            @ToolArg(description = "Id de l'animateur candidat") String animateurId,
            @ToolArg(description = EditionArg.DESCRIPTION, required = false) @EditionArg String edition) {
        SwapSimulation simulation = planningService.simulateSwap(persistedPlanning(), posteId, animateurId);
        return new SwapView(
                simulation.posteId(),
                simulation.animateurActuelId(),
                simulation.animateurCandidatId(),
                String.valueOf(simulation.scoreAvant()),
                String.valueOf(simulation.scoreApres()),
                String.valueOf(simulation.delta()),
                toViews(simulation.contraintesVioleesAvant()),
                toViews(simulation.contraintesVioleesApres()));
    }

    @Tool(
            description = "Cherche qui pourrait tenir un poste du dernier planning persisté et chiffre chaque "
                    + "candidat : score après, delta, violations résolues et violations introduites. Là où simuler_swap "
                    + "note un animateur qu'on lui désigne, celui-ci les cherche. Ne persiste rien et ne relance aucune "
                    + "résolution ; le coût est borné par plafond, et candidatsEligibles/candidatsEvalues disent si la "
                    + "recherche a été exhaustive.",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = true,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    SuggestionsView suggerer_reparations(
            @ToolArg(description = "Id du poste") String posteId,
            @ToolArg(
                            description = "Nombre maximum de candidats simulés (défaut : configuration serveur)",
                            required = false)
                    Integer plafond,
            @ToolArg(description = EditionArg.DESCRIPTION, required = false) @EditionArg String edition) {
        SuggestionsReparation suggestions = planningService.suggererReparations(persistedPlanning(), posteId, plafond);
        return new SuggestionsView(
                suggestions.posteId(),
                suggestions.animateurActuelId(),
                String.valueOf(suggestions.scoreAvant()),
                toViews(suggestions.contraintesVioleesAvant()),
                suggestions.candidatsEligibles(),
                suggestions.candidatsEvalues(),
                suggestions.plafond(),
                suggestions.suggestions().stream()
                        .map(suggestion -> new SuggestionView(
                                suggestion.animateurId(),
                                String.valueOf(suggestion.scoreApres()),
                                String.valueOf(suggestion.delta()),
                                toViews(suggestion.violationsResolues()),
                                toViews(suggestion.violationsIntroduites())))
                        .toList());
    }

    /**
     * The only tool here that writes into the plan itself. Kept apart from
     * {@code simuler_swap} rather than added to it as a flag: an assistant
     * that scores a move and applies it in the same call has no step left at
     * which a human could say no.
     */
    @Tool(
            description = "Affecte un poste du planning persisté à un animateur — ou le vide si aucun animateur "
                    + "n'est donné. Ce poste seul change de main, aucun autre n'est touché et aucune résolution n'est "
                    + "relancée. Refusé si le poste est verrouillé.",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = false,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    ReaffectationView affecter_poste(
            @ToolArg(description = "Id du poste") String posteId,
            @ToolArg(description = "Id de l'animateur ; omis, le poste est vidé", required = false) String animateurId,
            @ToolArg(description = EditionArg.DESCRIPTION, required = false) @EditionArg String edition) {
        String precedent = persistedPlanning().getPostes().stream()
                .filter(poste -> poste.getId().equals(posteId))
                .findFirst()
                .map(poste -> poste.getAnimateur() == null
                        ? null
                        : poste.getAnimateur().getId())
                .orElse(null);
        planningService.applyReparation(posteId, animateurId);
        return new ReaffectationView(posteId, precedent, animateurId);
    }

    @Tool(
            description = "Chiffre le déplacement d'une affectation du planning persisté sans rien écrire : "
                    + "le siège posteId déposé sur un autre siège (posteCibleId — vide, l'animateur y va et son siège se "
                    + "libère ; occupé, les deux échangent) ou sur une personne (animateurId, qui prend le siège, ou "
                    + "échange le sien si elle en tient un sur le même créneau). Verdict sur tout le planning : "
                    + "casseContrainteDure vrai veut dire que deplacer_affectation refusera.",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = true,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    DeplacementView simuler_deplacement(
            @ToolArg(description = "Id du poste dont l'affectation bouge") String posteId,
            @ToolArg(description = "Id du poste qui la reçoit", required = false) String posteCibleId,
            @ToolArg(description = "Id de l'animateur qui la reçoit, si aucun poste n'est donné", required = false)
                    String animateurId,
            @ToolArg(description = EditionArg.DESCRIPTION, required = false) @EditionArg String edition) {
        return toView(deplacementService.simulate(posteId, posteCibleId, animateurId));
    }

    /**
     * Kept apart from {@code simuler_deplacement} for the reason
     * {@code affecter_poste} is kept apart from {@code simuler_swap}: a tool
     * that scores a move and applies it in the same call leaves no step at
     * which a human could say no.
     */
    @Tool(
            description =
                    "Déplace une affectation du planning persisté, comme simuler_deplacement le décrit, "
                            + "et l'écrit : deux sièges au plus changent de main, aucune résolution n'est relancée. Refusé si le "
                            + "déplacement casserait une règle dure, si un des sièges est verrouillé, ou pendant une résolution.",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = false,
                            destructiveHint = false,
                            idempotentHint = false,
                            openWorldHint = false))
    DeplacementView deplacer_affectation(
            @ToolArg(description = "Id du poste dont l'affectation bouge") String posteId,
            @ToolArg(description = "Id du poste qui la reçoit", required = false) String posteCibleId,
            @ToolArg(description = "Id de l'animateur qui la reçoit, si aucun poste n'est donné", required = false)
                    String animateurId,
            @ToolArg(
                            description =
                                    "Id de l'animateur que l'appelant croit sur le siège de départ (précondition : "
                                            + "refusé si quelqu'un d'autre l'occupe ; omis, pas de contrôle)",
                            required = false)
                    String occupantAttendu,
            @ToolArg(description = EditionArg.DESCRIPTION, required = false) @EditionArg String edition) {
        return toView(deplacementService.apply(posteId, posteCibleId, animateurId, occupantAttendu));
    }

    private static DeplacementView toView(DeplacementSimulation simulation) {
        return new DeplacementView(
                simulation.posteSourceId(),
                simulation.posteCibleId(),
                simulation.animateurSourceId(),
                simulation.animateurCibleId(),
                String.valueOf(simulation.scoreAvant()),
                String.valueOf(simulation.scoreApres()),
                String.valueOf(simulation.delta()),
                simulation.casseContrainteDure(),
                simulation.nouvellesViolationsDures().stream()
                        .map(violation -> violation.description() + " (+" + violation.matchesSupplementaires() + ")")
                        .toList());
    }

    private PlanningEvenement persistedPlanning() {
        PlanningEvenement planning = persistenceService.loadPersistedPlanning();
        if (planning == null
                || planning.getPostes() == null
                || planning.getPostes().isEmpty()) {
            throw new IllegalStateException("Aucun planning persisté : lancez d'abord une résolution.");
        }
        return planning;
    }

    private static List<ContrainteImpactView> toViews(List<ContrainteImpact> impacts) {
        return impacts.stream()
                .map(impact -> new ContrainteImpactView(
                        impact.name(),
                        impact.niveau(),
                        impact.categorie(),
                        impact.description(),
                        impact.matchCount(),
                        AnonymisationViolations.anonymiser(impact.details())))
                .toList();
    }

    private static AffectationView toView(PosteAffectation poste) {
        return new AffectationView(
                poste.getId(),
                poste.getStand() == null ? null : poste.getStand().getId(),
                poste.getStand() == null ? null : poste.getStand().getNom(),
                poste.getCreneau() == null ? null : poste.getCreneau().getId(),
                poste.getCreneau() == null ? null : poste.getCreneau().getDate(),
                poste.getHeureDebutEffective(),
                poste.getHeureFinEffective(),
                poste.getAnimateur() == null ? null : poste.getAnimateur().getId());
    }

    private static HeuresAnimateurView toView(HeuresAnimateur ligne) {
        return new HeuresAnimateurView(ligne.animateurId(), ligne.heuresParSemaine(), ligne.total());
    }

    static EquiteView toView(RapportEquite rapport) {
        return new EquiteView(
                rapport.heureDebutSoiree(),
                rapport.semaines(),
                rapport.lignes().stream().map(PlanningMcpTools::toView).toList(),
                rapport.syntheses(),
                rapport.colonnesSolveur());
    }

    private static LigneEquiteView toView(LigneEquite ligne) {
        return new LigneEquiteView(
                ligne.animateurId(),
                ligne.heuresTotal(),
                ligne.heuresParSemaine(),
                ligne.heuresSoiree(),
                ligne.heuresWeekEnd(),
                ligne.heuresJourFerie(),
                ligne.postes(),
                ligne.postesPenibles(),
                ligne.standsDistincts(),
                ligne.typologiesDistinctes(),
                ligne.emplacementsDistinctsParJourMax(),
                ligne.tauxSouhaits(),
                ligne.tauxAppreciation(),
                ligne.joursTravailles(),
                ligne.joursRepos(),
                ligne.plusLongueSerie());
    }

    /** @param resolu false when nothing has ever been solved */
    public record EtatPlanningView(
            boolean resolu, Instant resoluLe, int affectationsPersistees, Instant derniereModificationDonnees) {}

    public record AffectationView(
            String posteId,
            String standId,
            String standNom,
            Long creneauId,
            LocalDate date,
            LocalTime heureDebut,
            LocalTime heureFin,
            String animateurId) {}

    /** @param total seats matching the filters, which may exceed the number returned */
    public record AffectationsView(int total, List<AffectationView> affectations) {}

    public record SyntheseView(
            int postesTotal,
            int postesPourvus,
            int postesNonPourvus,
            int animateursAffectes,
            List<StandSyntheseView> parStand,
            List<JourSyntheseView> parJour) {}

    public record StandSyntheseView(String standId, String standNom, int postes, int pourvus, int nonPourvus) {}

    public record JourSyntheseView(LocalDate date, int postes, int pourvus, int nonPourvus) {}

    /** Mutable tally behind one line of the synthèse, never exposed. */
    private static final class LigneSynthese {

        private final String nom;
        private int postes;
        private int pourvus;

        LigneSynthese(String nom) {
            this.nom = nom;
        }

        void ajouter(boolean pourvu) {
            postes++;
            if (pourvu) {
                pourvus++;
            }
        }
    }

    public record HeuresView(List<String> semaines, List<HeuresAnimateurView> animateurs) {}

    public record HeuresAnimateurView(String animateurId, Map<String, Double> heuresParSemaine, double total) {}

    /** The equity table, the same shape as the REST one minus the names. */
    public record EquiteView(
            LocalTime heureDebutSoiree,
            List<String> semaines,
            List<LigneEquiteView> lignes,
            Map<String, SyntheseColonne> syntheses,
            List<ColonneSolveur> colonnesSolveur) {}

    public record LigneEquiteView(
            String animateurId,
            double heuresTotal,
            Map<String, Double> heuresParSemaine,
            double heuresSoiree,
            double heuresWeekEnd,
            double heuresJourFerie,
            int postes,
            int postesPenibles,
            int standsDistincts,
            int typologiesDistinctes,
            int emplacementsDistinctsParJourMax,
            double tauxSouhaits,
            double tauxAppreciation,
            int joursTravailles,
            int joursRepos,
            int plusLongueSerie) {}

    public record ExplicationView(
            String posteId,
            String animateurId,
            String score,
            List<ContrainteImpactView> contraintesViolees,
            List<ContrainteImpactView> contraintesRespectees) {}

    public record SwapView(
            String posteId,
            String animateurActuelId,
            String animateurCandidatId,
            String scoreAvant,
            String scoreApres,
            String delta,
            List<ContrainteImpactView> contraintesVioleesAvant,
            List<ContrainteImpactView> contraintesVioleesApres) {}

    /**
     * @param candidatsEligibles animateurs the search could have tried
     * @param candidatsEvalues   animateurs it actually simulated; below
     *                           {@code candidatsEligibles} the list is the best
     *                           of what it saw, not an exhaustive answer
     */
    public record SuggestionsView(
            String posteId,
            String animateurActuelId,
            String scoreAvant,
            List<ContrainteImpactView> contraintesVioleesAvant,
            int candidatsEligibles,
            int candidatsEvalues,
            int plafond,
            List<SuggestionView> suggestions) {}

    public record SuggestionView(
            String animateurId,
            String scoreApres,
            String delta,
            List<ContrainteImpactView> violationsResolues,
            List<ContrainteImpactView> violationsIntroduites) {}

    /** @param animateurPrecedentId who held the seat before, null when it was empty */
    public record ReaffectationView(String posteId, String animateurPrecedentId, String animateurId) {}

    /**
     * A seat movement (issue #308): after it, {@code posteSourceId} holds
     * {@code animateurCibleId} (nobody when null) and {@code posteCibleId}, when
     * set, holds {@code animateurSourceId}. Ids only, like every seat listing here.
     */
    public record DeplacementView(
            String posteSourceId,
            String posteCibleId,
            String animateurSourceId,
            String animateurCibleId,
            String scoreAvant,
            String scoreApres,
            String delta,
            boolean casseContrainteDure,
            List<String> nouvellesViolationsDures) {}

    /** @param details readable lines describing every match, anonymised (animateur id, never a name) */
    public record ContrainteImpactView(
            String nom,
            String niveau,
            String categorie,
            String description,
            int nombreCorrespondances,
            List<String> details) {}
}
