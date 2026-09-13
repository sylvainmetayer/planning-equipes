package dev.sylvain.planning.service.referentiel;

import dev.sylvain.planning.domain.ContrainteAdHoc;
import java.util.List;

/**
 * Body of a successful write of a hand-entered exception: the exception as
 * persisted and the {@link Avertissement}s it raised. Same shape, and same
 * reason, as {@link WrittenStand}: a forced assignment falling on its
 * animateurs' days off is accepted — the day off may be withdrawn, and it
 * usually arrives after the exception anyway — but never silently.
 */
public record WrittenContrainteAdHoc(ContrainteAdHoc contrainte, List<Avertissement> avertissements) {

    public WrittenContrainteAdHoc {
        avertissements = List.copyOf(avertissements);
    }
}
