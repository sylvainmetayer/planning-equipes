package dev.sylvain.planning.domain;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public class Stand {

    private String id;
    private String nom;
    /** Ids referencing the {@code typologie} referential table (CRUD-managed), not a fixed enum. */
    private Set<String> typologiesProposees = new HashSet<>();
    private int effectifMin;
    private int effectifMax;
    private boolean reserveMajeurs;
    private boolean premium;
    /** Physical-effort tier; drives rest-after-effort and pénibilité-fairness constraints in QualiteConstraints/PreferenceConstraints. */
    private NiveauEffort niveauEffort = NiveauEffort.NORMAL;
    /** Physical location the stand is set up at; nullable (not every stand is geocoded). */
    private Emplacement emplacement;
    /**
     * Closure windows for this stand, e.g. "closed 14:00-16:00 on 2026-07-18"
     * — possibly only part of a créneau. Empty = the stand is open on every
     * créneau (the default), unless {@link #ouvertures} says otherwise for
     * that day. See {@link Creneau#segmentsOuvertsMinutes(Stand)}.
     */
    private List<IndisponibiliteStand> indisponibilites = new ArrayList<>();
    /**
     * Opening windows for this stand — the inverse of {@link #indisponibilites},
     * for a stand normally closed and only staffed during specific windows.
     * Empty = no day is opening-only (the default). A day can never carry
     * both an entry here and one in {@link #indisponibilites}; see
     * {@link OuvertureStand}'s javadoc for the three-state rule this implies.
     */
    private List<OuvertureStand> ouvertures = new ArrayList<>();

    public Stand() {
    }

    public Stand(String id, String nom, Set<String> typologiesProposees, int effectifMin, int effectifMax,
            boolean reserveMajeurs) {
        this(id, nom, typologiesProposees, effectifMin, effectifMax, reserveMajeurs, false);
    }

    public Stand(String id, String nom, Set<String> typologiesProposees, int effectifMin, int effectifMax,
            boolean reserveMajeurs, boolean premium) {
        this.id = id;
        this.nom = nom;
        this.typologiesProposees = typologiesProposees;
        this.effectifMin = effectifMin;
        this.effectifMax = effectifMax;
        this.reserveMajeurs = reserveMajeurs;
        this.premium = premium;
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

    public Set<String> getTypologiesProposees() {
        return typologiesProposees;
    }

    public void setTypologiesProposees(Set<String> typologiesProposees) {
        this.typologiesProposees = typologiesProposees;
    }

    public int getEffectifMin() {
        return effectifMin;
    }

    public void setEffectifMin(int effectifMin) {
        this.effectifMin = effectifMin;
    }

    public int getEffectifMax() {
        return effectifMax;
    }

    public void setEffectifMax(int effectifMax) {
        this.effectifMax = effectifMax;
    }

    public boolean isReserveMajeurs() {
        return reserveMajeurs;
    }

    public void setReserveMajeurs(boolean reserveMajeurs) {
        this.reserveMajeurs = reserveMajeurs;
    }

    /** Editor/publisher-tier stand: high-visibility, needs experienced staffing continuity. */
    public boolean isPremium() {
        return premium;
    }

    public void setPremium(boolean premium) {
        this.premium = premium;
    }

    public NiveauEffort getNiveauEffort() {
        return niveauEffort;
    }

    public void setNiveauEffort(NiveauEffort niveauEffort) {
        this.niveauEffort = niveauEffort != null ? niveauEffort : NiveauEffort.NORMAL;
    }

    public Emplacement getEmplacement() {
        return emplacement;
    }

    public void setEmplacement(Emplacement emplacement) {
        this.emplacement = emplacement;
    }

    public List<IndisponibiliteStand> getIndisponibilites() {
        return indisponibilites;
    }

    public void setIndisponibilites(List<IndisponibiliteStand> indisponibilites) {
        this.indisponibilites = indisponibilites != null ? indisponibilites : new ArrayList<>();
    }

    public List<OuvertureStand> getOuvertures() {
        return ouvertures;
    }

    public void setOuvertures(List<OuvertureStand> ouvertures) {
        this.ouvertures = ouvertures != null ? ouvertures : new ArrayList<>();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Stand stand)) {
            return false;
        }
        return Objects.equals(id, stand.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
