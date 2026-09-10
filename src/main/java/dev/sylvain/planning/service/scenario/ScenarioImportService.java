package dev.sylvain.planning.service.scenario;

import dev.sylvain.planning.scenario.dto.EditionCibleDto;
import dev.sylvain.planning.solver.ConstraintCatalog;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Optional;
import java.util.concurrent.Callable;
import dev.sylvain.planning.service.edition.EditionService;
import dev.sylvain.planning.service.EditionContext;
import dev.sylvain.planning.service.PlanningService;
import dev.sylvain.planning.service.ReferenceDataService;

/**
 * Importing a scenario into the referential: the order the sections are applied
 * in, and the edition they land in.
 *
 * <p>This used to live in {@code ReferenceDataResource}, which made the order
 * below a property of an HTTP endpoint. {@code ScenarioMcpTools} needed the
 * same order and could only get it by injecting the resource and reading
 * {@code Response.getStatus()} — the repository's only {@code mcp → api}
 * import, and a business rule reachable only through a transport type.</p>
 *
 * <p><b>The order is the rule.</b> Parameters first, because the découpage the
 * scenario may trigger has to run against the ones it pinned rather than
 * against whatever the database already held. Then the planning. Then
 * typologies, <em>after</em> the planning and never before: importing a
 * planning auto-derives an id-as-its-own-label entry for every typologie a
 * stand or an animateur references, and overwrites the label when it does — an
 * explicit {@code {id, label}} pair applied first would simply be erased. Then
 * the constraints.</p>
 */
@ApplicationScoped
public class ScenarioImportService {

    @Inject
    ReferenceDataService referenceDataService;

    @Inject
    EditionService editionService;

    @Inject
    EditionContext editionContext;

    @Inject
    PlanningService planningService;

    /**
     * Imports one of the scenarios bundled under
     * {@code src/main/resources/scenarios}, by name.
     */
    public ScenarioImportOutcome importBundled(String name) {
        return importScenario(planningService.loadScenario(name));
    }

    /**
     * Imports a scenario the caller supplies as YAML text — a file uploaded
     * from somebody's machine, or the body of an MCP call. Throws
     * {@link BusinessError.Invalid} on a malformed or structurally invalid
     * document rather than importing part of it.
     */
    public ScenarioImportOutcome importYaml(String yamlContent) {
        return importScenario(planningService.buildFromScenarioText(yamlContent));
    }

    /**
     * The import itself, identical whether the scenario came bundled or was
     * uploaded — the two callers differ only in where the bytes were read from.
     * It used to be written out twice, and the two copies had drifted: the
     * bundled path re-read the file once per optional section.
     */
    private ScenarioImportOutcome importScenario(ScenarioYamlReader.ScenarioImporte importe) {
        ScenarioYamlReader.ScenarioSections sections = importe.sections();
        return importIntoTarget(sections.edition(), () -> {
            sections.parametresLegaux().ifPresent(referenceDataService::updateParametresLegaux);
            sections.parametresDecoupage().ifPresent(referenceDataService::updateParametresDecoupage);
            sections.parametresSolveur().ifPresent(referenceDataService::updateParametresSolveur);
            if (sections.decoupageAuto()) {
                referenceDataService.applyAutomaticDecoupage(importe.planning());
                applyTypologies(sections);
                applyContraintes(sections);
                return true;
            }
            referenceDataService.importFromPlanning(importe.planning());
            applyTypologies(sections);
            applyContraintes(sections);
            return false;
        });
    }

    /**
     * Runs {@code importAction} against the edition the scenario's optional
     * {@code edition:} section designates — created empty when missing, reused
     * otherwise — or plainly against the caller's current edition when the file
     * names none. The outcome always reports where the data landed (and whether
     * the edition was just created), because the operator's browser may be
     * sitting on a different edition than the one that was written.
     */
    private ScenarioImportOutcome importIntoTarget(Optional<EditionCibleDto> cibleDto,
            Callable<Boolean> importAction) {
        try {
            if (cibleDto.isEmpty()) {
                return new ScenarioImportOutcome(importAction.call(), null, null, null);
            }
            EditionService.ImportTarget target =
                    editionService.resolveForImport(cibleDto.get().id(), cibleDto.get().nom());
            boolean decoupageAuto = editionContext.executeIn(target.edition().getId(), importAction);
            return new ScenarioImportOutcome(decoupageAuto,
                    target.edition().getId(), target.edition().getNom(), target.creee());
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Import de scénario échoué", e);
        }
    }

    /**
     * Applies the scenario's optional {@code typologies:} section, if any,
     * <b>after</b> the planning itself has been imported: see this class's
     * javadoc for why the order is not negotiable.
     */
    private void applyTypologies(ScenarioYamlReader.ScenarioSections sections) {
        sections.typologies().forEach(referenceDataService::importTypologie);
    }

    /**
     * Applies the scenario's {@code contraintes:} section to the target
     * edition: which rules are switched off, and what weight the others carry.
     *
     * <p>The section is applied <b>wholesale</b> over the whole catalogue, not
     * merged: a scenario that pins its tuning describes the problem it was
     * verified against, so a rule it does not name goes back to active, at its
     * configured weight. Merging would leave the importing edition's own
     * leftovers in place, and the "same" scenario would keep solving a
     * different problem depending on where it landed — the very hole this
     * section closes.</p>
     */
    private void applyContraintes(ScenarioYamlReader.ScenarioSections sections) {
        sections.contraintes().ifPresent(contraintes -> {
            for (ConstraintCatalog.ConstraintDefinition definition : ConstraintCatalog.definitions()) {
                referenceDataService.setContrainteActive(definition.name(),
                        !contraintes.desactivees().contains(definition.name()));
                referenceDataService.setConstraintWeight(definition.name(),
                        contraintes.poids().get(definition.name()));
            }
        });
    }

    /**
     * Where a scenario landed, and what it triggered on the way in.
     *
     * @param decoupageAuto the scenario carried a {@code decoupageAuto:} section, so its opening spans were
     *                      sliced into vacations at import time
     * @param editionId     the edition the scenario named, or {@code null} when it named none and the data
     *                      landed in the caller's current edition
     * @param editionNom    the name of that edition, {@code null} for the same reason
     * @param editionCreee  the edition did not exist and this import created it; {@code null} when the
     *                      scenario named no edition
     */
    public record ScenarioImportOutcome(boolean decoupageAuto, String editionId, String editionNom,
            Boolean editionCreee) {
    }
}
