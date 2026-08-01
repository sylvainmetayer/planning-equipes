package dev.sylvain.planning.domain;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.time.temporal.IsoFields;
import java.util.Collection;
import java.util.HashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

public class Creneau {

    private Long id;
    private int jour;
    private LocalDate date;
    private LocalTime heureDebut;
    private LocalTime heureFin;
    /** Empty = every stand is open on this timeslot (the default). */
    private Set<String> standsOuvertsIds = new HashSet<>();
    /** Planning ("groupe de créneaux") this slot belongs to; nullable defensively, always set once persisted. */
    private GroupeCreneau groupe;

    public Creneau() {
    }

    public Creneau(Long id, int jour, LocalDate date, LocalTime heureDebut, LocalTime heureFin) {
        this.id = id;
        this.jour = jour;
        this.date = date;
        this.heureDebut = heureDebut;
        this.heureFin = heureFin;
    }

    /**
     * Computes and assigns {@link #getJour()} for every créneau of the same
     * group: the number of calendar days between the group's earliest date
     * and each créneau's date, plus one. This guarantees two créneaux on
     * calendar-consecutive dates always get day numbers differing by exactly
     * one — even across a créneau-less gap day — which the night-rest legal
     * constraint relies on ({@code soir.getJour() + 1 == lendemain.getJour()}).
     */
    public static void assignerJours(Collection<Creneau> creneauxMemeGroupe) {
        LocalDate min = creneauxMemeGroupe.stream()
                .map(Creneau::getDate)
                .filter(Objects::nonNull)
                .min(LocalDate::compareTo)
                .orElse(null);
        if (min == null) {
            return;
        }
        for (Creneau creneau : creneauxMemeGroupe) {
            if (creneau.getDate() != null) {
                creneau.setJour((int) ChronoUnit.DAYS.between(min, creneau.getDate()) + 1);
            }
        }
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
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

    public Set<String> getStandsOuvertsIds() {
        return standsOuvertsIds;
    }

    public void setStandsOuvertsIds(Set<String> standsOuvertsIds) {
        this.standsOuvertsIds = standsOuvertsIds != null ? standsOuvertsIds : new HashSet<>();
    }

    public GroupeCreneau getGroupe() {
        return groupe;
    }

    public void setGroupe(GroupeCreneau groupe) {
        this.groupe = groupe;
    }

    /** True when the given stand is open on this timeslot (open-by-default). */
    public boolean estStandOuvert(String standId) {
        return standsOuvertsIds.isEmpty() || standsOuvertsIds.contains(standId);
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
     * ISO calendar week (e.g. {@code "2026-W28"}) the slot's {@link #date}
     * falls in. Used to group worked minutes per animateur and week, both for
     * reporting ({@code HeuresPlanningService}) and for the weekly max
     * working-time hard constraint.
     */
    public String semaineIso() {
        if (date == null) {
            return "?";
        }
        int annee = date.get(IsoFields.WEEK_BASED_YEAR);
        int semaine = date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
        return String.format(Locale.ROOT, "%d-W%02d", annee, semaine);
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
