package dev.sylvain.planning.service.scenario;

import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.PrereglageConsigne;
import dev.sylvain.planning.scenario.dto.EditionCibleDto;
import dev.sylvain.planning.service.EditionContext;
import dev.sylvain.planning.service.consigne.ConsigneRepository;
import dev.sylvain.planning.service.consigne.ConsigneService;
import dev.sylvain.planning.service.edition.EditionService;
import dev.sylvain.planning.service.referentiel.ReferenceDataService;
import dev.sylvain.planning.service.solve.PlanningService;
import dev.sylvain.planning.solver.ConstraintCatalog;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

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
 * the constraints. Then the consignes (ADR 0043), last of all: their openings
 * name stands and their added créneaux name créneaux, both of which must have
 * landed — with their final ids — before a consigne can point at them.</p>
 */
@ApplicationScoped
public class ScenarioImportService {

    private final ReferenceDataService referenceDataService;

    private final EditionService editionService;

    private final EditionContext editionContext;

    private final PlanningService planningService;

    private final ConsigneRepository consigneRepository;

    @Inject
    public ScenarioImportService(
            ReferenceDataService referenceDataService,
            EditionService editionService,
            EditionContext editionContext,
            PlanningService planningService,
            ConsigneRepository consigneRepository) {
        this.referenceDataService = referenceDataService;
        this.editionService = editionService;
        this.editionContext = editionContext;
        this.planningService = planningService;
        this.consigneRepository = consigneRepository;
    }

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
            sections.parametresQualite().ifPresent(referenceDataService::updateParametresQualite);
            sections.parametresSolveur().ifPresent(referenceDataService::updateParametresSolveur);
            referenceDataService.importFromPlanning(importe.planning());
            applyTypologies(sections);
            applyJourneesTypes(sections);
            applyContraintes(sections);
            applyConsignes(sections);
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
    private ScenarioImportOutcome importIntoTarget(Optional<EditionCibleDto> cibleDto, Runnable importAction) {
        try {
            if (cibleDto.isEmpty()) {
                importAction.run();
                return new ScenarioImportOutcome(null, null, null);
            }
            EditionService.ImportTarget target = editionService.resolveForImport(
                    cibleDto.get().id(), cibleDto.get().nom());
            editionContext.executeIn(target.edition().getId(), () -> {
                importAction.run();
                return null;
            });
            return new ScenarioImportOutcome(
                    target.edition().getId(), target.edition().getNom(), target.creee());
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Import de scénario échoué", e);
        }
    }

    /**
     * The file's own day templates, when it names them, replace the ones the
     * planning import recognised from its créneaux; absent, the recognised ones
     * stand — the screen and the file then describe the same edition.
     */
    private void applyJourneesTypes(ScenarioYamlReader.ScenarioSections sections) {
        sections.journeesTypes()
                .ifPresent(section ->
                        referenceDataService.importJourneesTypes(section.journeesTypes(), section.calendrier()));
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
     * verified against, so a rule it names in neither list goes back to the
     * catalogue's own state, at its configured weight. Merging would leave the
     * importing edition's own leftovers in place, and the "same" scenario would
     * keep solving a different problem depending on where it landed — the very
     * hole this section closes.</p>
     *
     * <p>"Back to the catalogue's own state" rather than "back to active":
     * since a rule can ship switched off (issue #595), re-enabling everything a
     * file does not mention would turn on, on every import, precisely the rule
     * the deployment decided not to enforce.</p>
     */
    private void applyContraintes(ScenarioYamlReader.ScenarioSections sections) {
        sections.contraintes().ifPresent(contraintes -> {
            for (ConstraintCatalog.ConstraintDefinition definition : ConstraintCatalog.definitions()) {
                boolean actif;
                if (contraintes.desactivees().contains(definition.name())) {
                    actif = false;
                } else if (contraintes.activees().contains(definition.name())) {
                    actif = true;
                } else {
                    actif = definition.activeByDefault();
                }
                referenceDataService.setContrainteActive(definition.name(), actif);
                referenceDataService.setConstraintWeight(
                        definition.name(), contraintes.poids().get(definition.name()));
            }
        });
    }

    /**
     * Applies the two consigne sections (ADR 0043), each replacing the
     * edition's own when present and leaving it alone when absent — the rule
     * of every other referential the file carries.
     *
     * <p>Written through the repository, never through
     * {@code ConsigneService.poser}: that gesture refuses a date already
     * worked, and adds to the grid the créneaux its openings need. A file may
     * legitimately carry a consigne on a past date — the statistics of a day
     * are what was actually done — and the créneaux it added are in the
     * {@code creneaux:} section already, so they have just been written with
     * the rest of the grid. What is left to do is to tie them back: the file
     * names them by day and hours, the grid now knows them by id.</p>
     *
     * <p>One exception to « already written »: the planning import keeps the
     * créneaux a stand opens on, and a créneau a consigne added belongs to
     * nobody's usual hours (ADR 0043) — a stand is open on it only through
     * the consigne. Such a créneau reaches the grid without a seat and is
     * dropped on the way, so it is created here, the way the consigne created
     * it the first time.</p>
     */
    private void applyConsignes(ScenarioYamlReader.ScenarioSections sections) {
        sections.prereglagesConsigne().ifPresent(this::replacePrereglages);
        sections.consignes().ifPresent(this::replaceConsignes);
    }

    private void replacePrereglages(List<PrereglageConsigne> prereglages) {
        for (PrereglageConsigne existant : consigneRepository.listPrereglages()) {
            consigneRepository.deletePrereglage(existant.id());
        }
        for (PrereglageConsigne prereglage : prereglages) {
            consigneRepository.savePrereglage(prereglage.id() != null ? prereglage : withGeneratedId(prereglage));
        }
    }

    /**
     * Replaces the edition's consignes by the file's, creating the timeslots
     * they added that the grid does not hold yet — each one once, however
     * many consignes name it.
     */
    private void replaceConsignes(List<ScenarioYamlReader.ConsigneScenario> consignes) {
        for (var existante : consigneRepository.list()) {
            consigneRepository.delete(existante.date());
        }
        Map<ConsigneService.VacationRef, Long> idsParCle = new HashMap<>();
        for (Creneau creneau : referenceDataService.listCreneaux()) {
            idsParCle.put(
                    new ConsigneService.VacationRef(creneau.getDate(), creneau.getHeureDebut(), creneau.getHeureFin()),
                    creneau.getId());
        }
        for (ScenarioYamlReader.ConsigneScenario entree : consignes) {
            List<Long> ids = new ArrayList<>();
            for (ConsigneService.VacationRef cle : entree.creneauxAjoutes()) {
                ids.add(idsParCle.computeIfAbsent(cle, this::createCreneau));
            }
            consigneRepository.save(entree.consigne().withCreneauxAjoutes(ids));
        }
    }

    private Long createCreneau(ConsigneService.VacationRef cle) {
        return referenceDataService
                .writeCreneau(new Creneau(null, 0, cle.date(), cle.heureDebut(), cle.heureFin()))
                .creneau()
                .getId();
    }

    /** A preset written by hand carries no id; the screen would have drawn one, so does the import. */
    private static PrereglageConsigne withGeneratedId(PrereglageConsigne prereglage) {
        return new PrereglageConsigne(
                UUID.randomUUID().toString(),
                prereglage.nom(),
                prereglage.fermetureDebut(),
                prereglage.fermetureFin(),
                prereglage.motif(),
                prereglage.fenetres(),
                prereglage.creeLe(),
                prereglage.modifieLe(),
                prereglage.repas());
    }

    /**
     * Where a scenario landed.
     *
     * @param editionId    the edition the scenario named, or {@code null} when it named none and the data
     *                     landed in the caller's current edition
     * @param editionNom   the name of that edition, {@code null} for the same reason
     * @param editionCreee the edition did not exist and this import created it; {@code null} when the
     *                     scenario named no edition
     */
    public record ScenarioImportOutcome(String editionId, String editionNom, Boolean editionCreee) {}
}
