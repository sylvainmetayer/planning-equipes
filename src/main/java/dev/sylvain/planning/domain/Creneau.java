package dev.sylvain.planning.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.time.temporal.IsoFields;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public class Creneau {

    private Long id;
    private int jour;
    private LocalDate date;
    private LocalTime heureDebut;
    private LocalTime heureFin;
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
     * {@link ParametresDecoupage.PauseCoverageStrategy#EFFECTIF_REDUIT}.
     * Poste generation then staffs it at half the stand's usual headcount
     * (see {@code ProblemBuilder#buildPostes}).
     *
     * <p>Always {@code false} under the two other strategies — {@code RELEVE}
     * covers the pause at full headcount and {@code FERMETURE} generates no
     * covering vacation at all — and always {@code false} on amplitude
     * créneaux.</p>
     */
    private boolean couverturePause;

    /**
     * When this row was last written (issue #362), read from the referential
     * and echoed back by a form on save: a write carrying a value older than
     * the row's is refused, see {@code ConcurrentModificationGuard}. {@code null}
     * on an object that never went through the database, and on a write that
     * deliberately carries no precondition (import, MCP merge, a client that
     * chose to overwrite).
     */
    private Instant modifieLe;

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
     * Computes and assigns {@link #getJour()} for every créneau of the
     * edition: the number of calendar days between the earliest date and each
     * créneau's date, plus one. This guarantees two créneaux on
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

    public Instant getModifieLe() {
        return modifieLe;
    }

    public void setModifieLe(Instant modifieLe) {
        this.modifieLe = modifieLe;
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
     * start ({@link #getHeureDebut()} on {@link #getDate()}). The rules —
     * open-by-default or closed-by-default, midnight, "until closing" — are
     * {@link ProfilOuverture}'s, which this delegates to.
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
     * window that isn't {@code hasValidRange()} (own {@code heureFin} before or
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
        return ProfilOuverture.of(this, stand).segmentsMinutes();
    }

    /**
     * One open sub-interval of this slot together with the number of seats to
     * staff on it, in minutes from this slot's start.
     *
     * <p>{@code effectif} is already resolved: a window that named one carries
     * that value, a window that did not carries the stand's
     * {@link Stand#getEffectifMin()}. It is <b>not</b> floored to 1 — that rule
     * belongs to seat generation, {@link #siegesSegment(int)}, which is the
     * single place that decides a stand open with nobody declared still gets
     * one seat.</p>
     */
    public record SegmentOuvert(int debutMinutes, int finMinutes, int effectif) {
    }

    /**
     * Seats generated on one open segment of this slot for the given resolved
     * effectif: at least one — a stand nobody declared a headcount for still
     * needs somebody, so closing it stays an explicit decision rather than a
     * side effect of an unset {@code effectifMin} — and half, rounded up, on a
     * break-covering shift ({@link #isCouverturePause()}), where the stand runs
     * at reduced staffing and a stand held by a single person keeps that person
     * rather than closing.
     *
     * <p>Poste generation, the feasibility analysis and the premium-continuity
     * rule all read this one method, so what the solver must fill and what the
     * screens announce can never be two different numbers.</p>
     */
    public int siegesSegment(int effectifSegment) {
        int effectif = Math.max(1, effectifSegment);
        return couverturePause ? (effectif + 1) / 2 : effectif;
    }

    /**
     * The most seats this slot asks the stand to staff at any one instant: the
     * largest {@link #siegesSegment(int)} over its open segments, zero when the
     * stand is closed on it. Segments of one slot never overlap, so the demand
     * at an instant is the one of the segment covering it, never a sum.
     */
    public int siegesSimultanes(Stand stand) {
        int sieges = 0;
        for (SegmentOuvert segment : segmentsOuverts(stand)) {
            sieges = Math.max(sieges, siegesSegment(segment.effectif()));
        }
        return sieges;
    }


    /**
     * Same open sub-intervals as {@link #segmentsOuvertsMinutes(Stand)}, each
     * carrying the headcount that applies to it.
     *
     * <p>This is the primary computation; {@code segmentsOuvertsMinutes} is a
     * projection of it. It exists because a stand's staffing can vary during
     * the day — 4 people in the morning, 5 in the evening — which
     * {@link FenetreHoraire#getEffectif()} expresses per window. A slot spanning
     * two windows of different effectifs therefore comes back as <b>two</b>
     * segments, and poste generation emits the right number of seats on each.</p>
     *
     * <p>Where two opening windows overlap, the overlap takes the <b>higher</b>
     * of the two effectifs: overlapping windows are two statements of a need
     * over the same minutes, and satisfying the larger satisfies both. Adjacent
     * stretches that end up with the same effectif are merged back, so a stand
     * whose windows carry no effectif at all — the overwhelming majority —
     * produces exactly the single merged segment it always did.</p>
     */
    public List<SegmentOuvert> segmentsOuverts(Stand stand) {
        return ProfilOuverture.of(this, stand).segments();
    }

    /** True when at least part of this slot is open for {@code stand} (open-by-default). */
    public boolean isStandOpen(Stand stand) {
        return !segmentsOuvertsMinutes(stand).isEmpty();
    }

    /** True when {@code stand} is closed for this slot's entire duration. */
    public boolean isStandFullyClosed(Stand stand) {
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
     * reporting ({@code PlanningHoursService}) and for the weekly max
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
     * the law but needlessly shrinks the pool on an event evening.</p>
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
