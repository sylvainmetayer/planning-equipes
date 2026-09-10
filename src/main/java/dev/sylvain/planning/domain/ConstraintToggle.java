package dev.sylvain.planning.domain;

/**
 * Problem fact marking one constraint (identified by the name passed to
 * {@code asConstraint(...)}) as disabled for the current solve. Presence in
 * {@link PlanningEvenement}'s list means disabled; absence means active — the
 * default for every constraint, same convention as the
 * {@code constraint_toggle} table.
 */
public class ConstraintToggle {

    private String nom;

    public ConstraintToggle() {}

    public ConstraintToggle(String nom) {
        this.nom = nom;
    }

    public String getNom() {
        return nom;
    }

    public void setNom(String nom) {
        this.nom = nom;
    }
}
