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
    /** Keys reference the {@code typologie} referential table (CRUD-managed), not a fixed enum. */
    private Map<String, NiveauCompetence> competences = new HashMap<>();
    /** Ids referencing the {@code typologie} referential table — stand typologies the animateur wishes to be assigned to. */
    private Set<String> souhaits = new HashSet<>();
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

    /**
     * True when the animateur is under 18 on the given date ("jeune
     * travailleur" in the Code du travail). Always derived from
     * {@link #dateNaissance}, never stored.
     */
    public boolean estMineurLe(LocalDate dateReference) {
        return dateReference != null
                && dateNaissance != null
                && Period.between(dateNaissance, dateReference).getYears() < 18;
    }

    /**
     * True when the animateur is under 16 on the given date.
     *
     * <p>French labour law knows <b>three</b> regimes, not two: under 16,
     * 16-to-18, and adult. Treating a 15-year-old like a 17-year-old grants
     * them 2 h less daily rest (12 h instead of 14 h, art. L3164-1), 1 h more
     * daily work (8 h instead of 7 h, art. D4153-3) and 2 h more of legal
     * evening availability (night starts at 22:00 instead of 20:00, art.
     * L3163-1).</p>
     *
     * <p>Two further requirements for under-16s are <b>outside what the solver
     * can check</b> and stay a manual, documented responsibility: the labour
     * inspectorate authorisation required to employ them during school
     * holidays (art. L4153-3, D4153-2) and the "continuous rest of at least
     * half the total holiday period" condition of art. D4153-2.</p>
     *
     * <p>Like {@link #estMineurLe}, always derived from
     * {@link #dateNaissance}, never stored.</p>
     */
    public boolean estMoinsDe16AnsLe(LocalDate dateReference) {
        return dateReference != null
                && dateNaissance != null
                && Period.between(dateNaissance, dateReference).getYears() < 16;
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

    /** True when at least one typologie proposée by the stand is among the animateur's declared wishes. */
    public boolean aSouhaitePour(Stand stand) {
        return stand.getTypologiesProposees().stream().anyMatch(souhaits::contains);
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

    public Map<String, NiveauCompetence> getCompetences() {
        return competences;
    }

    public void setCompetences(Map<String, NiveauCompetence> competences) {
        this.competences = competences;
    }

    public Set<String> getSouhaits() {
        return souhaits;
    }

    public void setSouhaits(Set<String> souhaits) {
        this.souhaits = souhaits != null ? souhaits : new HashSet<>();
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
