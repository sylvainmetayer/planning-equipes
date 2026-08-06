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
     * working-time hard constraints (art. L3121-20 for adults, L3162-1 for
     * minors).
     *
     * <p><b>Attribution convention:</b> a slot crossing midnight is attributed
     * <i>in full</i> to the ISO week of its start date. A Sunday 20:00-00:00
     * slot therefore counts towards the week that ends, not the one that
     * begins. Deliberate simplification, conservative as long as night slots
     * stay short.</p>
     */
    public String semaineIso() {
        if (date == null) {
            return "?";
        }
        int annee = date.get(IsoFields.WEEK_BASED_YEAR);
        int semaine = date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
        return String.format(Locale.ROOT, "%d-W%02d", annee, semaine);
    }

    /** Night ends at 06:00 for every minor, whatever their age (art. L3163-1). */
    public static final LocalTime FIN_NUIT = LocalTime.of(6, 0);

    /**
     * Start of the legal night for a minor under 16: 20:00 (Code du travail
     * art. L3163-1, <i>« tout travail entre 20 heures et 6 heures »</i>).
     */
    public static final LocalTime DEBUT_NUIT_MOINS_DE_16_ANS = LocalTime.of(20, 0);

    /**
     * Start of the legal night for a minor aged 16 to 18: 22:00 (Code du
     * travail art. L3163-1, <i>« tout travail entre 22 heures et 6 heures »</i>).
     */
    public static final LocalTime DEBUT_NUIT_16_A_18_ANS = LocalTime.of(22, 0);

    /**
     * True when the slot overlaps the legal night window of a minor under 16,
     * i.e. 20:00-06:00 (art. L3163-1). Kept as the default because it is the
     * most protective of the two windows; the age-aware form is
     * {@link #chevaucheNuit(LocalTime)}.
     */
    public boolean chevaucheNuit() {
        return chevaucheNuit(DEBUT_NUIT_MOINS_DE_16_ANS);
    }

    /**
     * True when the slot overlaps the night window {@code [debutNuit, 06:00)},
     * whether or not the slot itself crosses midnight.
     *
     * <p>Code du travail art. <b>L3163-1</b> defines night work for young
     * workers as <i>« tout travail entre 22 heures et 6 heures »</i> for the
     * 16-to-18 bracket and <i>« tout travail entre 20 heures et 6 heures »</i>
     * for those under 16 — hence the parameter rather than a hard-coded 20:00.
     * Applying the 20:00 window to 16-to-18-year-olds is more protective than
     * the law but needlessly shrinks the pool on a festival evening.</p>
     *
     * <p>Computed as a genuine interval overlap, in seconds-since-the-slot's-
     * start-of-day: a slot ending at 00:00 is normalised to 24:00, and the
     * night is tested twice, once as the evening window {@code [debutNuit,
     * 24:00+06:00)} and once as the early-morning window {@code [00:00,
     * 06:00)} of the start day. The previous formulation
     * ({@code !heureDebut.isBefore(debutNuit) || !heureFin.isAfter(finNuit)})
     * missed every slot merely straddling the boundary — 19:00-21:00 was
     * reported as not overlapping the night.</p>
     */
    public boolean chevaucheNuit(LocalTime debutNuit) {
        if (heureDebut == null || heureFin == null) {
            return false;
        }
        int debut = heureDebut.toSecondOfDay();
        int fin = heureFin.toSecondOfDay();
        if (fin <= debut) {
            fin += SECONDES_PAR_JOUR;
        }
        int finNuit = FIN_NUIT.toSecondOfDay();
        return chevauchent(debut, fin, debutNuit.toSecondOfDay(), SECONDES_PAR_JOUR + finNuit)
                || chevauchent(debut, fin, 0, finNuit);
    }

    private static final int SECONDES_PAR_JOUR = 24 * 3600;

    private static boolean chevauchent(int debutA, int finA, int debutB, int finB) {
        return debutA < finB && debutB < finA;
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
