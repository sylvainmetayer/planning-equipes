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

    /**
     * Qualifies a créneau id with this group's id (idempotent — a call on an
     * already-qualified id, e.g. from re-importing a previously exported
     * scenario, is a no-op). Créneau ids are a single global primary key, so
     * without this, two groups both defining e.g. "J1-MATIN" (a very common
     * naming convention across scenarios) would collide on save; qualifying
     * transparently at save time — the user only ever types the short id —
     * makes that structurally impossible instead of rejecting the save.
     */
    public String qualifierCreneauId(String creneauId) {
        String suffixe = "-" + id;
        return creneauId.endsWith(suffixe) ? creneauId : creneauId + suffixe;
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
