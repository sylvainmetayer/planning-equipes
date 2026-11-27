package dev.sylvain.planning.mcp;

import com.fasterxml.jackson.annotation.JsonInclude;
import dev.sylvain.planning.service.BusinessError;
import dev.sylvain.planning.service.analyse.PlanningDiagnosticService.ConstraintDiagnostic;
import dev.sylvain.planning.service.journal.CurrentAction;
import dev.sylvain.planning.service.referentiel.ReferenceDataService;
import dev.sylvain.planning.service.solve.ConstraintAnalysisStore;
import dev.sylvain.planning.service.solve.ConstraintAnalysisStore.StoredAnalysis;
import dev.sylvain.planning.service.solve.PlanningService;
import dev.sylvain.planning.solver.ConstraintCatalog;
import dev.sylvain.planning.solver.ConstraintCatalog.ConstraintDefinition;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * MCP tools mirroring {@code ConstraintResource}: the business catalogue of
 * constraints (what each one means, whether it is active, how it scored on
 * the last analysis) plus activation/désactivation.
 */
@EditionCiblee
@RefusMetier
@Journalise
@ApplicationScoped
public class ContrainteMcpTools {

    private final ConstraintAnalysisStore analysisStore;

    private final ReferenceDataService referenceDataService;

    private final PlanningService planningService;

    private final CurrentAction currentAction;

    @Inject
    ContrainteMcpTools(
            ConstraintAnalysisStore analysisStore,
            ReferenceDataService referenceDataService,
            PlanningService planningService,
            CurrentAction currentAction) {
        this.currentAction = currentAction;
        this.analysisStore = analysisStore;
        this.referenceDataService = referenceDataService;
        this.planningService = planningService;
    }

    @Tool(
            name = "lister_contraintes",
            description = "Liste le catalogue métier des contraintes du solveur : niveau (HARD/MEDIUM/SOFT), "
                    + "description, si elle est active, et son score/nombre de correspondances lors de la dernière analyse. "
                    + "Une règle qui a pénalisé la quasi-totalité de ce qu'elle évalue porte ratioPlancher et "
                    + "motifPlancher : ses points sont une constante que la donnée absente au référentiel explique.",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = true,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    List<ContrainteView> listContraintes(@ToolArg(description = EditionArg.DESCRIPTION) @EditionArg String edition) {
        return views(definition -> true);
    }

    private List<ContrainteView> views(Predicate<ConstraintDefinition> retenue) {
        StoredAnalysis analysis = analysisStore.latest();
        Map<String, ConstraintDiagnostic> byName = analysis == null
                ? Map.of()
                : analysis.diagnostic().contraintes().stream()
                        .collect(Collectors.toMap(
                                ConstraintDiagnostic::name, Function.identity(), (first, second) -> first));
        Set<String> desactivees = referenceDataService.getContraintesDesactivees();
        Map<String, Integer> poids = planningService.effectiveConstraintWeights();
        Map<String, Boolean> niveaux = planningService.effectiveLevels();

        return ConstraintCatalog.definitions().stream()
                .filter(retenue)
                .map(definition -> toView(definition, byName.get(definition.name()), desactivees, poids, niveaux))
                .toList();
    }

    /**
     * One setting of one rule per call — its activation, its weight or its
     * level — so the journal line says exactly what moved, as the three REST
     * routes do; the answer is the rule's line of {@code lister_contraintes}
     * as it reads after the change.
     */
    @Tool(
            name = "modifier_contrainte",
            description = "Règle une contrainte pour la prochaine résolution, dans cette édition seulement, un "
                    + "réglage par appel. actif : true la réactive, false la fait ignorer du solveur. poids : à niveau "
                    + "égal, une contrainte de poids 10 pèse deux fois une contrainte de poids 5 ; l'écran propose "
                    + "faible 1, normale 5 (le défaut d'une règle moyenne ou souple), forte 25, tout entier de 1 à 500 "
                    + "est accepté ; poidsParDefaut=true revient au poids configuré. niveau, sur les règles dont "
                    + "lister_contraintes donne niveauReglable : OBLIGATOIRE (le plan qui la viole n'est pas livrable, "
                    + "comme une règle légale) ou PREFERENCE_FORTE, PREFERENCE_NORMALE, PREFERENCE_LEGERE (le poids de "
                    + "cette position, 25, 5 ou 1) ; une règle rendue obligatoire que l'édition ne peut pas tenir "
                    + "donne un plan infaisable : lancer analyser_faisabilite avant le calcul. Rend la ligne de la "
                    + "règle telle que lister_contraintes la lit après le changement.",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = false,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    @WarnsWhileSolving
    ContrainteModifieeView modifyContrainte(
            @ToolArg(description = "Nom technique de la contrainte (voir lister_contraintes)") String nom,
            @ToolArg(description = "true : active, false : désactivée", required = false) Boolean actif,
            @ToolArg(description = "Poids strictement positif", required = false) Integer poids,
            @ToolArg(description = "true : rétablit le poids par défaut", required = false) Boolean poidsParDefaut,
            @ToolArg(
                            description = "OBLIGATOIRE, PREFERENCE_FORTE, PREFERENCE_NORMALE ou PREFERENCE_LEGERE",
                            required = false)
                    String niveau,
            @ToolArg(description = EditionArg.DESCRIPTION) @EditionArg String edition) {
        boolean defaut = Boolean.TRUE.equals(poidsParDefaut);
        // Stated first, so a refused call is journalled under the setting it asked for.
        if (niveau != null) {
            currentAction.action("CONTRAINTE_NIVEAU");
        } else if (actif != null) {
            currentAction.action(actif ? "CONTRAINTE_ACTIVEE" : "CONTRAINTE_DESACTIVEE");
        }
        int reglages = (actif != null ? 1 : 0) + (poids != null ? 1 : 0) + (defaut ? 1 : 0) + (niveau != null ? 1 : 0);
        if (reglages != 1) {
            throw new BusinessError.Invalid(
                    "Un réglage par appel : actif, poids, poidsParDefaut ou niveau, exactement un des quatre");
        }
        String rule;
        if (actif != null) {
            rule = setActive(nom, actif).nom();
        } else if (niveau != null) {
            rule = setLevel(nom, niveau);
        } else {
            rule = setWeight(nom, poids);
        }
        return new ContrainteModifieeView(
                views(definition -> definition.name().equals(rule)).get(0), List.of());
    }

    /** The rule as {@code lister_contraintes} reads it after a change. */
    public record ContrainteModifieeView(
            ContrainteView contrainte,
            @JsonInclude(JsonInclude.Include.NON_EMPTY) List<String> avertissements)
            implements WarningCarrier<ContrainteModifieeView> {

        @Override
        public ContrainteModifieeView withWarning(String code) {
            return new ContrainteModifieeView(contrainte, WarningCodes.with(avertissements, code));
        }
    }

    @Deprecated
    @Tool(
            name = "activer_contrainte",
            description = "Ancien nom, gardé jusqu'en 5.0 : utiliser modifier_contrainte (actif true). "
                    + "Active une contrainte pour le prochain solve (annule une désactivation précédente).",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = false,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    @WarnsWhileSolving
    ToggleResult enableContrainte(
            @ToolArg(description = "Nom technique de la contrainte (voir lister_contraintes)") String nom,
            @ToolArg(description = EditionArg.DESCRIPTION) @EditionArg String edition) {
        return setActive(nom, true);
    }

    @Deprecated
    @Tool(
            name = "desactiver_contrainte",
            description = "Ancien nom, gardé jusqu'en 5.0 : utiliser modifier_contrainte (actif false). "
                    + "Désactive une contrainte pour le prochain solve. Le solveur l'ignorera jusqu'à réactivation.",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = false,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    @WarnsWhileSolving
    ToggleResult disableContrainte(
            @ToolArg(description = "Nom technique de la contrainte (voir lister_contraintes)") String nom,
            @ToolArg(description = EditionArg.DESCRIPTION) @EditionArg String edition) {
        return setActive(nom, false);
    }

    /**
     * Dosage rather than on/off: switching a soft constraint off removes its
     * opinion altogether, where a weight makes it count for more or less
     * against the others -- which is what tuning an edition actually needs.
     *
     * <p>The override belongs to the edition. The weights shipped in
     * {@code application.properties} stay the default for every edition that
     * never touched them, which is what makes the dosage a per-event decision
     * rather than a per-deployment one.</p>
     */
    @Deprecated
    @Tool(
            name = "modifier_poids_contrainte",
            description = "Ancien nom, gardé jusqu'en 5.0 : utiliser modifier_contrainte (poids). "
                    + "Change le poids d'une contrainte pour la prochaine résolution, dans cette édition "
                    + "seulement : à niveau égal, une contrainte de poids 10 pèse deux fois une contrainte de poids 5. "
                    + "L'écran propose trois positions (faible 1, normale 5 — le défaut d'une règle moyenne ou "
                    + "souple —, forte 25), tout entier de 1 à 500 est accepté. "
                    + "Sans poids, l'édition revient au poids configuré par défaut. Ne touche pas au niveau "
                    + "(obligatoire ou préférence) : c'est modifier_contrainte (niveau).",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = false,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    @WarnsWhileSolving
    PoidsResult updateContrainteWeight(
            @ToolArg(description = "Nom technique de la contrainte (voir lister_contraintes)") String nom,
            @ToolArg(description = "Poids strictement positif ; omis, rétablit le poids par défaut", required = false)
                    Integer poids,
            @ToolArg(description = EditionArg.DESCRIPTION) @EditionArg String edition) {
        String rule = setWeight(nom, poids);
        return new PoidsResult(
                rule, planningService.effectiveConstraintWeights().getOrDefault(rule, 1), poids == null);
    }

    private String setWeight(String nom, Integer poids) {
        if (ConstraintCatalog.FORME_DURE_JOURS_CONSECUTIFS.equals(nom)) {
            throw new BusinessError.Invalid(ConstraintCatalog.POIDS_FORME_DURE_REFUSE);
        }
        String rule = requireConnue(nom);
        if (poids != null && poids <= 0) {
            throw new BusinessError.Invalid("poids : attendu un entier strictement positif, reçu " + poids);
        }
        referenceDataService.setConstraintWeight(rule, poids);
        return rule;
    }

    private String setLevel(String nom, String niveau) {
        String rule = requireConnue(nom);
        ConstraintCatalog.NiveauRegle choisi;
        try {
            choisi = ConstraintCatalog.NiveauRegle.valueOf(niveau == null ? "" : niveau.trim());
        } catch (IllegalArgumentException e) {
            throw new BusinessError.Invalid(
                    "niveau : attendu OBLIGATOIRE, PREFERENCE_FORTE, PREFERENCE_NORMALE ou PREFERENCE_LEGERE, reçu "
                            + niveau);
        }
        referenceDataService.setNiveau(rule, choisi);
        return rule;
    }

    private ToggleResult setActive(String nom, boolean actif) {
        String rule = requireConnue(nom);
        // The 3.x hard run form is a level, not a rule: the service reads it under its own name.
        referenceDataService.setContrainteActive(
                ConstraintCatalog.FORME_DURE_JOURS_CONSECUTIFS.equals(nom) ? nom : rule, actif);
        return new ToggleResult(rule, actif);
    }

    /**
     * 404 rather than a silently stored row on a name the catalogue does not
     * hold. A rule merged into another in 4.0 is read as its successor, whose
     * name is returned and answered.
     */
    private String requireConnue(String nom) {
        String current = ConstraintCatalog.currentName(nom);
        if (!ConstraintCatalog.PAR_NOM.containsKey(current)) {
            throw new BusinessError.NotFound("Contrainte inconnue : " + nom);
        }
        // The journal's line says which rule moved (the REST route's path parameter does it there).
        currentAction.entiteId(current);
        return current;
    }

    public record ToggleResult(
            String nom,
            boolean actif,
            @JsonInclude(JsonInclude.Include.NON_EMPTY) List<String> avertissements)
            implements WarningCarrier<ToggleResult> {

        ToggleResult(String nom, boolean actif) {
            this(nom, actif, List.of());
        }

        @Override
        public ToggleResult withWarning(String code) {
            return new ToggleResult(nom, actif, WarningCodes.with(avertissements, code));
        }
    }

    /** @param parDefaut true when the edition carries no override any more */
    public record PoidsResult(
            String nom,
            int poids,
            boolean parDefaut,
            @JsonInclude(JsonInclude.Include.NON_EMPTY) List<String> avertissements)
            implements WarningCarrier<PoidsResult> {

        PoidsResult(String nom, int poids, boolean parDefaut) {
            this(nom, poids, parDefaut, List.of());
        }

        @Override
        public PoidsResult withWarning(String code) {
            return new PoidsResult(nom, poids, parDefaut, WarningCodes.with(avertissements, code));
        }
    }

    static ContrainteView toView(
            ConstraintDefinition definition,
            ConstraintDiagnostic diagnostic,
            Set<String> desactivees,
            Map<String, Integer> poids) {
        return toView(definition, diagnostic, desactivees, poids, Map.of());
    }

    static ContrainteView toView(
            ConstraintDefinition definition,
            ConstraintDiagnostic diagnostic,
            Set<String> desactivees,
            Map<String, Integer> poids,
            Map<String, Boolean> niveaux) {
        boolean obligatoire =
                definition.niveauEffectif(niveaux.get(definition.name())) == ConstraintCatalog.Niveau.HARD;
        return new ContrainteView(
                definition.name(),
                definition.niveau().name(),
                definition.categorie(),
                definition.description(),
                !desactivees.contains(definition.name()),
                poids.getOrDefault(definition.name(), 1),
                diagnostic == null ? null : diagnostic.score(),
                diagnostic == null ? null : diagnostic.matchCount(),
                diagnostic == null || diagnostic.plancher() == null
                        ? null
                        : diagnostic.plancher().ratio(),
                diagnostic == null || diagnostic.plancher() == null
                        ? null
                        : diagnostic.plancher().libelle(),
                definition.niveauReglable(),
                !definition.niveauReglable()
                        ? null
                        : obligatoire
                                ? ConstraintCatalog.NiveauRegle.OBLIGATOIRE.name()
                                : ConstraintCatalog.NiveauRegle.ofPoids(poids.getOrDefault(definition.name(), 1))
                                        .name());
    }

    /**
     * @param poids          what one match of this constraint is worth on the next solve
     * @param ratioPlancher  share of what the rule evaluated that it matched, only
     *                       when that share reads as a floor (95 % and above, issue
     *                       #495): those points are a constant no solve will move
     * @param motifPlancher  the sentence naming the missing referential data behind
     *                       that floor, or saying none was identified; {@code null}
     *                       with {@code ratioPlancher}
     * @param niveauReglable that the edition chooses this rule's level (ADR 0106);
     *                       {@code niveau} stays the catalogue's own
     * @param niveauChoisi   the level it has on this edition — OBLIGATOIRE or a
     *                       preference position — or {@code null} when not choosable
     */
    public record ContrainteView(
            String nom,
            String niveau,
            String categorie,
            String description,
            boolean actif,
            int poids,
            String score,
            Integer nombreCorrespondances,
            Double ratioPlancher,
            String motifPlancher,
            boolean niveauReglable,
            String niveauChoisi) {}
}
