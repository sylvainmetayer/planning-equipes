package dev.sylvain.planning.service.solve;

import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.VerrouillagePlanning;
import dev.sylvain.planning.service.BusinessError;
import dev.sylvain.planning.service.referentiel.ReferenceDataService;
import dev.sylvain.planning.service.solve.PlanningWhatIf.DeplacementSimulation;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    private final PlanningService planningService;

    private final PlanningPersistenceService persistence;

    private final ReferenceDataService referenceDataService;

    private final ConstraintAnalysisStore analysisStore;

    private final SolverJobService solverJobs;

    @Inject
    public DeplacementService(
            PlanningService planningService,
            PlanningPersistenceService persistence,
            ReferenceDataService referenceDataService,
            ConstraintAnalysisStore analysisStore,
            SolverJobService solverJobs) {
        this.planningService = planningService;
        this.persistence = persistence;
        this.referenceDataService = referenceDataService;
        this.analysisStore = analysisStore;
        this.solverJobs = solverJobs;
    }

    /**
     * Scores the gesture on the persisted plan, prepared as a solve prepares
     * it. Writes nothing.
     *
     * <p>Deliberately not on a planning the caller sends: the verdict has to
     * be read on the rules the edition actually runs under — the constraints
     * the operator switched off, the weights, the quality parameters, the
     * published plan — and those are the server's knowledge, not the client's.
     * Scoring a posted plan enforced rules the operator had disabled, and
     * showed a score the Contraintes screen contradicted.</p>
     */
    public DeplacementSimulation simulate(String posteSourceId, String posteCibleId, String animateurCibleId) {
        return planningService.simulateDeplacement(persistedPlan(), posteSourceId, posteCibleId, animateurCibleId);
    }

    /**
     * Applies the gesture to the persisted plan and answers what it did.
     *
     * @throws BusinessError.Invalid when the move would worsen the hard score,
     *                               naming the rules it would break, or when
     *                               one of the seats is locked
     */
    public DeplacementSimulation apply(
            String posteSourceId, String posteCibleId, String animateurCibleId, String occupantAttendu) {
        // A solve landing later would overwrite the move without a word: same
        // refusal as every referential write (issue #328).
        solverJobs.refuseIfSolving();
        PlanningEvenement persiste = persistedPlan();
        refuseIfMoved(persiste, posteSourceId, occupantAttendu);
        DeplacementSimulation simulation =
                planningService.simulateDeplacement(persiste, posteSourceId, posteCibleId, animateurCibleId);
        if (simulation.casseContrainteDure()) {
            throw new BusinessError.Invalid("Déplacement refusé : il casserait "
                    + PlanningWhatIf.describeHardViolations(simulation.nouvellesViolationsDures()));
        }
        refuseIfLocked(persiste, simulation);
        Map<String, String> ecritures = new LinkedHashMap<>();
        ecritures.put(simulation.posteSourceId(), simulation.animateurCibleId());
        if (simulation.posteCibleId() != null) {
            ecritures.put(simulation.posteCibleId(), simulation.animateurSourceId());
        }
        persistence.reaffecterPostes(ecritures);
        refreshAnalysis();
        return simulation;
    }

    /**
     * « Placer » from the Siège panel: somebody off duty seated on a seat
     * nobody holds, a timeslot still ahead. The write and its checks are the
     * ones {@code affecter_poste} makes — see
     * {@link PlanningWhatIf#placeOnFreeSeat} — on the plan prepared as this
     * service prepares it for a move, plus the two a move makes and a direct
     * write does not: the seat still free, and no lock on the person
     * receiving it. The solve guard is the write's own, first of its checks.
     *
     * @throws BusinessError.Conflict when the seat is no longer free
     * @throws BusinessError.Invalid  when the seating would break a hard rule,
     *                                naming it, or a lock covers the seat or
     *                                the person
     */
    public DeplacementSimulation place(String posteId, String animateurId) {
        DeplacementSimulation placement = planningService.placeOnFreeSeat(persistedPlan(), posteId, animateurId);
        refreshAnalysis();
        return placement;
    }

    /** Best-effort like the restore of a snapshot: a stale analysis is an empty screen, never a reason to undo a write. */
    private void refreshAnalysis() {
        try {
            analysisStore.refreshFromPersistedPlan();
        } catch (RuntimeException _) {
            // Deliberately swallowed, see above.
        }
    }

    private PlanningEvenement persistedPlan() {
        PlanningEvenement planning = persistence.loadPersistedPlanning();
        if (planning == null
                || planning.getPostes() == null
                || planning.getPostes().isEmpty()) {
            throw new BusinessError.Conflict("Aucun planning enregistré : lancez d'abord une résolution.");
        }
        // The rules of this edition, not the defaults: without this the verdict
        // enforces constraints the operator switched off, and reports a score
        // the Contraintes screen contradicts.
        planningService.prepareForAnalysis(planning);
        return planning;
    }

    /**
     * Refuses the gesture when the seat no longer holds the person the caller
     * saw. The day views load their plan once and never re-read it on someone
     * else's write, so « the name I dragged » and « whoever sits there now »
     * routinely differ — an accepted échange, a jour-J repair, another tab, a
     * solve that landed. Without this the server dutifully moves the wrong
     * person and answers 200.
     *
     * @param occupantAttendu the animateur the caller believes holds the seat;
     *                        {@code null} means no precondition, like a write
     *                        without {@code modifieLe} (issue #362)
     */
    private void refuseIfMoved(PlanningEvenement persiste, String posteSourceId, String occupantAttendu) {
        if (occupantAttendu == null) {
            return;
        }
        String occupant = persiste.getPostes().stream()
                .filter(poste -> poste.getId().equals(posteSourceId))
                .findFirst()
                .map(poste -> poste.getAnimateur() == null
                        ? null
                        : poste.getAnimateur().getId())
                .orElse(null);
        if (!occupantAttendu.equals(occupant)) {
            throw new BusinessError.Conflict("Ce siège n'est plus tenu par la personne affichée : le planning a "
                    + "changé depuis l'ouverture de cette vue. Rechargez-la avant de déplacer.");
        }
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
            if (poste.getId().equals(simulation.posteSourceId())) {
                PlanningWhatIf.refuseIfReceiverLocked(verrouillages, simulation.animateurCibleId(), poste);
            }
        }
    }
}
