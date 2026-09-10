package dev.sylvain.planning.service.referentiel;

import java.util.List;

import dev.sylvain.planning.domain.Animateur;

/**
 * Body of a successful write on the animateur referential: the fiche exactly as
 * it was persisted, and the {@link Avertissement}s it raised.
 *
 * <p>The entity keeps a key of its own rather than being flattened, so a
 * warning can never be mistaken for a field of the animateur — and so a client
 * that ignores {@code avertissements} still reads the same fiche it did
 * before.</p>
 */
public record WrittenAnimateur(Animateur animateur, List<Avertissement> avertissements) {

    public WrittenAnimateur {
        avertissements = List.copyOf(avertissements);
    }
}
