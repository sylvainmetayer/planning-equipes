package dev.sylvain.planning.service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import dev.sylvain.planning.domain.Creneau;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.NotFoundException;

/**
 * CRUD of the créneau grid, and the découpage that turns the edition's
 * amplitudes into vacations.
 */
@ApplicationScoped
public class CreneauService {

    @Inject
    ReferenceDataRepository repository;

    @Inject
    ParametresService parametres;

    @Inject
    ReferenceDataChangeTracker changeTracker;

    public List<Creneau> list() {
        return repository.listCreneaux();
    }

    public Creneau create(Creneau creneau) {
        creneau.setId(null); // ignore any client-supplied id — the database always generates it
        Creneau cree = repository.insertCreneau(creneau);
        changeTracker.markModified();
        return cree;
    }

    public Creneau update(Long id, Creneau creneau) {
        if (!repository.creneauExists(id)) {
            throw new NotFoundException("Timeslot not found: " + id);
        }
        creneau.setId(id);
        repository.updateCreneau(creneau);
        changeTracker.markModified();
        return creneau;
    }

    public void delete(Long id) {
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
    public List<Creneau> createEnLot(List<Creneau> creneaux) {
        List<Creneau> crees = new ArrayList<>();
        for (Creneau creneau : creneaux) {
            creneau.setId(null); // ignore any client-supplied id — the database always generates it
            crees.add(repository.insertCreneau(creneau));
        }
        if (!crees.isEmpty()) {
            changeTracker.markModified();
        }
        return crees;
    }

    /** Deletes a batch of créneaux, for the same "one intent, one edit" reason as {@link #createEnLot}. */
    public int deleteEnLot(Collection<Long> ids) {
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
    public List<Creneau> previsualiserDecoupage() {
        List<Creneau> amplitudes = repository.listCreneaux();
        if (amplitudes.isEmpty()) {
            throw new ErreurMetier.Invalide("Aucune amplitude à découper : l'édition n'a aucun créneau");
        }
        return VacationGeneratorService.genererVacations(amplitudes, parametres.getDecoupage());
    }

    /**
     * Materializes the découpage <b>in place</b> (issue #172): the edition's
     * créneaux — the amplitudes just read — are replaced by the generated
     * vacations, and the persisted plan goes with them. Re-running with other
     * parameters means re-importing the scenario (or duplicating an
     * "amplitudes" edition first): the edition only ever holds one grid.
     */
    public void genererDecoupage() {
        List<Creneau> vacations = previsualiserDecoupage();
        repository.replaceCreneaux(vacations);
        changeTracker.markModified();
    }
}
