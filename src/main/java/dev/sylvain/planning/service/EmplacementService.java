package dev.sylvain.planning.service;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

import dev.sylvain.planning.domain.Emplacement;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/** CRUD of the emplacement referential — the physical places stands sit in. */
@ApplicationScoped
public class EmplacementService {

    @Inject
    EmplacementRepository repository;

    @Inject
    ReferenceDataChangeTracker changeTracker;

    @Inject
    ConcurrentModificationGuard staleWrites;

    public List<Emplacement> list() {
        return repository.listEmplacements();
    }

    public Emplacement create(Emplacement emplacement) {
        emplacement.setId(Ids.required(emplacement.getId(), "emplacement id"));
        validateCoordinates(emplacement);
        repository.saveEmplacement(emplacement, true);
        changeTracker.markModified();
        return emplacement;
    }

    /** {@link #create(Emplacement)} inside a caller's transaction: written with the rest, or not at all. */
    Emplacement create(Connection connection, Emplacement emplacement) throws SQLException {
        emplacement.setId(Ids.required(emplacement.getId(), "emplacement id"));
        validateCoordinates(emplacement);
        repository.saveEmplacement(connection, emplacement, true);
        changeTracker.markModified();
        return emplacement;
    }

    public Emplacement update(String id, Emplacement emplacement) {
        if (!repository.emplacementExists(id)) {
            throw new BusinessError.NotFound("Emplacement not found: " + id);
        }
        emplacement.setId(id);
        validateCoordinates(emplacement);
        repository.saveEmplacement(emplacement, false);
        changeTracker.markModified();
        return emplacement;
    }

    public void delete(String id) {
        repository.deleteEmplacement(id);
        changeTracker.markModified();
    }

    private void validateCoordinates(Emplacement emplacement) {
        Double latitude = emplacement.getLatitude();
        Double longitude = emplacement.getLongitude();
        if (latitude != null && (latitude < -90 || latitude > 90)) {
            throw new BusinessError.Invalid("latitude must be between -90 and 90");
        }
        if (longitude != null && (longitude < -180 || longitude > 180)) {
            throw new BusinessError.Invalid("longitude must be between -180 and 180");
        }
    }
}
