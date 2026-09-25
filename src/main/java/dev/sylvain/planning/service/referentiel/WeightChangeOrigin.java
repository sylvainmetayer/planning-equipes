package dev.sylvain.planning.service.referentiel;

import dev.sylvain.planning.service.journal.Acteur;

/**
 * Where a change of a constraint's weight or activation came from — never who:
 * the application has one admin account, and a name would be a personal datum
 * in a table meant to last the whole preparation of an edition.
 */
public enum WeightChangeOrigin {
    /** The Contraintes screen. */
    SCREEN(Acteur.ADMIN),
    /** An MCP tool call. */
    ASSISTANT(Acteur.ASSISTANT),
    /** A scenario file that pinned its dosage, applied on import. */
    SCENARIO(Acteur.SYSTEME),
    /** The dosage an edition inherited from the one it was duplicated from. */
    DUPLICATION(Acteur.SYSTEME);

    private final Acteur acteur;

    WeightChangeOrigin(Acteur acteur) {
        this.acteur = acteur;
    }

    /** The journal's vocabulary for the same fact, so both histories name the origin alike. */
    public Acteur acteur() {
        return acteur;
    }

    /** Tolerant read of a stored value: a row written by a later version reads as the screen's. */
    static WeightChangeOrigin parse(String value) {
        try {
            return value == null ? SCREEN : valueOf(value);
        } catch (IllegalArgumentException e) {
            return SCREEN;
        }
    }
}
