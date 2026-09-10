package dev.sylvain.planning.service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.ModeGrilleCreneaux;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import dev.sylvain.planning.service.solve.SolverJobService;
import dev.sylvain.planning.service.solve.VacationGeneratorService;

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
            throw new BusinessError.NotFound("Timeslot not found: " + id);
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
        for (Creneau creneau : creneaux) {
            creneau.setId(null); // ignore any client-supplied id — the database always generates it
            crees.add(repository.insertCreneau(creneau));
        }
        if (!crees.isEmpty()) {
            changeTracker.markModified();
        }
        return crees;
    }

    /** Deletes a batch of créneaux, for the same "one intent, one edit" reason as {@link #createInBulk}. */
    public int deleteInBulk(Collection<Long> ids) {
        // Checked once for the lot, not once per row: the whole batch is refused
        // or none of it is, and the solver state cannot change under us anyway.
        solverJobs.refuseIfSolving();
        int supprimes = 0;
        for (Long id : ids) {
            repository.deleteCreneau(id);
            supprimes++;
        }
        if (supprimes > 0) {
            changeTracker.markModified();
        }
        return supprimes;
    }

    /**
     * Generates the vacations the edition's current créneaux — read as
     * amplitudes — would produce, without persisting anything: the découpage
     * preview.
     */
    public List<Creneau> previewDecoupage() {
        List<Creneau> amplitudes = repository.listCreneaux();
        if (amplitudes.isEmpty()) {
            throw new BusinessError.Invalid("Aucune amplitude à découper : l'édition n'a aucun créneau");
        }
        return VacationGeneratorService.generateVacations(amplitudes, parametres.getDecoupage());
    }

    /**
     * Materializes the découpage <b>in place</b> (issue #172): the edition's
     * créneaux — the amplitudes just read — are replaced by the generated
     * vacations, and the persisted plan goes with them. Re-running with other
     * parameters means re-importing the scenario (or duplicating an
     * "amplitudes" edition first): the edition only ever holds one grid.
     */
    public void generateDecoupage() {
        List<Creneau> vacations = previewDecoupage();
        repository.replaceCreneaux(vacations);
        // What the edition holds has just changed nature, so it says so here
        // rather than in the browser: an assistant calling generer_decoupage
        // then valider_creneaux would otherwise read staggered vacations as
        // amplitudes and report every relay overlap as a data-entry mistake.
        parametres.updateModeGrille(ModeGrilleCreneaux.VACATIONS);
        changeTracker.markModified();
    }

    /**
     * Replaces the whole grid by {@code creneaux}, the persisted plan going
     * with it — what the découpage does, offered to the derivation from the
     * stands' hours. Refused while a solve runs, like every rewrite of the
     * grid.
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
