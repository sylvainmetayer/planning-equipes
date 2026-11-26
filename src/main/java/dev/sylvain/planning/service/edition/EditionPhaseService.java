package dev.sylvain.planning.service.edition;

import dev.sylvain.planning.domain.Edition;
import dev.sylvain.planning.domain.EditionPhase;
import dev.sylvain.planning.service.BusinessError;
import dev.sylvain.planning.service.EditionContext;
import dev.sylvain.planning.service.journal.CurrentAction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;

/**
 * The phase of the current edition (ADR 0115): read by the referential's
 * guard, declared by the organiser, moved to {@link EditionPhase#PUBLIEE} by a
 * publication, and — in that phase only — the structure unblocked for a
 * last-minute change and frozen again.
 *
 * <p>None of these writes is refused while a solve runs: like the freeze it
 * replaces, a phase refuses the writes to come and changes no data a running
 * solve reads.</p>
 */
@ApplicationScoped
public class EditionPhaseService {

    private final EditionRepository repository;

    private final EditionContext editionContext;

    /** Where the phase entered is left for the history line, which names it. */
    private final CurrentAction currentAction;

    @Inject
    public EditionPhaseService(
            EditionRepository repository, EditionContext editionContext, CurrentAction currentAction) {
        this.repository = repository;
        this.editionContext = editionContext;
        this.currentAction = currentAction;
    }

    /** The current edition, with its phase. */
    public Edition current() {
        String id = editionContext.editionIdCourant();
        return repository.find(id).orElseThrow(() -> new IllegalStateException("Current edition not found: " + id));
    }

    /**
     * Declares the phase. Every move is allowed, backwards included: a phase
     * is a declaration, and going back to {@link EditionPhase#PREPARATION} is
     * how a frozen referential is reopened. Entering a phase refreezes an
     * unblocked structure.
     */
    public Edition setPhase(EditionPhase phase) {
        if (phase == null) {
            throw new BusinessError.Invalid("phase est requise : PREPARATION, CALCUL, PUBLIEE ou TERMINEE.");
        }
        repository.setPhase(editionContext.editionIdCourant(), phase);
        currentAction.champsModifies(List.of(phase.name()));
        return current();
    }

    /**
     * Reopens the structure of a published edition for a last-minute change,
     * without leaving {@link EditionPhase#PUBLIEE}. Refused in any other phase,
     * where it would mean nothing: there, the phase itself is what to move.
     */
    public Edition unblockStructure() {
        setStructureDebloquee(true);
        return current();
    }

    /** Freezes the structure of a published edition again; the next publication does it too. */
    public Edition refreezeStructure() {
        setStructureDebloquee(false);
        return current();
    }

    /**
     * What a publication does to the phase — see
     * {@link EditionRepository#markPublished(String)}.
     */
    public void markPublished() {
        repository.markPublished(editionContext.editionIdCourant());
    }

    private void setStructureDebloquee(boolean debloquee) {
        requirePublished();
        // No row updated: the phase moved between the check and the write — say so again.
        if (!repository.setStructureDebloquee(editionContext.editionIdCourant(), debloquee)) {
            requirePublished();
        }
    }

    private void requirePublished() {
        EditionPhase phase = current().getPhase();
        if (phase != EditionPhase.PUBLIEE) {
            throw new BusinessError.Conflict("L'édition est en phase « " + phase.libelle()
                    + " » : seule une édition publiée débloque sa structure. Changez sa phase pour modifier"
                    + " son référentiel.");
        }
    }
}
