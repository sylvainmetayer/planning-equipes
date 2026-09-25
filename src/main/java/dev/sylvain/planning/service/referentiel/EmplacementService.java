package dev.sylvain.planning.service.referentiel;

import dev.sylvain.planning.domain.Emplacement;
import dev.sylvain.planning.service.BusinessError;
import dev.sylvain.planning.service.ConcurrentModificationGuard;
import dev.sylvain.planning.service.IdGenerator;
import dev.sylvain.planning.service.JdbcEditionScope;
import dev.sylvain.planning.service.ReferenceDataChangeTracker;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

/** CRUD of the emplacement referential — the physical places stands sit in. */
@ApplicationScoped
public class EmplacementService {

    private final EmplacementRepository repository;

    private final ReferenceDataChangeTracker changeTracker;

    private final ConcurrentModificationGuard staleWrites;

    private final IdGenerator ids;

    private final JdbcEditionScope scope;

    @Inject
    public EmplacementService(
            EmplacementRepository repository,
            ReferenceDataChangeTracker changeTracker,
            ConcurrentModificationGuard staleWrites,
            IdGenerator ids,
            JdbcEditionScope scope) {
        this.repository = repository;
        this.changeTracker = changeTracker;
        this.staleWrites = staleWrites;
        this.ids = ids;
        this.scope = scope;
    }

    public List<Emplacement> list() {
        return repository.listEmplacements();
    }

    /**
     * Creates the emplacement under an id the application draws (ADR 0050):
     * an id the caller sent is overwritten.
     */
    public Emplacement create(Emplacement emplacement) {
        scope.write(
                "Failed to create emplacement " + emplacement.getNom(), connection -> create(connection, emplacement));
        changeTracker.markModified();
        return emplacement;
    }

    /**
     * {@link #create(Emplacement)} inside a caller's transaction: written with
     * the rest, or not at all. The caller marks the referential modified once
     * its transaction is committed (see {@code TypologieService}).
     */
    Emplacement create(Connection connection, Emplacement emplacement) throws SQLException {
        validateCoordinates(emplacement);
        emplacement.setCode(Codes.normalise(emplacement.getCode(), IdGenerator.Kind.EMPLACEMENT));
        Codes.refuseTaken(
                emplacement.getCode(), repository.idByCode(connection, emplacement.getCode()), null, "l'emplacement");
        emplacement.setId(ids.next(connection, IdGenerator.Kind.EMPLACEMENT));
        repository.saveEmplacement(connection, emplacement, true);
        return emplacement;
    }

    public Emplacement update(String id, Emplacement emplacement) {
        if (!repository.emplacementExists(id)) {
            throw new BusinessError.NotFound("Emplacement inconnu : " + id);
        }
        emplacement.setId(id);
        validateCoordinates(emplacement);
        emplacement.setCode(Codes.normalise(emplacement.getCode(), IdGenerator.Kind.EMPLACEMENT));
        Codes.refuseTaken(emplacement.getCode(), repository.idByCode(emplacement.getCode()), id, "l'emplacement");
        repository.saveEmplacement(emplacement, false);
        changeTracker.markModified();
        return emplacement;
    }

    /** Id of the emplacement carrying {@code code}, {@code null} when none does. */
    public String idByCode(String code) {
        return repository.idByCode(code);
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
