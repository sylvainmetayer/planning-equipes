package dev.sylvain.planning.mcp;

import com.fasterxml.jackson.annotation.JsonInclude;
import dev.sylvain.planning.service.BusinessError;
import dev.sylvain.planning.service.analyse.PlanningDiagnosticService.ConstraintDiagnostic;
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

    @Inject
    ContrainteMcpTools(
            ConstraintAnalysisStore analysisStore,
            ReferenceDataService referenceDataService,
            PlanningService planningService) {
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
    List<ContrainteView> listContraintes(
            @ToolArg(description = EditionArg.DESCRIPTION, required = false) @EditionArg String edition) {
        StoredAnalysis analysis = analysisStore.latest();
        Map<String, ConstraintDiagnostic> byName = analysis == null
                ? Map.of()
                : analysis.diagnostic().contraintes().stream()
                        .collect(Collectors.toMap(
                                ConstraintDiagnostic::name, Function.identity(), (first, second) -> first));
        Set<String> desactivees = referenceDataService.getContraintesDesactivees();
        Map<String, Integer> poids = planningService.effectiveConstraintWeights();

        return ConstraintCatalog.definitions().stream()
                .map(definition -> toView(definition, byName.get(definition.name()), desactivees, poids))
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
            @ToolArg(description = EditionArg.DESCRIPTION, required = false) @EditionArg String edition) {
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
            @ToolArg(description = EditionArg.DESCRIPTION, required = false) @EditionArg String edition) {
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
                    + "seulement : à niveau égal, une contrainte de poids 3 pèse trois fois une contrainte de poids 1. "
                    + "Sans poids, l'édition revient au poids configuré par défaut. Ne touche pas au niveau "
                    + "HARD/MEDIUM/SOFT, qui n'est pas réglable.",
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
            @ToolArg(description = EditionArg.DESCRIPTION, required = false) @EditionArg String edition) {
        requireConnue(nom);
        if (poids != null && poids <= 0) {
            throw new BusinessError.Invalid("poids : attendu un entier strictement positif, reçu " + poids);
        }
        referenceDataService.setConstraintWeight(nom, poids);
        return new PoidsResult(nom, planningService.effectiveConstraintWeights().getOrDefault(nom, 1), poids == null);
    }

    private ToggleResult setActive(String nom, boolean actif) {
        requireConnue(nom);
        referenceDataService.setContrainteActive(nom, actif);
        return new ToggleResult(nom, actif);
    }

    /** 404 rather than a silently stored row on a name the catalogue does not hold. */
    private void requireConnue(String nom) {
        boolean known = ConstraintCatalog.definitions().stream()
                .anyMatch(definition -> definition.name().equals(nom));
        if (!known) {
            throw new BusinessError.NotFound("Contrainte inconnue : " + nom);
        }
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
                        : diagnostic.plancher().libelle());
    }

    /**
     * @param poids          what one match of this constraint is worth on the next solve
     * @param ratioPlancher  share of what the rule evaluated that it matched, only
     *                       when that share reads as a floor (95 % and above, issue
     *                       #495): those points are a constant no solve will move
     * @param motifPlancher  the sentence naming the missing referential data behind
     *                       that floor, or saying none was identified; {@code null}
     *                       with {@code ratioPlancher}
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
            String motifPlancher) {}
}
