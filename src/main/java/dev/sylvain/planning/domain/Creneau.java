package dev.sylvain.planning.domain;

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

    /**
     * Duration of the slot in minutes, handling slots that cross midnight
     * (e.g. 20:00 -> 00:00 counts as 240 minutes, not a negative value).
     */
    public int getDureeMinutes() {
        if (heureDebut == null || heureFin == null) {
            return 0;
        }
        int debut = heureDebut.toSecondOfDay();
        int fin = heureFin.toSecondOfDay();
        int seconds = fin > debut ? fin - debut : (24 * 3600 - debut) + fin;
        return seconds / 60;
    }

    /**
     * True when the slot overlaps the legal night window for minors
     * (20:00-06:00), including any slot that runs into or past midnight.
     * Used to forbid night work for minors.
     */
    public boolean chevaucheNuit() {
        if (heureDebut == null || heureFin == null) {
            return false;
        }
        boolean croiseMinuit = !heureFin.isAfter(heureDebut);
        if (croiseMinuit) {
            return true;
        }
        LocalTime debutNuit = LocalTime.of(20, 0);
        LocalTime finNuit = LocalTime.of(6, 0);
        return !heureDebut.isBefore(debutNuit) || !heureFin.isAfter(finNuit);
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
