package dev.sylvain.planning.domain;

/**
 * Organisational-quality thresholds a constraint needs at solve time, carried
 * as a problem fact so a rule can join them exactly like
 * {@link ParametresLegaux}. Unlike the legal ones, these are not stored per
 * edition in the database: they are read from application configuration
 * ({@code planning.contraintes.*}) by {@code PlanningService}, because they
 * tune the plan's comfort rather than the law it must obey.
 *
 * <p>A record, unlike {@link ParametresLegaux}: a problem fact is immutable by
 * nature — the solver reads it thousands of times per second and never writes
 * it — and the setter this used to carry was never called from anywhere.</p>
 *
 * @param maxEmplacementsDistinctsParJour how many distinct {@link Emplacement}s
 *        an animateur may cover in one day before
 *        {@code limiterEmplacementsParJour} starts penalising. Three lets a
 *        normal day (a stand in the morning, another in the afternoon, a third
 *        in the evening) pass untouched, so the default changes nothing on an
 *        existing plan — it only catches the genuinely scattered days.
 */
public record ParametresQualite(int maxEmplacementsDistinctsParJour) {

    /** @see #maxEmplacementsDistinctsParJour() */
    public static final int EMPLACEMENTS_DISTINCTS_PAR_JOUR_MAX_PAR_DEFAUT = 3;

    public ParametresQualite() {
        this(EMPLACEMENTS_DISTINCTS_PAR_JOUR_MAX_PAR_DEFAUT);
    }
}
