package dev.sylvain.planning.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.time.Instant;
import java.util.Objects;

/**
 * Physical location a stand is set up at (kiosque, mairie, château, ...),
 * geocoded so the solver can penalise moving an animateur between two distant
 * locations on consecutive slots. A stand without a linked emplacement is
 * simply never subject to that distance check.
 */
public class Emplacement {

    private String id;

    /**
     * The readable key of this emplacement in the files an organiser keeps (the CSV
     * imports, the scenario), unique in its edition, {@code null} when none
     * was given. The id is generated and means nothing (ADR 0050); the code is
     * what a file matches on.
     */
    private String code;

    private String nom;
    private Double latitude;
    private Double longitude;

    /**
     * When this row was last written (issue #362), read from the referential
     * and echoed back by a form on save: a write carrying a value older than
     * the row's is refused, see {@code ConcurrentModificationGuard}. {@code null}
     * on an object that never went through the database, and on a write that
     * deliberately carries no precondition (import, MCP merge, a client that
     * chose to overwrite).
     */
    private Instant modifieLe;

    public Emplacement() {}

    public Emplacement(String id, String nom, Double latitude, Double longitude) {
        this.id = id;
        this.nom = nom;
        this.latitude = latitude;
        this.longitude = longitude;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public Instant getModifieLe() {
        return modifieLe;
    }

    public void setModifieLe(Instant modifieLe) {
        this.modifieLe = modifieLe;
    }

    public String getNom() {
        return nom;
    }

    public void setNom(String nom) {
        this.nom = nom;
    }

    public Double getLatitude() {
        return latitude;
    }

    public void setLatitude(Double latitude) {
        this.latitude = latitude;
    }

    public Double getLongitude() {
        return longitude;
    }

    public void setLongitude(Double longitude) {
        this.longitude = longitude;
    }

    /**
     * Both coordinates, or neither: a position is a pair, and half of one
     * points at the Gulf of Guinea. Every reader that hands a location out —
     * the ICS {@code GEO} line, the map link of the individual PDF, the espace
     * animateur — asks this before sending anything.
     *
     * <p>{@code @JsonIgnore}, and not by taste: an {@code Emplacement} is
     * serialised as it stands by the referential API, where a derived
     * {@code geocoded} key would be one more thing on the wire that no client
     * asked for and every client would then be entitled to read.</p>
     */
    @JsonIgnore
    public boolean isGeocoded() {
        return latitude != null && longitude != null;
    }

    /**
     * Great-circle distance to another emplacement, in meters (haversine
     * formula, mean Earth radius). Returns {@code null} when either point has
     * no coordinates, so callers can tell "unknown distance" apart from "zero
     * distance" instead of silently treating a missing GPS fix as co-located.
     */
    public Double distanceMetresTo(Emplacement autre) {
        if (autre == null
                || latitude == null
                || longitude == null
                || autre.latitude == null
                || autre.longitude == null) {
            return null;
        }
        double rayonTerreMetres = 6_371_000.0;
        double phi1 = Math.toRadians(latitude);
        double phi2 = Math.toRadians(autre.latitude);
        double deltaPhi = Math.toRadians(autre.latitude - latitude);
        double deltaLambda = Math.toRadians(autre.longitude - longitude);
        double a = Math.sin(deltaPhi / 2) * Math.sin(deltaPhi / 2)
                + Math.cos(phi1) * Math.cos(phi2) * Math.sin(deltaLambda / 2) * Math.sin(deltaLambda / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return rayonTerreMetres * c;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Emplacement emplacement)) {
            return false;
        }
        return Objects.equals(id, emplacement.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
