package dev.sylvain.planning.service.referentiel;

import dev.sylvain.planning.domain.Creneau;
import java.util.List;

/**
 * Body of a successful write on the créneau grid: the timeslot exactly as it
 * was persisted — the generated id included — and the {@link Avertissement}s it
 * raised. Same shape, and same reason, as {@link WrittenAnimateur}.
 */
public record WrittenCreneau(Creneau creneau, List<Avertissement> avertissements) {

    public WrittenCreneau {
        avertissements = List.copyOf(avertissements);
    }
}
