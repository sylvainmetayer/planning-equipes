package dev.sylvain.planning.domain;

/**
 * Problem fact carrying the <b>explicit state</b> of one constraint
 * (identified by the name passed to {@code asConstraint(...)}) for the current
 * solve. Absence means « whatever the catalogue says by default », which is
 * active for all but the few names listed in
 * {@code ConstraintCatalog.DESACTIVEES_PAR_DEFAUT}.
 *
 * <p>The one-argument constructor means <i>disabled</i>, which is what the
 * fact meant when every constraint was active by default and a row could only
 * ever switch one off. A default-off rule needs the other direction — a fact
 * saying « this organiser does want it » — which is what {@code actif} carries;
 * same convention as the {@code constraint_toggle} table.</p>
 */
public class ConstraintToggle {

    private String nom;

    private boolean actif;

    public ConstraintToggle() {}

    /** Switches {@code nom} off, the only thing a toggle could do before default-off rules existed. */
    public ConstraintToggle(String nom) {
        this(nom, false);
    }

    public ConstraintToggle(String nom, boolean actif) {
        this.nom = nom;
        this.actif = actif;
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
}
