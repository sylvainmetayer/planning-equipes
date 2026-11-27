package dev.sylvain.planning.mcp;

import com.fasterxml.jackson.annotation.JsonInclude;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.SeatPlaces;
import dev.sylvain.planning.service.BusinessError;
import dev.sylvain.planning.service.ReferenceDataChangeTracker;
import dev.sylvain.planning.service.analyse.EquiteService;
import dev.sylvain.planning.service.analyse.EquiteService.ColonneSolveur;
import dev.sylvain.planning.service.analyse.EquiteService.LigneEquite;
import dev.sylvain.planning.service.analyse.EquiteService.RapportEquite;
import dev.sylvain.planning.service.analyse.EquiteService.SyntheseColonne;
import dev.sylvain.planning.service.analyse.PlanningHoursReader;
import dev.sylvain.planning.service.analyse.PlanningHoursReader.HoursReading;
import dev.sylvain.planning.service.analyse.PlanningHoursReader.Source;
import dev.sylvain.planning.service.analyse.PlanningHoursService.HeuresAnimateur;
import dev.sylvain.planning.service.journal.CurrentAction;
import dev.sylvain.planning.service.referentiel.ReferenceDataService;
import dev.sylvain.planning.service.solve.DeplacementService;
import dev.sylvain.planning.service.solve.PlanningPersistenceService;
import dev.sylvain.planning.service.solve.PlanningService;
import dev.sylvain.planning.service.solve.PlanningWhatIf.AffectationExplanation;
import dev.sylvain.planning.service.solve.PlanningWhatIf.ContrainteImpact;
import dev.sylvain.planning.service.solve.PlanningWhatIf.DeplacementSimulation;
import dev.sylvain.planning.service.solve.PlanningWhatIf.SuggestionsReparation;
import dev.sylvain.planning.service.solve.PlanningWhatIf.SwapSimulation;
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
 * MCP tools around the planning itself: what is persisted from the last
 * solve, the load it gives each animateur (hours and fairness), and the per-poste
 * explanation/swap simulation of {@code AffectationExplanationResource}.
 *
 * <p>The explanation/swap REST endpoints take a whole
 * {@code PlanningEvenement} in their body; here they always run against the
 * planning persisted by the last solve, since an assistant has no practical
 * way to send back a payload that can weigh dozens of MB. The hours are read
 * server-side on both sides, from the persisted plan or the publication in
 * force.
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

    private final DeplacementService deplacementService;

    private final PlanningService planningService;

    private final PlanningPersistenceService persistenceService;

    private final ReferenceDataService referenceDataService;

    private final PlanningHoursReader hoursReader;

    private final EquiteService equiteService;

    private final ReferenceDataChangeTracker changeTracker;

    private final CurrentAction currentAction;

    @Inject
    PlanningMcpTools(
            DeplacementService deplacementService,
            PlanningService planningService,
            PlanningPersistenceService persistenceService,
            ReferenceDataService referenceDataService,
            PlanningHoursReader hoursReader,
            EquiteService equiteService,
            ReferenceDataChangeTracker changeTracker,
            CurrentAction currentAction) {
        this.deplacementService = deplacementService;
        this.planningService = planningService;
        this.persistenceService = persistenceService;
        this.referenceDataService = referenceDataService;
        this.hoursReader = hoursReader;
        this.equiteService = equiteService;
        this.changeTracker = changeTracker;
        this.currentAction = currentAction;
    }

    @Tool(
            name = "consulter_planning",
            description = "Lit le dernier planning persisté, sous la vue demandée. etat (défaut) : quand la "
                    + "dernière résolution a tourné, combien d'affectations sont stockées, et quand les données de "
                    + "référence ont changé pour la dernière fois (après la résolution, le planning est périmé). "
                    + "synthese : postes pourvus au total, par stand et par jour, quelques dizaines de lignes pour "
                    + "repérer où il manque du monde. affectations : les sièges, filtrables par stand, créneau, "
                    + "animateur ou postes non pourvus (animateurId nul), plafonnés (200 par défaut), total disant "
                    + "combien correspondent aux filtres. Des ids seulement, jamais de données personnelles ; les "
                    + "filtres ne valent que pour la vue affectations.",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = true,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    PlanningConsultationView consultPlanning(
            @ToolArg(description = "etat (défaut), synthese ou affectations", required = false) String vue,
            @ToolArg(description = "Id de stand pour filtrer (vue affectations)", required = false) String standId,
            @ToolArg(description = "Id de créneau pour filtrer (vue affectations)", required = false) Long creneauId,
            @ToolArg(description = "Id d'animateur pour filtrer (vue affectations)", required = false)
                    String animateurId,
            @ToolArg(description = "Ne garder que les postes non pourvus (vue affectations)", required = false)
                    Boolean seulementNonPourvus,
            @ToolArg(description = "Nombre maximum d'affectations renvoyées (défaut 200)", required = false)
                    Integer limite,
            @ToolArg(description = EditionArg.DESCRIPTION) @EditionArg String edition) {
        String choisie = McpArgs.choix(vue, "vue", "etat", "etat", "synthese", "affectations");
        // Refused rather than ignored: a synthesis read as the one of the stand filtered would mislead.
        if (!"affectations".equals(choisie)
                && (standId != null
                        || creneauId != null
                        || animateurId != null
                        || seulementNonPourvus != null
                        || limite != null)) {
            throw new BusinessError.Invalid("Les filtres ne valent que pour la vue affectations");
        }
        return switch (choisie) {
            case "etat" -> new PlanningConsultationView(choisie, planningState(edition), null, null);
            case "synthese" -> new PlanningConsultationView(choisie, null, summarizeAffectations(edition), null);
            default ->
                new PlanningConsultationView(
                        choisie,
                        null,
                        null,
                        listAffectations(standId, creneauId, animateurId, seulementNonPourvus, limite, edition));
        };
    }

    @Tool(
            name = "analyser_affectation",
            description = "Explique le score d'un siège du dernier planning persisté, lu sous les règles de "
                    + "l'édition (contraintes désactivées, poids) : contraintes non tenues et contraintes respectées le "
                    + "concernant. Ne relance aucune résolution ; pour chiffrer un autre titulaire, "
                    + "simuler_affectation.",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = true,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    ExplicationView analyzeAffectation(
            @ToolArg(description = "Id du poste") String posteId,
            @ToolArg(description = EditionArg.DESCRIPTION) @EditionArg String edition) {
        return explainAffectation(posteId, edition);
    }

    @Computes
    @Tool(
            name = "simuler_affectation",
            description = "Chiffre un changement de titulaire d'un siège du planning persisté, sans rien écrire ni "
                    + "relancer de résolution. mode affecter : le siège posteId donné à animateurId, nul autre ne "
                    + "bouge ; impact sur le score. mode deplacer : le siège posteId déposé sur un autre siège "
                    + "(posteCibleId — vide, l'animateur y va et son siège se libère ; occupé, les deux échangent) ou "
                    + "sur une personne (animateurId, qui prend le siège, ou échange le sien si elle en tient un sur le "
                    + "même créneau) ; casseContrainteDure vrai veut dire que modifier_affectation refusera. mode "
                    + "candidats : cherche qui pourrait tenir le siège et chiffre chaque candidat (écarts résolus et "
                    + "introduits), borné par plafond ; sans candidat viable, rappeler avec depuis = depuis + "
                    + "candidatsEvalues.",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = true,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    SimulationAffectationView simulateAffectation(
            @ToolArg(description = "Id du poste") String posteId,
            @ToolArg(description = "affecter, deplacer ou candidats") String mode,
            @ToolArg(
                            description = "Id de l'animateur candidat (mode affecter) ou qui reçoit le siège (mode "
                                    + "deplacer, si aucun poste n'est donné)",
                            required = false)
                    String animateurId,
            @ToolArg(description = "Id du poste qui reçoit l'affectation (mode deplacer)", required = false)
                    String posteCibleId,
            @ToolArg(
                            description = "Nombre maximum de candidats simulés (mode candidats ; défaut : "
                                    + "configuration serveur)",
                            required = false)
                    Integer plafond,
            @ToolArg(
                            description = "Rang du premier candidat éligible à simuler (mode candidats, défaut 0)",
                            required = false)
                    Integer depuis,
            @ToolArg(description = EditionArg.DESCRIPTION) @EditionArg String edition) {
        String choisi = McpArgs.choix(mode, "mode", null, AFFECTER, DEPLACER, CANDIDATS);
        return switch (choisi) {
            case AFFECTER ->
                new SimulationAffectationView(
                        choisi,
                        simulateSwap(posteId, required(animateurId, "animateurId", choisi), edition),
                        null,
                        null);
            case DEPLACER ->
                new SimulationAffectationView(
                        choisi, null, simulateMove(posteId, posteCibleId, animateurId, edition), null);
            default ->
                new SimulationAffectationView(choisi, null, null, suggestRepairs(posteId, plafond, depuis, edition));
        };
    }

    /**
     * Kept apart from {@code simuler_affectation} rather than added to it as a
     * flag: an assistant that scores a change and applies it in the same call
     * has no step left at which a human could say no.
     */
    @Tool(
            name = "modifier_affectation",
            description = "Change le titulaire d'un siège du planning persisté et l'écrit, sans relancer de "
                    + "résolution. mode affecter : le siège posteId passe à animateurId — ou se vide si aucun "
                    + "animateur n'est donné —, aucun autre siège n'est touché. mode deplacer : comme "
                    + "simuler_affectation le décrit, deux sièges au plus changent de main ; refusé si le déplacement "
                    + "casserait une règle dure. Refusé sur un siège verrouillé, sur un créneau commencé, et pendant "
                    + "une résolution de l'édition.",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = false,
                            destructiveHint = false,
                            idempotentHint = false,
                            openWorldHint = false))
    ModificationAffectationView modifyAffectation(
            @ToolArg(description = "Id du poste") String posteId,
            @ToolArg(description = "affecter ou deplacer") String mode,
            @ToolArg(
                            description = "Id de l'animateur qui prend le siège ; omis en mode affecter, le siège "
                                    + "est vidé",
                            required = false)
                    String animateurId,
            @ToolArg(description = "Id du poste qui reçoit l'affectation (mode deplacer)", required = false)
                    String posteCibleId,
            @ToolArg(
                            description = "Id de l'animateur que l'appelant croit sur le siège de départ (mode "
                                    + "deplacer ; précondition : refusé si quelqu'un d'autre l'occupe)",
                            required = false)
                    String occupantAttendu,
            @ToolArg(description = EditionArg.DESCRIPTION) @EditionArg String edition) {
        String choisi = McpArgs.choix(mode, "mode", null, AFFECTER, DEPLACER);
        if (AFFECTER.equals(choisi)) {
            // Refused rather than ignored: occupantAttendu is a precondition, and a write that drops it is
            // unconditional.
            if (posteCibleId != null && !posteCibleId.isBlank()
                    || occupantAttendu != null && !occupantAttendu.isBlank()) {
                throw new BusinessError.Invalid(
                        "posteCibleId et occupantAttendu ne valent qu'en mode deplacer : en mode affecter, le siège "
                                + "posteId passe à animateurId, sans contrôle de l'occupant");
            }
            return new ModificationAffectationView(choisi, assignPoste(posteId, animateurId, edition), null);
        }
        // The catalogue files the tool under the seat handed over; a move is its own line.
        currentAction.action("AFFECTATION_DEPLACEE");
        return new ModificationAffectationView(
                choisi, null, moveAffectation(posteId, posteCibleId, animateurId, occupantAttendu, edition));
    }

    private static final String AFFECTER = "affecter";
    private static final String DEPLACER = "deplacer";
    private static final String CANDIDATS = "candidats";

    private static String required(String valeur, String champ, String mode) {
        if (valeur == null || valeur.isBlank()) {
            throw new BusinessError.Invalid(champ + " est requis en mode " + mode);
        }
        return valeur;
    }

    @Deprecated
    @Tool(
            name = "etat_planning",
            description = "Ancien nom, gardé jusqu'en 5.0 : utiliser consulter_planning (vue etat). "
                    + "État du planning persisté : pour quel groupe de créneaux la dernière résolution a "
                    + "tourné, quand, combien d'affectations sont stockées, et quand les données de référence ont été "
                    + "modifiées pour la dernière fois (si c'est après la résolution, le planning affiché est périmé).",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = true,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    EtatPlanningView planningState(@ToolArg(description = EditionArg.DESCRIPTION) @EditionArg String edition) {
        PlanningPersistenceService.PlanningResolution resolution = persistenceService.loadResolution();
        int affectations = persistenceService.countPersistedAssignments();
        Instant derniereModificationDonnees = changeTracker.lastModifiedAt();
        if (resolution == null) {
            return new EtatPlanningView(false, null, affectations, derniereModificationDonnees);
        }
        return new EtatPlanningView(true, resolution.resoluLe(), affectations, derniereModificationDonnees);
    }

    @Deprecated
    @Tool(
            name = "lister_affectations",
            description = "Ancien nom, gardé jusqu'en 5.0 : utiliser consulter_planning (vue affectations). "
                    + "Affectations du dernier planning persisté, filtrables par stand, par créneau ou par "
                    + "animateur. Ne renvoie que des ids, jamais de données personnelles. Les postes non pourvus ont un "
                    + "animateurId nul. La liste est plafonnée (200 par défaut) : total dit combien de postes "
                    + "correspondent réellement aux filtres. Pour une vue d'ensemble, préférer synthese_affectations.",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = true,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    AffectationsView listAffectations(
            @ToolArg(description = "Id de stand pour filtrer", required = false) String standId,
            @ToolArg(description = "Id de créneau pour filtrer", required = false) Long creneauId,
            @ToolArg(description = "Id d'animateur pour filtrer", required = false) String animateurId,
            @ToolArg(description = "Ne garder que les postes non pourvus", required = false)
                    Boolean seulementNonPourvus,
            @ToolArg(description = "Nombre maximum d'affectations renvoyées (défaut 200)", required = false)
                    Integer limite,
            @ToolArg(description = EditionArg.DESCRIPTION) @EditionArg String edition) {
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
    @Deprecated
    @Tool(
            name = "synthese_affectations",
            description = "Ancien nom, gardé jusqu'en 5.0 : utiliser consulter_planning (vue synthese). "
                    + "Synthèse du dernier planning persisté : combien de postes sont pourvus, au total puis "
                    + "par stand et par jour. Quelques dizaines de lignes au lieu de plusieurs milliers d'affectations, "
                    + "pour repérer d'un coup d'œil où il manque du monde.",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = true,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    SyntheseView summarizeAffectations(@ToolArg(description = EditionArg.DESCRIPTION) @EditionArg String edition) {
        List<PosteAffectation> postes = postesFiltres(null, null, null, null);
        Map<String, LigneSynthese> parStand = new LinkedHashMap<>();
        Map<LocalDate, LigneSynthese> parJour = new TreeMap<>();
        Set<String> animateurs = new HashSet<>();
        postes.stream()
                .filter(poste -> poste.getAnimateur() != null)
                .forEach(poste -> animateurs.add(poste.getAnimateur().getId()));
        // A seat split on the day (ADR 0066) is one place, counted on its
        // continuation: two people held it, it is staffed once.
        postes = SeatPlaces.places(postes);
        for (PosteAffectation poste : postes) {
            boolean pourvu = poste.getAnimateur() != null;
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
            name = "consulter_charge",
            description = "Le rapport Charge du planning, sous la vue demandée, animateurs désignés par id seul. "
                    + "equite (défaut) : par animateur affecté du dernier planning persisté, heures totales et par "
                    + "semaine ISO, heures de soirée, de week-end et de jour férié, postes et postes pénibles, stands, "
                    + "typologies et emplacements distincts, part des postes sur une typologie souhaitée ou appréciée, "
                    + "jours travaillés, de repos et plus longue série ; par colonne, médiane, min, max, écart-type, et "
                    + "si le solveur la mesure. heures : heures par animateur, par semaine ISO et au total, amplitude "
                    + "journalière maximale et jours au-delà de 12 h (affichage, aucune règle), sur le plan persisté "
                    + "(source persiste, défaut) ou sur la publication en vigueur (source publie, refusée tant que rien "
                    + "n'a été publié) ; la réponse dit quel plan elle a lu et sa date. source ne vaut que pour la vue "
                    + "heures.",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = true,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    ChargeView consultLoad(
            @ToolArg(description = "equite (défaut) ou heures", required = false) String vue,
            @ToolArg(
                            description = "Vue heures : « persiste » (le plan enregistré, par défaut) ou « publie »"
                                    + " (la publication en vigueur)",
                            required = false)
                    String source,
            @ToolArg(description = EditionArg.DESCRIPTION) @EditionArg String edition) {
        String choisie = McpArgs.choix(vue, "vue", "equite", "equite", "heures");
        if ("equite".equals(choisie)) {
            if (source != null) {
                throw new BusinessError.Invalid("source ne vaut que pour la vue heures");
            }
            return new ChargeView(choisie, planningFairness(edition), null);
        }
        return new ChargeView(choisie, null, workedHours(edition, source));
    }

    @Deprecated
    @Tool(
            name = "heures_travaillees",
            description = "Ancien nom, gardé jusqu'en 5.0 : utiliser consulter_charge (vue heures). "
                    + "Heures travaillées par animateur, par semaine ISO et au total, avec l'amplitude journalière maximale et le "
                    + "nombre de jours au-delà de 12 h (affichage, aucune règle), lues côté serveur : sur le "
                    + "dernier planning persisté par défaut (source « persiste »), ou sur la publication en vigueur, ce "
                    + "que les animateurs ont reçu (source « publie »), refusée tant que rien n'a été publié. La réponse "
                    + "dit quel plan elle a lu et sa date. Les animateurs sont désignés par id seul.",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = true,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    HeuresView workedHours(
            @ToolArg(description = EditionArg.DESCRIPTION) @EditionArg String edition,
            @ToolArg(
                            description = "Plan lu : « persiste » (le plan enregistré, par défaut) ou « publie »"
                                    + " (la publication en vigueur)",
                            required = false)
                    String source) {
        HoursReading reading = hoursReader.read(Source.parse(source).orElse(Source.PERSISTE));
        return new HeuresView(
                reading.source(),
                reading.planDate(),
                reading.report().semaines(),
                reading.report().animateurs().stream()
                        .map(PlanningMcpTools::toView)
                        .toList());
    }

    @Deprecated
    @Tool(
            name = "equite_planning",
            description =
                    "Ancien nom, gardé jusqu'en 5.0 : utiliser consulter_charge (vue equite). "
                            + "Tableau d'équité du dernier planning persisté : par animateur affecté, heures totales "
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
    EquiteView planningFairness(@ToolArg(description = EditionArg.DESCRIPTION) @EditionArg String edition) {
        return toView(equiteService.rapport());
    }

    @Deprecated
    @Tool(
            name = "expliquer_affectation",
            description = "Ancien nom, gardé jusqu'en 5.0 : utiliser analyser_affectation. "
                    + "Explique le score d'un poste du dernier planning persisté, lu sous les règles de "
                    + "l'édition (contraintes désactivées, poids) : contraintes non tenues et contraintes respectées le "
                    + "concernant. Ne relance aucune résolution.",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = true,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    ExplicationView explainAffectation(
            @ToolArg(description = "Id du poste") String posteId,
            @ToolArg(description = EditionArg.DESCRIPTION) @EditionArg String edition) {
        AffectationExplanation explication = planningService.persistedExplainAffectation(posteId);
        return new ExplicationView(
                explication.posteId(),
                explication.animateurId(),
                String.valueOf(explication.score()),
                toViews(explication.contraintesViolees()),
                toViews(explication.contraintesRespectees()));
    }

    @Computes
    @Deprecated
    @Tool(
            name = "simuler_swap",
            description = "Ancien nom, gardé jusqu'en 5.0 : utiliser simuler_affectation (mode affecter). "
                    + "Simule l'affectation d'un poste à un autre animateur sur le dernier planning persisté, "
                    + "et renvoie l'impact sur le score. Ne persiste rien et ne relance aucune résolution.",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = true,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    SwapView simulateSwap(
            @ToolArg(description = "Id du poste") String posteId,
            @ToolArg(description = "Id de l'animateur candidat") String animateurId,
            @ToolArg(description = EditionArg.DESCRIPTION) @EditionArg String edition) {
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

    @Computes
    @Deprecated
    @Tool(
            name = "suggerer_reparations",
            description = "Ancien nom, gardé jusqu'en 5.0 : utiliser simuler_affectation (mode candidats). "
                    + "Cherche qui pourrait tenir un poste du dernier planning persisté et chiffre chaque "
                    + "candidat : score après, delta, écarts résolus et écarts introduits. Là où simuler_swap "
                    + "note un animateur qu'on lui désigne, celui-ci les cherche. Ne persiste rien et ne relance aucune "
                    + "résolution ; le coût est borné par plafond, et candidatsEligibles/candidatsEvalues disent si la "
                    + "recherche a été exhaustive. Sans candidat viable, rappeler avec depuis = depuis + "
                    + "candidatsEvalues évalue les candidats suivants.",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = true,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    SuggestionsView suggestRepairs(
            @ToolArg(description = "Id du poste") String posteId,
            @ToolArg(
                            description = "Nombre maximum de candidats simulés (défaut : configuration serveur)",
                            required = false)
                    Integer plafond,
            @ToolArg(
                            description = "Rang du premier candidat éligible à simuler (défaut 0) : la suite d'une "
                                    + "recherche restée sans candidat viable",
                            required = false)
                    Integer depuis,
            @ToolArg(description = EditionArg.DESCRIPTION) @EditionArg String edition) {
        SuggestionsReparation suggestions = planningService.persistedSuggererReparations(posteId, plafond, depuis);
        return new SuggestionsView(
                suggestions.posteId(),
                suggestions.animateurActuelId(),
                String.valueOf(suggestions.scoreAvant()),
                toViews(suggestions.contraintesVioleesAvant()),
                suggestions.candidatsEligibles(),
                suggestions.depuis(),
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
    @Deprecated
    @Tool(
            name = "affecter_poste",
            description = "Ancien nom, gardé jusqu'en 5.0 : utiliser modifier_affectation (mode affecter). "
                    + "Affecte un poste du planning persisté à un animateur — ou le vide si aucun animateur "
                    + "n'est donné. Ce poste seul change de main, aucun autre n'est touché et aucune résolution n'est "
                    + "relancée. Refusé si le poste est verrouillé.",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = false,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    ReaffectationView assignPoste(
            @ToolArg(description = "Id du poste") String posteId,
            @ToolArg(description = "Id de l'animateur ; omis, le poste est vidé", required = false) String animateurId,
            @ToolArg(description = EditionArg.DESCRIPTION) @EditionArg String edition) {
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

    @Computes
    @Deprecated
    @Tool(
            name = "simuler_deplacement",
            description = "Ancien nom, gardé jusqu'en 5.0 : utiliser simuler_affectation (mode deplacer). "
                    + "Chiffre le déplacement d'une affectation du planning persisté sans rien écrire : "
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
    DeplacementView simulateMove(
            @ToolArg(description = "Id du poste dont l'affectation bouge") String posteId,
            @ToolArg(description = "Id du poste qui la reçoit", required = false) String posteCibleId,
            @ToolArg(description = "Id de l'animateur qui la reçoit, si aucun poste n'est donné", required = false)
                    String animateurId,
            @ToolArg(description = EditionArg.DESCRIPTION) @EditionArg String edition) {
        return toView(deplacementService.simulate(posteId, posteCibleId, animateurId));
    }

    /**
     * Kept apart from {@code simuler_deplacement} for the reason
     * {@code affecter_poste} is kept apart from {@code simuler_swap}: a tool
     * that scores a move and applies it in the same call leaves no step at
     * which a human could say no.
     */
    @Deprecated
    @Tool(
            name = "deplacer_affectation",
            description =
                    "Ancien nom, gardé jusqu'en 5.0 : utiliser modifier_affectation (mode deplacer). "
                            + "Déplace une affectation du planning persisté, comme simuler_deplacement le décrit, "
                            + "et l'écrit : deux sièges au plus changent de main, aucune résolution n'est relancée. Refusé si le "
                            + "déplacement casserait une règle dure, si un des sièges est verrouillé, ou pendant une résolution.",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = false,
                            destructiveHint = false,
                            idempotentHint = false,
                            openWorldHint = false))
    DeplacementView moveAffectation(
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
            @ToolArg(description = EditionArg.DESCRIPTION) @EditionArg String edition) {
        return toView(deplacementService.apply(posteId, posteCibleId, animateurId, occupantAttendu));
    }

    private DeplacementView toView(DeplacementSimulation simulation) {
        // Anonymised before « (+n) » is appended: the net of the anonymisation
        // would otherwise read « … (+3) » as a label followed by its id.
        AnonymisationViolations anonymisation = AnonymisationViolations.of(referenceDataService);
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
                        .map(violation -> anonymisation.anonymiser(violation.description()) + " (+"
                                + violation.matchesSupplementaires() + ")")
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

    private List<ContrainteImpactView> toViews(List<ContrainteImpact> impacts) {
        AnonymisationViolations anonymisation = AnonymisationViolations.of(referenceDataService);
        return impacts.stream()
                .map(impact -> new ContrainteImpactView(
                        impact.name(),
                        impact.niveau(),
                        impact.categorie(),
                        impact.description(),
                        impact.matchCount(),
                        anonymisation.anonymiser(impact.details())))
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
        return new HeuresAnimateurView(
                ligne.animateurId(),
                ligne.heuresParSemaine(),
                ligne.total(),
                ligne.amplitudeMaxHeures(),
                ligne.joursAmplitudeElevee());
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

    /** What {@code consulter_charge} answers: the view asked, the other left out. */
    public record ChargeView(
            String vue,
            @JsonInclude(JsonInclude.Include.NON_NULL) EquiteView equite,
            @JsonInclude(JsonInclude.Include.NON_NULL) HeuresView heures) {}

    /** What {@code consulter_planning} answers: the view asked, the other two left out. */
    public record PlanningConsultationView(
            String vue,
            @JsonInclude(JsonInclude.Include.NON_NULL) EtatPlanningView etat,
            @JsonInclude(JsonInclude.Include.NON_NULL) SyntheseView synthese,
            @JsonInclude(JsonInclude.Include.NON_NULL) AffectationsView affectations) {}

    /** What {@code simuler_affectation} answers: the simulation of the mode asked, the others left out. */
    public record SimulationAffectationView(
            String mode,
            @JsonInclude(JsonInclude.Include.NON_NULL) SwapView affectation,
            @JsonInclude(JsonInclude.Include.NON_NULL) DeplacementView deplacement,
            @JsonInclude(JsonInclude.Include.NON_NULL) SuggestionsView candidats) {}

    /** What {@code modifier_affectation} answers: the write of the mode asked, the other left out. */
    public record ModificationAffectationView(
            String mode,
            @JsonInclude(JsonInclude.Include.NON_NULL) ReaffectationView affectation,
            @JsonInclude(JsonInclude.Include.NON_NULL) DeplacementView deplacement) {}

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

    /**
     * @param source   which plan was read: {@code persiste} or {@code publie}
     * @param planDate when that plan was dated — its publication, or its last
     *                 solve or restore; {@code null} when it never was
     */
    public record HeuresView(
            String source, Instant planDate, List<String> semaines, List<HeuresAnimateurView> animateurs) {}

    public record HeuresAnimateurView(
            String animateurId,
            Map<String, Double> heuresParSemaine,
            double total,
            double amplitudeMaxHeures,
            int joursAmplitudeElevee) {}

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
            int depuis,
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
