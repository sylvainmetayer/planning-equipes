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
    /**
     * Id of the "amplitudes" group this group's créneaux were auto-generated
     * from (see {@code VacationGeneratorService}), or {@code null} for a group
     * edited/imported directly. Purely informational — enables a "régénérer"
     * action in the découpage UI; the solver never reads it.
     */
    private String groupeSourceId;
    /**
     * Whether this group takes part in the "résoudre tous les groupes" queue
     * (issue #167). Defaults to {@code true}; the découpage paths flip it to
     * {@code false} on the amplitudes source they slice from, since solving
     * raw amplitudes only yields a garbage plan. User-editable — the
     * amplitudes/vacations distinction is not derivable reliably.
     */
    private boolean resoudreEnFile = true;

    public GroupeCreneau() {
    }

    public GroupeCreneau(String id, String nom, boolean actif) {
        this.id = id;
        this.nom = nom;
        this.actif = actif;
    }

    public GroupeCreneau(String id, String nom, boolean actif, String groupeSourceId) {
        this.id = id;
        this.nom = nom;
        this.actif = actif;
        this.groupeSourceId = groupeSourceId;
    }

    public GroupeCreneau(String id, String nom, boolean actif, String groupeSourceId, boolean resoudreEnFile) {
        this.id = id;
        this.nom = nom;
        this.actif = actif;
        this.groupeSourceId = groupeSourceId;
        this.resoudreEnFile = resoudreEnFile;
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

    public String getGroupeSourceId() {
        return groupeSourceId;
    }

    public void setGroupeSourceId(String groupeSourceId) {
        this.groupeSourceId = groupeSourceId;
    }

    public boolean isResoudreEnFile() {
        return resoudreEnFile;
    }

    public void setResoudreEnFile(boolean resoudreEnFile) {
        this.resoudreEnFile = resoudreEnFile;
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
