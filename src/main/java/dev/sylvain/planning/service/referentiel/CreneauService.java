package dev.sylvain.planning.service.referentiel;

import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.service.BusinessError;
import dev.sylvain.planning.service.ConcurrentModificationGuard;
import dev.sylvain.planning.service.ReferenceDataChangeTracker;
import dev.sylvain.planning.service.solve.SolverJobService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * CRUD of the créneau grid, and the découpage that turns the edition's
 * amplitudes into vacations.
 */
@ApplicationScoped
public class CreneauService {

    @Inject
    CreneauRepository repository;

    @Inject
    ParametresService parametres;

    @Inject
    ReferenceDataChangeTracker changeTracker;

    @Inject
    ConcurrentModificationGuard staleWrites;

    @Inject
    SolverJobService solverJobs;

    public List<Creneau> list() {
        return repository.listCreneaux();
    }

    /** One timeslot by id, {@code null} when the edition holds none. */
    public Creneau find(Long id) {
        return repository.findCreneau(id);
    }

    public Creneau create(Creneau creneau) {
        CreneauValidator.check(creneau);
        creneau.setId(null); // ignore any client-supplied id — the database always generates it
        Creneau cree = repository.insertCreneau(creneau);
        changeTracker.markModified();
        return cree;
    }

    public Creneau update(Long id, Creneau creneau) {
        CreneauValidator.check(creneau);
        if (!repository.creneauExists(id)) {
            throw new BusinessError.NotFound("Créneau inconnu : " + id);
        }
        creneau.setId(id);
        repository.updateCreneau(creneau);
        changeTracker.markModified();
        return creneau;
    }

    /**
     * Removes the créneau, and the seats placed on it (issue #281).
     *
     * <p>Refused while a solve holds this edition's solver, for the same reason
     * as the stand and animateur deletes: the landing persist re-upserts the
     * créneaux its result names, so the grid would come back on its own. See
     * {@link SolverJobService#refuseIfSolving}.</p>
     */
    public void delete(Long id) {
        solverJobs.refuseIfSolving();
        repository.deleteCreneau(id);
        changeTracker.markModified();
    }

    /**
     * Inserts a batch of créneaux — the product of one recurrence rule — as a
     * single reference-data change.
     *
     * <p>Not a loop over {@link #create} at the caller's level on purpose:
     * that would stamp the "données modifiées depuis le dernier solve" marker
     * once per row, so a rule covering fourteen days would look like fourteen
     * separate edits in the toolbar warnings. One rule is one edit.</p>
     *
     * <p>Day numbers are deliberately not touched here: {@link Creneau#getJour()}
     * is never persisted, it is recomputed on read by
     * {@link Creneau#assignerJours} over the whole edition — which is also
     * what keeps the numbering correct when a batch adds a date earlier than
     * every existing one.</p>
     */
    public List<Creneau> createInBulk(List<Creneau> creneaux) {
        // A recurrence and a derivation both land here, and both add rows a
        // running solve would not know about — the same reason delete and
        // replace refuse.
        solverJobs.refuseIfSolving();
        List<Creneau> crees = new ArrayList<>();
        creneaux.forEach(CreneauValidator::check);
        try {
            for (Creneau creneau : creneaux) {
                creneau.setId(null); // ignore any client-supplied id — the database always generates it
                crees.add(repository.insertCreneau(creneau));
            }
        } finally {
            // Each insert is a transaction of its own, so a failure on the
            // tenth row leaves nine committed. Marking from a finally may
            // over-mark, never under-mark: an unmarked write would leave a
            // snapshot older than it reading « à jour », the one direction the
            // freshness badge must never get wrong (issue #170).
            if (!crees.isEmpty()) {
                changeTracker.markModified();
            }
        }
        return crees;
    }

    /** Deletes a batch of créneaux, for the same "one intent, one edit" reason as {@link #createInBulk}. */
    public int deleteInBulk(Collection<Long> ids) {
        // Checked once for the lot, not once per row: the whole batch is refused
        // or none of it is, and the solver state cannot change under us anyway.
        solverJobs.refuseIfSolving();
        int supprimes = 0;
        try {
            for (Long id : ids) {
                repository.deleteCreneau(id);
                supprimes++;
            }
        } finally {
            // One transaction per row here too — see createInBulk.
            if (supprimes > 0) {
                changeTracker.markModified();
            }
        }
        return supprimes;
    }

    /**
     * A CSV import's whole effect on the grid, as one edit: the rows the file
     * adds, and the ones it writes over — the latter already carrying the id
     * of the timeslot they match.
     *
     * <p>Not a loop over {@link #create} and {@link #update} at the caller's
     * level, for the reason {@link #createInBulk} spells out: one file is one
     * edit, not one edit per row. Nothing is removed — a timeslot the file
     * leaves out stays, which is the doctrine of every CSV import here.</p>
     */
    public void importer(List<Creneau> aCreer, List<Creneau> aMettreAJour) {
        // Both halves add or move rows a running solve would not know about,
        // the same reason the bulk create and the replace refuse.
        solverJobs.refuseIfSolving();
        aCreer.forEach(CreneauValidator::check);
        aMettreAJour.forEach(CreneauValidator::check);
        for (Creneau creneau : aCreer) {
            creneau.setId(null); // ignore any client-supplied id — the database always generates it
            repository.insertCreneau(creneau);
        }
        for (Creneau creneau : aMettreAJour) {
            repository.updateCreneau(creneau);
        }
        if (!aCreer.isEmpty() || !aMettreAJour.isEmpty()) {
            changeTracker.markModified();
        }
    }

    /**
     * Replaces the whole grid by {@code creneaux}, the persisted plan going
     * with it — what the derivation from the stands' hours does when it is
     * asked to start over. Refused while a solve runs, like every rewrite of
     * the grid.
     */
    public List<Creneau> replace(List<Creneau> creneaux) {
        solverJobs.refuseIfSolving();
        for (Creneau creneau : creneaux) {
            CreneauValidator.check(creneau);
        }
        repository.replaceCreneaux(creneaux);
        changeTracker.markModified();
        return repository.listCreneaux();
    }
}
