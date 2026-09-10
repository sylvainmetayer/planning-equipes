package dev.sylvain.planning.service.referentiel;

import java.util.List;

import dev.sylvain.planning.domain.Stand;

/**
 * Body of a successful write on the stand referential: the stand as persisted
 * and the {@link Avertissement}s its schedule raised. Same shape, and same
 * reason, as {@link WrittenAnimateur} and {@link WrittenCreneau}.
 */
public record WrittenStand(Stand stand, List<Avertissement> avertissements) {

    public WrittenStand {
        avertissements = List.copyOf(avertissements);
    }
}
