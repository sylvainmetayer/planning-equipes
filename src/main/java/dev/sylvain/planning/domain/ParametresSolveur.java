package dev.sylvain.planning.domain;

/**
 * Admin-configurable solver termination duration (Données tab). Never seen
 * by the solver as a problem fact — it is only read back by the frontend to
 * build the {@code ?seconds=} query param on a solve/analyze request, so
 * every browser sends the same value instead of a per-browser one.
 *
 * <p>A record: it is read, written whole, and never mutated field by field.
 * The JSON shape is unchanged — Jackson names a record's properties after its
 * components, which are exactly the former getters.</p>
 *
 * @param dureeResolutionSecondes how long a solve/analyze runs by default
 * @param mailFinResolution       whether a finished solve mails its outcome to
 *                                the admin address. Off by default — sending
 *                                mail is never something an application should
 *                                start doing on its own — and inert until
 *                                {@code MAIL_ADMIN} is configured
 */
public record ParametresSolveur(int dureeResolutionSecondes, boolean mailFinResolution) {

    /** Mirrors the default of `planning.solver.seconds-limit` in application.properties. */
    public static final int DUREE_RESOLUTION_SECONDES_PAR_DEFAUT = 180;

    public ParametresSolveur() {
        this(DUREE_RESOLUTION_SECONDES_PAR_DEFAUT, false);
    }

    public ParametresSolveur(int dureeResolutionSecondes) {
        this(dureeResolutionSecondes, false);
    }
}
