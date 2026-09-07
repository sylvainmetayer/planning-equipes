package dev.sylvain.planning.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.VerrouillagePlanning;
import dev.sylvain.planning.service.PlanningWhatIf.DeplacementSimulation;
import dev.sylvain.planning.service.PlanningWhatIf.HardViolation;
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
    public DeplacementSimulation apply(String posteSourceId, String posteCibleId, String animateurCibleId,
            String occupantAttendu) {
        // A solve landing later would overwrite the move without a word: same
        // refusal as every referential write (issue #328).
        solverJobs.refuseIfSolving();
        PlanningEvenement persiste = persistedPlan();
        refuseIfMoved(persiste, posteSourceId, occupantAttendu);
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
                .map(poste -> poste.getAnimateur() == null ? null : poste.getAnimateur().getId())
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
                refuseIfReceiverLocked(verrouillages, simulation, poste);
            }
        }
    }

    /**
     * A lock on the <b>person receiving</b> the seat is read on nothing when
     * they hold no seat on that créneau — which is the rail gesture's main
     * case — so {@code couvre} cannot see it, and neither can the score: the
     * persisted plan carries no lock facts. Yet « the solver may not give them
     * an extra seat » is exactly what an ANIMATEUR lock says, and an
     * ANIMATEUR_CRENEAU lock is what an accepted échange posts to keep the
     * freed person free on that créneau.
     */
    private static void refuseIfReceiverLocked(List<VerrouillagePlanning> verrouillages,
            DeplacementSimulation simulation, PosteAffectation source) {
        String receveur = simulation.animateurCibleId();
        if (receveur == null) {
            return;
        }
        Long creneauId = source.getCreneau() == null ? null : source.getCreneau().getId();
        boolean verrouille = verrouillages.stream().anyMatch(verrouillage -> switch (verrouillage.getType()) {
            case ANIMATEUR -> receveur.equals(verrouillage.getAnimateurId());
            case ANIMATEUR_CRENEAU -> receveur.equals(verrouillage.getAnimateurId())
                    && creneauId != null && creneauId.equals(verrouillage.getCreneauId());
            default -> false;
        });
        if (verrouille) {
            throw new BusinessError.Invalid("L'emploi du temps de cette personne est verrouillé : déverrouillez-le "
                    + "avant de lui donner ce siège.");
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
