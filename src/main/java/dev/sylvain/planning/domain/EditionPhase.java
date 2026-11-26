package dev.sylvain.planning.domain;

/**
 * Where an edition stands in its cycle (ADR 0115), declared by the organiser
 * and moved to {@link #PUBLIEE} by the first publication. The phase is what
 * freezes the referential: what each phase leaves open is decided in
 * {@code GelReferentielService}, and the constant names are on the wire (the
 * {@code edition.phase} column, the JSON and the REST body).
 */
public enum EditionPhase {
    /** Everything is open: the stands, timeslots and people are being entered. */
    PREPARATION("Préparation"),
    /** The referential is frozen while the plan is computed and corrected. */
    CALCUL("Calcul"),
    /**
     * A plan was published: the structure (stands, timeslots, game categories,
     * locations) stays frozen unless explicitly unblocked; the animateurs'
     * fiches are open.
     */
    PUBLIEE("Publiée"),
    /** The event is over: frozen like {@link #CALCUL}. */
    TERMINEE("Terminée");

    private final String libelle;

    EditionPhase(String libelle) {
        this.libelle = libelle;
    }

    /** How the screens and the refusals name the phase, in French. */
    public String libelle() {
        return libelle;
    }
}
