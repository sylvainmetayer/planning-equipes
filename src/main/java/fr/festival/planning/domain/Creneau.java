package fr.festival.planning.domain;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Objects;

public class Creneau {

    private String id;
    private int jour;
    private LocalDate date;
    private LocalTime heureDebut;
    private LocalTime heureFin;

    public Creneau() {
    }

    public Creneau(String id, int jour, LocalDate date, LocalTime heureDebut, LocalTime heureFin) {
        this.id = id;
        this.jour = jour;
        this.date = date;
        this.heureDebut = heureDebut;
        this.heureFin = heureFin;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public int getJour() {
        return jour;
    }

    public void setJour(int jour) {
        this.jour = jour;
    }

    public LocalDate getDate() {
        return date;
    }

    public void setDate(LocalDate date) {
        this.date = date;
    }

    public LocalTime getHeureDebut() {
        return heureDebut;
    }

    public void setHeureDebut(LocalTime heureDebut) {
        this.heureDebut = heureDebut;
    }

    public LocalTime getHeureFin() {
        return heureFin;
    }

    public void setHeureFin(LocalTime heureFin) {
        this.heureFin = heureFin;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Creneau creneau)) {
            return false;
        }
        return Objects.equals(id, creneau.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
