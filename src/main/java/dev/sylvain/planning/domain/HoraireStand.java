package dev.sylvain.planning.domain;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * A <b>recurring opening (or closing) rule</b> of a {@link Stand}: a set of
 * {@link FenetreHoraire}s plus the days they apply to. One rule replaces as many
 * dated {@link OuvertureStand} / {@link IndisponibiliteStand} rows as there are
 * event days it covers — the stand open "10:00-12:00 then 14:00 to closing,
 * every day" is a single rule with two windows instead of twenty-four rows,
 * which is the whole reason this type exists.
 *
 * <p>Rules never replace the dated rows: they sit <b>above</b> them. For a given
 * stand and calendar day, {@code HoraireStandResolver} picks, in order:</p>
 * <ol>
 * <li>the day's dated {@link OuvertureStand}/{@link IndisponibiliteStand}
 * entries, if it has any — an exception always wins, and fully replaces the
 * rules for that day;</li>
 * <li>otherwise the rules covering that day, keeping only those of the highest
 * {@link #specificite()} and taking the union of their windows;</li>
 * <li>otherwise nothing: open all day, the historical default.</li>
 * </ol>
 *
 * <p>Resolving to a single {@link ModeHoraire} per day is what preserves the
 * pre-existing invariant that a stand's day is in exactly one of three states
 * (see {@link OuvertureStand}), and therefore what lets
 * {@link Creneau#segmentsOuvertsMinutes(Stand)}, the constraints and the solver
 * stay untouched by this whole mechanism.</p>
 *
 * <p>Consequence of layer 1 replacing (rather than subtracting from) layer 2: a
 * one-off closure on a day otherwise covered by an opening rule means re-stating
 * that day's opening windows as exceptions. Deliberate — the alternative is
 * mixing both modes on one day, the very ambiguity the three-state invariant
 * exists to rule out.</p>
 */
public class HoraireStand {

    private Long id;
    private ModeHoraire mode = ModeHoraire.OUVERTURE;
    /** Which days this rule applies to; {@code null} is read as {@link TypeJoursHoraire#TOUS}. */
    private TypeJoursHoraire jours = TypeJoursHoraire.TOUS;
    /** Only meaningful for {@link TypeJoursHoraire#JOURS_SEMAINE}. */
    private Set<DayOfWeek> joursSemaine = new TreeSet<>();
    /** Only meaningful for {@link TypeJoursHoraire#PLAGE}; bounds included. */
    private LocalDate dateDebut;
    private LocalDate dateFin;
    /** Only meaningful for {@link TypeJoursHoraire#DATES}. */
    private Set<LocalDate> dates = new TreeSet<>();
    private List<FenetreHoraire> fenetres = new ArrayList<>();
    /** Free-text reason, nullable — purely informative, never read by the solver. */
    private String motif;

    public HoraireStand() {
    }

    public HoraireStand(Long id, ModeHoraire mode, TypeJoursHoraire jours, List<FenetreHoraire> fenetres) {
        this.id = id;
        setMode(mode);
        setJours(jours);
        setFenetres(fenetres);
    }

    /** A rule applying to every event day — the shape of the overwhelming majority of them. */
    public static HoraireStand everyDay(ModeHoraire mode, FenetreHoraire... fenetres) {
        return new HoraireStand(null, mode, TypeJoursHoraire.TOUS, List.of(fenetres));
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public ModeHoraire getMode() {
        return mode;
    }

    public void setMode(ModeHoraire mode) {
        this.mode = mode != null ? mode : ModeHoraire.OUVERTURE;
    }

    public TypeJoursHoraire getJours() {
        return jours;
    }

    public void setJours(TypeJoursHoraire jours) {
        this.jours = jours != null ? jours : TypeJoursHoraire.TOUS;
    }

    public Set<DayOfWeek> getJoursSemaine() {
        return joursSemaine;
    }

    /** Normalised to a sorted set so a rule reads, round-trips and compares the same way everywhere. */
    public void setJoursSemaine(Set<DayOfWeek> joursSemaine) {
        this.joursSemaine = joursSemaine != null ? new TreeSet<>(joursSemaine) : new TreeSet<>();
    }

    public LocalDate getDateDebut() {
        return dateDebut;
    }

    public void setDateDebut(LocalDate dateDebut) {
        this.dateDebut = dateDebut;
    }

    public LocalDate getDateFin() {
        return dateFin;
    }

    public void setDateFin(LocalDate dateFin) {
        this.dateFin = dateFin;
    }

    public Set<LocalDate> getDates() {
        return dates;
    }

    public void setDates(Set<LocalDate> dates) {
        this.dates = dates != null ? new TreeSet<>(dates) : new TreeSet<>();
    }

    public List<FenetreHoraire> getFenetres() {
        return fenetres;
    }

    public void setFenetres(List<FenetreHoraire> fenetres) {
        this.fenetres = fenetres != null ? new ArrayList<>(fenetres) : new ArrayList<>();
    }

    public String getMotif() {
        return motif;
    }

    public void setMotif(String motif) {
        this.motif = motif;
    }

    /** How specific this rule's day selector is; the highest one covering a day wins. */
    public int specificite() {
        return jours.specificite();
    }

    /** True when this rule has something to say about {@code date}. */
    public boolean couvre(LocalDate date) {
        if (date == null) {
            return false;
        }
        return switch (jours) {
            case TOUS -> true;
            case JOURS_SEMAINE -> joursSemaine.contains(date.getDayOfWeek());
            case PLAGE -> (dateDebut == null || !date.isBefore(dateDebut))
                    && (dateFin == null || !date.isAfter(dateFin));
            case DATES -> dates.contains(date);
        };
    }

    /**
     * True when this rule and {@code autre} can apply to a same day, judged
     * from the selectors alone (no event calendar needed). Only meaningful
     * between two rules of the same {@link #getJours()} type, which is how
     * {@code ReferenceDataService} uses it to reject two rules of equal
     * specificity but opposite {@link ModeHoraire} — the one case the resolver
     * would otherwise have to arbitrate arbitrarily.
     */
    public boolean daysOverlapWith(HoraireStand autre) {
        if (autre == null || jours != autre.jours) {
            return false;
        }
        return switch (jours) {
            case TOUS -> true;
            case JOURS_SEMAINE -> joursSemaine.stream().anyMatch(autre.joursSemaine::contains);
            case PLAGE -> (dateDebut == null || autre.dateFin == null || !autre.dateFin.isBefore(dateDebut))
                    && (autre.dateDebut == null || dateFin == null || !dateFin.isBefore(autre.dateDebut));
            case DATES -> dates.stream().anyMatch(autre.dates::contains);
        };
    }

    /** Windows worth honouring: {@link FenetreHoraire#hasValidRange()} filters out half-entered ones defensively. */
    public List<FenetreHoraire> validFenetres() {
        return fenetres.stream().filter(FenetreHoraire::hasValidRange).toList();
    }

    // No equals/hashCode on purpose. Nothing compares or de-duplicates rules,
    // and neither candidate semantics would be safe if something started to:
    // by id, every unsaved rule would collapse into one (they all carry a null
    // id); by content, a rule would stop equalling itself across a save. The
    // compaction, which does need to group identical days, defines its own
    // value type for exactly that.

    @Override
    public String toString() {
        return mode + " " + jours + " " + fenetres;
    }
}
