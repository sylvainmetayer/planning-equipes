package dev.sylvain.planning.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.VerrouillagePlanning;
import dev.sylvain.planning.service.PlanningService.DeplacementSimulation;
import dev.sylvain.planning.service.PlanningService.HardViolation;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * A seat moved by hand on a day view (issue #308): the drag-and-drop of the
 * calendar and of the rail, and the same gesture over the API and MCP.
 *
 * <p>Simulated first, then written — and simulated <b>here</b>, on the
 * persisted plan, not trusted from the browser: a drop that would worsen the
 * plan's hard score is refused whatever the client checked, in the same terms
 * the repair assistant uses (it never offers a candidate that breaks a hard
 * rule). Locked seats are refused like every other manual write, and the
 * write itself is one transaction over the two rows. Afterwards the stored
 * constraint analysis is re-derived from the plan, so the Contraintes screen
 * describes the plan actually in place — the same care a restore takes.</p>
 */
@ApplicationScoped
public class DeplacementService {

    @Inject
    PlanningService planningService;

    @Inject
    PlanningPersistenceService persistence;

    @Inject
    ReferenceDataService referenceDataService;

    @Inject
    ConstraintAnalysisStore analysisStore;

    @Inject
    SolverJobService solverJobs;

    /** Scores the gesture on {@code planning}, or on the persisted plan when none is given. Writes nothing. */
    public DeplacementSimulation simulate(PlanningEvenement planning, String posteSourceId, String posteCibleId,
            String animateurCibleId) {
        PlanningEvenement solved = planning == null ? persistedPlan() : planning;
        return planningService.simulateDeplacement(solved, posteSourceId, posteCibleId, animateurCibleId);
    }

    /**
     * Applies the gesture to the persisted plan and answers what it did.
     *
     * @throws BusinessError.Invalid when the move would worsen the hard score,
     *                               naming the rules it would break, or when
     *                               one of the seats is locked
     */
    public DeplacementSimulation apply(String posteSourceId, String posteCibleId, String animateurCibleId) {
        // A solve landing later would overwrite the move without a word: same
        // refusal as every referential write (issue #328).
        solverJobs.refuseIfSolving();
        PlanningEvenement persiste = persistedPlan();
        DeplacementSimulation simulation = planningService.simulateDeplacement(persiste, posteSourceId,
                posteCibleId, animateurCibleId);
        if (simulation.casseContrainteDure()) {
            throw new BusinessError.Invalid("Déplacement refusé : il casserait " + describe(simulation.nouvellesViolationsDures()));
        }
        refuseIfLocked(persiste, simulation);
        Map<String, String> ecritures = new LinkedHashMap<>();
        ecritures.put(simulation.posteSourceId(), simulation.animateurCibleId());
        if (simulation.posteCibleId() != null) {
            ecritures.put(simulation.posteCibleId(), simulation.animateurSourceId());
        }
        persistence.reaffecterPostes(ecritures);
        // Best-effort like the restore of a snapshot: a stale analysis is an
        // empty screen, never a reason to undo a move already written.
        try {
            analysisStore.refreshFromPersistedPlan();
        } catch (RuntimeException e) {
            // Deliberately swallowed, see above.
        }
        return simulation;
    }

    private PlanningEvenement persistedPlan() {
        PlanningEvenement planning = persistence.loadPersistedPlanning();
        if (planning == null || planning.getPostes() == null || planning.getPostes().isEmpty()) {
            throw new BusinessError.Conflict("Aucun planning enregistré : lancez d'abord une résolution.");
        }
        return planning;
    }

    private void refuseIfLocked(PlanningEvenement persiste, DeplacementSimulation simulation) {
        List<VerrouillagePlanning> verrouillages = referenceDataService.listVerrouillages();
        if (verrouillages.isEmpty()) {
            return;
        }
        for (PosteAffectation poste : persiste.getPostes()) {
            boolean concerne = poste.getId().equals(simulation.posteSourceId())
                    || poste.getId().equals(simulation.posteCibleId());
            if (concerne && verrouillages.stream().anyMatch(verrouillage -> verrouillage.couvre(poste))) {
                throw new BusinessError.Invalid(
                        "Ce poste est verrouillé : déverrouillez-le avant de déplacer son affectation.");
            }
        }
    }

    private static String describe(List<HardViolation> violations) {
        if (violations.isEmpty()) {
            return "une règle dure du planning.";
        }
        return violations.stream()
                .map(violation -> violation.description() + " (" + violation.matchesSupplementaires() + ")")
                .collect(Collectors.joining(" ; ")) + ".";
    }
}
