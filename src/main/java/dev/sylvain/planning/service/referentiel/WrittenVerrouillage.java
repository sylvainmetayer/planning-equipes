package dev.sylvain.planning.service.referentiel;

import dev.sylvain.planning.domain.VerrouillagePlanning;
import java.util.List;

/**
 * Body of a successful write of a lock: the lock as persisted and the
 * {@link Avertissement}s it raised. Same shape, and same reason, as
 * {@link WrittenContrainteAdHoc}: freezing seats that already break a hard rule
 * is a legitimate decision — the lock keeps them scored, it does not exempt
 * them — and is accepted, but never silently.
 */
public record WrittenVerrouillage(VerrouillagePlanning verrouillage, List<Avertissement> avertissements) {

    public WrittenVerrouillage {
        avertissements = List.copyOf(avertissements);
    }
}
