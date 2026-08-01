package dev.sylvain.planning.domain;

import java.util.Objects;

/**
 * Named set of timeslots (a "planning"), so an alternate schedule can be
 * prepared ahead of time and swapped in on short notice. Exactly one group
 * is active at a time; the solver only considers the active group's
 * {@link Creneau}s when building a problem from reference data.
 */
public class GroupeCreneau {

    private String id;
    private String nom;
    private boolean actif;

    public GroupeCreneau() {
    }

    public GroupeCreneau(String id, String nom, boolean actif) {
        this.id = id;
        this.nom = nom;
        this.actif = actif;
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

    public boolean isActif() {
        return actif;
    }

    public void setActif(boolean actif) {
        this.actif = actif;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof GroupeCreneau groupeCreneau)) {
            return false;
        }
        return Objects.equals(id, groupeCreneau.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
