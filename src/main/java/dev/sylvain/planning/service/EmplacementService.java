package dev.sylvain.planning.service;

import java.util.List;

import dev.sylvain.planning.domain.Emplacement;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.NotFoundException;

/** CRUD of the emplacement referential — the physical places stands sit in. */
@ApplicationScoped
public class EmplacementService {

    @Inject
    ReferenceDataRepository repository;

    @Inject
    ReferenceDataChangeTracker changeTracker;

    public List<Emplacement> list() {
        return repository.listEmplacements();
    }

    public Emplacement create(Emplacement emplacement) {
        emplacement.setId(Identifiants.requis(emplacement.getId(), "emplacement id"));
        validerCoordonnees(emplacement);
        repository.saveEmplacement(emplacement);
        changeTracker.markModified();
        return emplacement;
    }

    public Emplacement update(String id, Emplacement emplacement) {
        if (!repository.emplacementExists(id)) {
            throw new NotFoundException("Emplacement not found: " + id);
        }
        emplacement.setId(id);
        validerCoordonnees(emplacement);
        repository.saveEmplacement(emplacement);
        changeTracker.markModified();
        return emplacement;
    }

    public void delete(String id) {
        repository.deleteEmplacement(id);
        changeTracker.markModified();
    }

    private void validerCoordonnees(Emplacement emplacement) {
        Double latitude = emplacement.getLatitude();
        Double longitude = emplacement.getLongitude();
        if (latitude != null && (latitude < -90 || latitude > 90)) {
            throw new ErreurMetier.Invalide("latitude must be between -90 and 90");
        }
        if (longitude != null && (longitude < -180 || longitude > 180)) {
            throw new ErreurMetier.Invalide("longitude must be between -180 and 180");
        }
    }
}
