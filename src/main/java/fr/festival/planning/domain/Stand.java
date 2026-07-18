package fr.festival.planning.domain;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

public class Stand {

    private String id;
    private String nom;
    private Set<TypologieJeu> typologiesProposees = new HashSet<>();
    private int effectifMin;
    private int effectifMax;
    private boolean reserveMajeurs;

    public Stand() {
    }

    public Stand(String id, String nom, Set<TypologieJeu> typologiesProposees, int effectifMin, int effectifMax,
            boolean reserveMajeurs) {
        this.id = id;
        this.nom = nom;
        this.typologiesProposees = typologiesProposees;
        this.effectifMin = effectifMin;
        this.effectifMax = effectifMax;
        this.reserveMajeurs = reserveMajeurs;
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

    public Set<TypologieJeu> getTypologiesProposees() {
        return typologiesProposees;
    }

    public void setTypologiesProposees(Set<TypologieJeu> typologiesProposees) {
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
