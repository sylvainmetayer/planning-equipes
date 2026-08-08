package dev.sylvain.planning.domain;

/**
 * Admin-configurable solver termination duration (Données tab). Never seen
 * by the solver as a problem fact — it is only read back by the frontend to
 * build the {@code ?seconds=} query param on a solve/analyze request, so
 * every browser sends the same value instead of a per-browser one.
 */
public class ParametresSolveur {

    /** Mirrors the default of `planning.solver.seconds-limit` in application.properties. */
    public static final int DUREE_RESOLUTION_SECONDES_PAR_DEFAUT = 180;

    private int dureeResolutionSecondes = DUREE_RESOLUTION_SECONDES_PAR_DEFAUT;

    public ParametresSolveur() {
    }

    public ParametresSolveur(int dureeResolutionSecondes) {
        this.dureeResolutionSecondes = dureeResolutionSecondes;
    }

    public int getDureeResolutionSecondes() {
        return dureeResolutionSecondes;
    }

    public void setDureeResolutionSecondes(int dureeResolutionSecondes) {
        this.dureeResolutionSecondes = dureeResolutionSecondes;
    }
}
