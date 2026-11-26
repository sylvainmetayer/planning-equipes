package dev.sylvain.planning.mcp;

import com.fasterxml.jackson.annotation.JsonInclude;
import dev.sylvain.planning.service.BusinessError;
import dev.sylvain.planning.service.analyse.PlanningDiagnosticService.ConstraintDiagnostic;
import dev.sylvain.planning.service.analyse.WeightHistoryService;
import dev.sylvain.planning.service.referentiel.ReferenceDataService;
import dev.sylvain.planning.service.referentiel.WeightChangeOrigin;
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

    private final WeightHistoryService weightHistory;

    @Inject
    ContrainteMcpTools(
            ConstraintAnalysisStore analysisStore,
            ReferenceDataService referenceDataService,
            PlanningService planningService,
            WeightHistoryService weightHistory) {
        this.weightHistory = weightHistory;
        this.analysisStore = analysisStore;
        this.referenceDataService = referenceDataService;
        this.planningService = planningService;
    }

    @Tool(
            name = "consulter_historique_ponderation",
            description = "Historique des réglages de pondération de l'édition : chaque changement de poids ou "
                    + "d'activation d'une règle, valeurs effectives avant et après (défaut compris), origine "
                    + "(SCREEN, ASSISTANT, SCENARIO, DUPLICATION) et date ; puis les résolutions de l'édition, "
                    + "avec leur score, le dosage sous lequel chacune a tourné (null = inconnu) et, pour une "
                    + "règle donnée, son nombre d'écarts. Juxtaposition, pas causalité : le référentiel a pu "
                    + "changer entre deux résolutions.",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = true,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    WeightHistoryService.ConstraintHistory weightHistory(
            @ToolArg(description = "Nom d'une contrainte ; absent = toutes les règles", required = false)
                    String contrainte,
            @ToolArg(description = EditionArg.DESCRIPTION) @EditionArg String edition) {
        return contrainte == null || contrainte.isBlank()
                ? weightHistory.all()
                : weightHistory.forConstraint(contrainte);
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
                .map(definition -> toView(definition, byName.get(definition.name()), desactivees, poids, niveaux))
                .toList();
    }

    @Tool(
            name = "activer_contrainte",
            description = "Active une contrainte pour le prochain solve (annule une désactivation précédente).",
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

    @Tool(
            name = "desactiver_contrainte",
            description =
                    "Désactive une contrainte pour le prochain solve. Le solveur l'ignorera jusqu'à réactivation.",
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
    @Tool(
            name = "modifier_poids_contrainte",
            description = "Change le poids d'une contrainte pour la prochaine résolution, dans cette édition "
                    + "seulement : à niveau égal, une contrainte de poids 10 pèse deux fois une contrainte de poids 5. "
                    + "L'écran propose trois positions (faible 1, normale 5 — le défaut d'une règle moyenne ou "
                    + "souple —, forte 25), tout entier de 1 à 500 est accepté. "
                    + "Sans poids, l'édition revient au poids configuré par défaut. Ne touche pas au niveau "
                    + "(obligatoire ou préférence) : c'est modifier_niveau_contrainte.",
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
        if (ConstraintCatalog.FORME_DURE_JOURS_CONSECUTIFS.equals(nom)) {
            throw new BusinessError.Invalid(ConstraintCatalog.POIDS_FORME_DURE_REFUSE);
        }
        String rule = requireConnue(nom);
        if (poids != null && poids <= 0) {
            throw new BusinessError.Invalid("poids : attendu un entier strictement positif, reçu " + poids);
        }
        referenceDataService.setConstraintWeight(rule, poids, WeightChangeOrigin.ASSISTANT);
        return new PoidsResult(
                rule, planningService.effectiveConstraintWeights().getOrDefault(rule, 1), poids == null);
    }

    @Tool(
            name = "modifier_niveau_contrainte",
            description = "Choisit le niveau d'une règle d'organisation dans cette édition : OBLIGATOIRE (le plan "
                    + "qui la viole n'est pas livrable, comme une règle légale), ou une préférence "
                    + "(PREFERENCE_FORTE, PREFERENCE_NORMALE, PREFERENCE_LEGERE : la règle pèse sur le score, avec "
                    + "le poids de cette position, 25, 5 ou 1). Le niveau se règle sur les règles dont "
                    + "lister_contraintes donne niveauReglable : jamais sur une règle légale, d'affectation, "
                    + "ad hoc ou de verrou. Une règle rendue obligatoire sur une édition qui ne peut pas la "
                    + "tenir donne un plan infaisable : lancer analyser_faisabilite avant le calcul.",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = false,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    @WarnsWhileSolving
    NiveauResult updateContrainteNiveau(
            @ToolArg(description = "Nom technique de la contrainte (voir lister_contraintes)") String nom,
            @ToolArg(description = "OBLIGATOIRE, PREFERENCE_FORTE, PREFERENCE_NORMALE ou PREFERENCE_LEGERE")
                    String niveau,
            @ToolArg(description = EditionArg.DESCRIPTION) @EditionArg String edition) {
        String rule = requireConnue(nom);
        ConstraintCatalog.NiveauRegle choisi;
        try {
            choisi = ConstraintCatalog.NiveauRegle.valueOf(niveau == null ? "" : niveau.trim());
        } catch (IllegalArgumentException e) {
            throw new BusinessError.Invalid(
                    "niveau : attendu OBLIGATOIRE, PREFERENCE_FORTE, PREFERENCE_NORMALE ou PREFERENCE_LEGERE, reçu "
                            + niveau);
        }
        referenceDataService.setNiveau(rule, choisi, WeightChangeOrigin.ASSISTANT);
        return new NiveauResult(rule, choisi.name());
    }

    private ToggleResult setActive(String nom, boolean actif) {
        String rule = requireConnue(nom);
        // The 3.x hard run form is a level, not a rule: the service reads it under its own name.
        referenceDataService.setContrainteActive(
                ConstraintCatalog.FORME_DURE_JOURS_CONSECUTIFS.equals(nom) ? nom : rule,
                actif,
                WeightChangeOrigin.ASSISTANT);
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

    public record NiveauResult(
            String nom,
            String niveau,
            @JsonInclude(JsonInclude.Include.NON_EMPTY) List<String> avertissements)
            implements WarningCarrier<NiveauResult> {

        NiveauResult(String nom, String niveau) {
            this(nom, niveau, List.of());
        }

        @Override
        public NiveauResult withWarning(String code) {
            return new NiveauResult(nom, niveau, WarningCodes.with(avertissements, code));
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
