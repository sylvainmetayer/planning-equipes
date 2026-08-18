package dev.sylvain.planning.domain;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.time.temporal.IsoFields;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public class Creneau {

    private Long id;
    private int jour;
    private LocalDate date;
    private LocalTime heureDebut;
    private LocalTime heureFin;
    /** Planning ("groupe de créneaux") this slot belongs to; nullable defensively, always set once persisted. */
    private GroupeCreneau groupe;
    /**
     * Which time-staggered relay-grid variant this slot belongs to, when
     * {@code VacationGeneratorService} generated several instead of one
     * shared grid (see {@link ParametresDecoupage#getNombreFamillesDecalage()}).
     * Always {@code 0} for amplitude créneaux and for vacations generated
     * without staggering — poste generation only filters by famille when a
     * group actually contains more than one.
     */
    private int famille;

    /**
     * True when this slot is the short vacation covering a stand's internal
     * meal pause, generated under
     * {@link ParametresDecoupage.StrategieCouverturePendantPause#EFFECTIF_REDUIT}.
     * Poste generation then staffs it at half the stand's usual headcount
     * (see {@code PlanningService#construirePostes}).
     *
     * <p>Always {@code false} under the two other strategies — {@code RELEVE}
     * covers the pause at full headcount and {@code FERMETURE} generates no
     * covering vacation at all — and always {@code false} on amplitude
     * créneaux.</p>
     */
    private boolean couverturePause;

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

    public GroupeCreneau getGroupe() {
        return groupe;
    }

    public void setGroupe(GroupeCreneau groupe) {
        this.groupe = groupe;
    }

    public int getFamille() {
        return famille;
    }

    public void setFamille(int famille) {
        this.famille = famille;
    }

    public boolean isCouverturePause() {
        return couverturePause;
    }

    public void setCouverturePause(boolean couverturePause) {
        this.couverturePause = couverturePause;
    }

    /**
     * Open sub-intervals of this slot for {@code stand}, as
     * {@code [debutMinutes, finMinutes)} pairs measured from this slot's own
     * start ({@link #getHeureDebut()} on {@link #getDate()}).
     *
     * <p>A stand's day is in exactly one of three states — see
     * {@link OuvertureStand}'s javadoc:</p>
     * <ul>
     * <li><b>No {@link OuvertureStand} that day.</b> Open-by-default, same as
     * before {@link OuvertureStand} existed: a single pair
     * {@code [0, getDureeMinutes()]} unless an {@link IndisponibiliteStand}
     * that day carves out closed stretches (an empty list if it closes the
     * slot entirely, more than one pair if it sits strictly inside the slot).</li>
     * <li><b>At least one {@link OuvertureStand} that day.</b> Closed-by-default:
     * the returned pairs are exactly the (possibly merged, if two windows
     * overlap or touch) opening windows clamped to this slot — an empty list
     * if none of them overlap it at all. Any {@link IndisponibiliteStand} for
     * that same day is ignored (the two are mutually exclusive per day, see
     * {@link OuvertureStand}; {@code ReferenceDataService} enforces it at
     * write time — this method has no other way to arbitrate a conflict).</li>
     * </ul>
     *
     * <p>A window counts against this slot only when its {@code date} is this
     * slot's start date or — <b>for a slot that actually crosses midnight</b>
     * — the following calendar day, which covers a window falling after
     * midnight inside such a slot (e.g. a 20:00-02:00 slot closed 00:30-01:30
     * on the next day), mirroring how {@link #getDureeMinutes()} itself treats
     * a slot ending at or before its start as crossing into the next day. A
     * window that isn't {@code estValide()} (own {@code heureFin} before or
     * equal to its {@code heureDebut}) is ignored defensively — a window may
     * not itself cross midnight. A window with a {@code null}
     * {@code heureFin} runs to the end of this slot: that is what "open from
     * 14:00 until closing" means, whichever hour this particular day closes at.</p>
     *
     * <p>The windows read here are the <b>effective</b> ones
     * ({@link Stand#getOuverturesEffectives()}): the dated exceptions plus
     * whatever {@link HoraireStand} rules expand to, when a resolution has run.
     * With no rules in play they are the dated lists themselves, so this method
     * behaves exactly as it did before rules existed.</p>
     */
    public List<int[]> segmentsOuvertsMinutes(Stand stand) {
        int dureeMinutes = getDureeMinutes();
        if (dureeMinutes <= 0) {
            return List.of();
        }
        int dureeSecondes = dureeMinutes * 60;
        if (jourEnModeOuverture(stand)) {
            // The day has at least one OuvertureStand: closed-by-default. This
            // slot's open segments are exactly whichever of that day's opening
            // windows overlap it — possibly none at all, i.e. this slot is
            // fully closed even though the stand does open elsewhere that day.
            List<int[]> ouverturesSecondes = ouverturesEnSecondes(stand, dureeSecondes);
            return ouverturesSecondes.isEmpty() ? List.of() : fusionnerSegments(ouverturesSecondes);
        }
        List<int[]> fermeturesSecondes = fermeturesEnSecondes(stand, dureeSecondes);
        if (fermeturesSecondes.isEmpty()) {
            return List.of(new int[] {0, dureeMinutes});
        }
        fermeturesSecondes.sort(Comparator.comparingInt(f -> f[0]));
        List<int[]> ouverts = new ArrayList<>();
        int curseur = 0;
        for (int[] fermeture : fermeturesSecondes) {
            int debut = Math.max(curseur, fermeture[0]);
            if (debut > curseur) {
                ouverts.add(new int[] {curseur / 60, debut / 60});
            }
            curseur = Math.max(curseur, fermeture[1]);
        }
        if (curseur < dureeSecondes) {
            ouverts.add(new int[] {curseur / 60, dureeMinutes});
        }
        return ouverts;
    }

    /** Closure windows of {@code stand} overlapping this slot, clamped to {@code [0, dureeSecondes]} and expressed in seconds since this slot's start. */
    private List<int[]> fermeturesEnSecondes(Stand stand, int dureeSecondes) {
        if (stand == null || stand.getIndisponibilitesEffectives().isEmpty() || heureDebut == null || date == null) {
            return List.of();
        }
        int debutSlotSecondes = heureDebut.toSecondOfDay();
        List<int[]> fermetures = new ArrayList<>();
        for (IndisponibiliteStand indispo : stand.getIndisponibilitesEffectives()) {
            if (!indispo.estValide()) {
                continue;
            }
            int decalageJour = decalageJourFenetre(indispo.getDate());
            if (decalageJour < 0) {
                continue;
            }
            int indispoDebut = decalageJour + indispo.getHeureDebut().toSecondOfDay() - debutSlotSecondes;
            int debut = Math.max(0, indispoDebut);
            int fin = finFenetreEnSecondes(indispo.getHeureFin(), decalageJour, debutSlotSecondes, dureeSecondes);
            if (fin > debut) {
                fermetures.add(new int[] {debut, fin});
            }
        }
        return fermetures;
    }

    /**
     * Offset in seconds to add to a window dated {@code dateFenetre} to place
     * it on this slot's timeline, or {@code -1} when that date has no bearing
     * on this slot: {@code 0} for this slot's own date, one day for the next
     * one — <b>only</b> when this slot really crosses midnight, since a window
     * dated the day after a 10:00-20:00 slot cannot possibly overlap it.
     */
    private int decalageJourFenetre(LocalDate dateFenetre) {
        if (dateFenetre.equals(date)) {
            return 0;
        }
        if (traverseMinuit() && dateFenetre.equals(date.plusDays(1))) {
            return SECONDES_PAR_JOUR;
        }
        return -1;
    }

    /**
     * End of a window on this slot's timeline, clamped to the slot: a
     * {@code null} {@code heureFin} means "until closing time" and therefore
     * lands exactly on this slot's end, whatever hour that is.
     */
    private static int finFenetreEnSecondes(LocalTime heureFin, int decalageJour, int debutSlotSecondes,
            int dureeSecondes) {
        if (heureFin == null) {
            return dureeSecondes;
        }
        return Math.min(dureeSecondes, decalageJour + heureFin.toSecondOfDay() - debutSlotSecondes);
    }

    /** True when this slot runs past midnight, i.e. its end is at or before its start. */
    private boolean traverseMinuit() {
        return heureDebut != null && heureFin != null && !heureFin.isAfter(heureDebut);
    }

    /**
     * True when {@code stand} has at least one valid {@link OuvertureStand}
     * bearing on this slot's day — regardless of whether its time window
     * actually overlaps this slot. Deciding "closed-by-default" mode on this
     * alone (rather than on whether {@link #ouverturesEnSecondes} came back
     * non-empty) is what makes a slot that happens to fall entirely outside
     * every opening window that day come out fully closed, instead of wrongly
     * falling back to open-by-default.
     *
     * <p>"Bearing on this slot's day" goes through
     * {@link #decalageJourFenetre}, so the next day only counts for a slot that
     * genuinely crosses midnight. Reading the next day unconditionally — as
     * this did before recurring rules existed — let an opening dated the day
     * <i>after</i> a 10:00-20:00 slot flip that slot to closed-by-default, and
     * so silently close a stand that a same-day closure exception meant to shut
     * for two hours only. Harmless while openings were rare and hand-dated; not
     * once a rule expands one onto every festival day.</p>
     */
    private boolean jourEnModeOuverture(Stand stand) {
        if (stand == null || stand.getOuverturesEffectives().isEmpty() || date == null) {
            return false;
        }
        for (OuvertureStand ouverture : stand.getOuverturesEffectives()) {
            if (ouverture.estValide() && decalageJourFenetre(ouverture.getDate()) >= 0) {
                return true;
            }
        }
        return false;
    }

    /** Opening windows of {@code stand} overlapping this slot, clamped to {@code [0, dureeSecondes]} and expressed in seconds since this slot's start. */
    private List<int[]> ouverturesEnSecondes(Stand stand, int dureeSecondes) {
        if (stand == null || stand.getOuverturesEffectives().isEmpty() || heureDebut == null || date == null) {
            return List.of();
        }
        int debutSlotSecondes = heureDebut.toSecondOfDay();
        List<int[]> ouvertures = new ArrayList<>();
        for (OuvertureStand ouverture : stand.getOuverturesEffectives()) {
            if (!ouverture.estValide()) {
                continue;
            }
            int decalageJour = decalageJourFenetre(ouverture.getDate());
            if (decalageJour < 0) {
                continue;
            }
            int ouvertureDebut = decalageJour + ouverture.getHeureDebut().toSecondOfDay() - debutSlotSecondes;
            int debut = Math.max(0, ouvertureDebut);
            int fin = finFenetreEnSecondes(ouverture.getHeureFin(), decalageJour, debutSlotSecondes, dureeSecondes);
            if (fin > debut) {
                ouvertures.add(new int[] {debut, fin});
            }
        }
        return ouvertures;
    }

    /** Merges overlapping/touching second-granularity windows and converts their boundaries to minutes. */
    private static List<int[]> fusionnerSegments(List<int[]> segmentsSecondes) {
        segmentsSecondes.sort(Comparator.comparingInt(s -> s[0]));
        List<int[]> fusionnes = new ArrayList<>();
        for (int[] segment : segmentsSecondes) {
            if (!fusionnes.isEmpty() && segment[0] <= fusionnes.get(fusionnes.size() - 1)[1]) {
                int[] dernier = fusionnes.get(fusionnes.size() - 1);
                dernier[1] = Math.max(dernier[1], segment[1]);
            } else {
                fusionnes.add(segment.clone());
            }
        }
        List<int[]> minutes = new ArrayList<>();
        for (int[] segment : fusionnes) {
            minutes.add(new int[] {segment[0] / 60, segment[1] / 60});
        }
        return minutes;
    }

    /** True when at least part of this slot is open for {@code stand} (open-by-default). */
    public boolean estStandOuvert(Stand stand) {
        return !segmentsOuvertsMinutes(stand).isEmpty();
    }

    /** True when {@code stand} is closed for this slot's entire duration. */
    public boolean estStandFermeIntegralement(Stand stand) {
        return getDureeMinutes() > 0 && segmentsOuvertsMinutes(stand).isEmpty();
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
