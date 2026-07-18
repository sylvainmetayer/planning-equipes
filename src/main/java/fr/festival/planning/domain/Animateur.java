package fr.festival.planning.domain;

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
    private StatutAnimateur statut;
    private Map<TypologieJeu, NiveauCompetence> competences = new HashMap<>();
    private Set<Creneau> disponibilites = new HashSet<>();
    private ContactLegal contactLegal;

    public Animateur() {
    }

    public Animateur(String id, String prenom, String nom, LocalDate dateNaissance, StatutAnimateur statut) {
        this.id = id;
        this.prenom = prenom;
        this.nom = nom;
        this.dateNaissance = dateNaissance;
        this.statut = statut;
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

    public StatutAnimateur getStatut() {
        return statut;
    }

    public void setStatut(StatutAnimateur statut) {
        this.statut = statut;
    }

    public Map<TypologieJeu, NiveauCompetence> getCompetences() {
        return competences;
    }

    public void setCompetences(Map<TypologieJeu, NiveauCompetence> competences) {
        this.competences = competences;
    }

    public Set<Creneau> getDisponibilites() {
        return disponibilites;
    }

    public void setDisponibilites(Set<Creneau> disponibilites) {
        this.disponibilites = disponibilites;
    }

    public ContactLegal getContactLegal() {
        return contactLegal;
    }

    public void setContactLegal(ContactLegal contactLegal) {
        this.contactLegal = contactLegal;
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
