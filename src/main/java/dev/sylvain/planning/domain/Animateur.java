package dev.sylvain.planning.domain;

import java.time.LocalDate;
import java.time.Period;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public class Animateur {

    private String id;
    private String prenom;
    private String nom;
    private LocalDate dateNaissance;
    private boolean manager;
    private Map<TypologieJeu, NiveauCompetence> competences = new HashMap<>();
    private Set<LocalDate> joursIndisponibles = new HashSet<>();

    public Animateur() {
    }

    public Animateur(String id, String prenom, String nom, LocalDate dateNaissance, boolean manager) {
        this.id = id;
        this.prenom = prenom;
        this.nom = nom;
        this.dateNaissance = dateNaissance;
        this.manager = manager;
    }

    /**
     * True when the animateur has declared the given date as an off day.
     * Availability is opt-out: an animateur is available on every festival date
     * except the ones listed in {@link #joursIndisponibles}.
     */
    public boolean estIndisponibleLe(LocalDate dateReference) {
        return dateReference != null
                && joursIndisponibles != null
                && joursIndisponibles.contains(dateReference);
    }

    public boolean estMineurLe(LocalDate dateReference) {
        return dateReference != null
                && dateNaissance != null
                && Period.between(dateNaissance, dateReference).getYears() < 18;
    }

    public boolean estMajeurLe(LocalDate dateReference) {
        return dateReference != null
                && dateNaissance != null
                && !estMineurLe(dateReference);
    }

    public boolean possedeCompetencePour(Stand stand) {
        return stand.getTypologiesProposees().stream().anyMatch(competences::containsKey);
    }

    public boolean estReferentPour(Stand stand) {
        return stand.getTypologiesProposees().stream()
                .anyMatch(typologie -> competences.get(typologie) == NiveauCompetence.REFERENT);
    }

    public boolean estDebutantPour(Stand stand) {
        return stand.getTypologiesProposees().stream()
                .anyMatch(typologie -> competences.get(typologie) == NiveauCompetence.DEBUTANT);
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getPrenom() {
        return prenom;
    }

    public void setPrenom(String prenom) {
        this.prenom = prenom;
    }

    public String getNom() {
        return nom;
    }

    public void setNom(String nom) {
        this.nom = nom;
    }

    public LocalDate getDateNaissance() {
        return dateNaissance;
    }

    public void setDateNaissance(LocalDate dateNaissance) {
        this.dateNaissance = dateNaissance;
    }

    /** Manages other animateurs; every animateur (manager or not) is paid. */
    public boolean isManager() {
        return manager;
    }

    public void setManager(boolean manager) {
        this.manager = manager;
    }

    public Map<TypologieJeu, NiveauCompetence> getCompetences() {
        return competences;
    }

    public void setCompetences(Map<TypologieJeu, NiveauCompetence> competences) {
        this.competences = competences;
    }

    public Set<LocalDate> getJoursIndisponibles() {
        return joursIndisponibles;
    }

    public void setJoursIndisponibles(Set<LocalDate> joursIndisponibles) {
        this.joursIndisponibles = joursIndisponibles != null ? joursIndisponibles : new HashSet<>();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Animateur animateur)) {
            return false;
        }
        return Objects.equals(id, animateur.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
