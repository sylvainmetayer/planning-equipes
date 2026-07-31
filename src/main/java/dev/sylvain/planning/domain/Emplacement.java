package dev.sylvain.planning.domain;

import java.util.Objects;

/**
 * Physical location a stand is set up at (kiosque, mairie, château, ...),
 * geocoded so the solver can penalise moving an animateur between two distant
 * locations on consecutive slots. A stand without a linked emplacement is
 * simply never subject to that distance check.
 */
public class Emplacement {

    private String id;
    private String nom;
    private Double latitude;
    private Double longitude;

    public Emplacement() {
    }

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
     * Great-circle distance to another emplacement, in meters (haversine
     * formula, mean Earth radius). Returns {@code null} when either point has
     * no coordinates, so callers can tell "unknown distance" apart from "zero
     * distance" instead of silently treating a missing GPS fix as co-located.
     */
    public Double distanceMetresVers(Emplacement autre) {
        if (autre == null || latitude == null || longitude == null
                || autre.latitude == null || autre.longitude == null) {
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
