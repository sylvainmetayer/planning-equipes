package dev.sylvain.planning.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * What the recurring rules of 3.x (a stand's {@code horaires}) mean, kept as a
 * pure function so that they can be turned into {@link PlageStand}s — by the
 * Flyway migration that converts a database, by the scenario file link that
 * converts a 3.x file, by the assistant tools that still take a rule, and by
 * the unfolding of a stand's <b>model</b> on the days an edition gains — with
 * <b>one</b> reading of the rules. A rule is the stand's model while its
 * edition has no dates, then it is unfolded into plages (ADR 0104).
 *
 * <p>Per calendar day: a dated exception wins outright; otherwise the covering
 * rules of the highest specificity ({@link TypeJoursHoraire#specificite()}) do,
 * their windows being merged; otherwise the day is left alone — open by
 * default, <b>unless</b> the rules declare openings somewhere, in which case a
 * day no rule names is a day the stand does not open (a closure from midnight,
 * {@link #MOTIF_JOUR_NON_DECLARE}). Two top-specificity rules disagreeing on
 * the mode resolve to {@link ModeHoraire#OUVERTURE}, the more restrictive
 * reading.</p>
 *
 * <p>This is a copy of the resolver 3.x ran at every read, frozen on purpose:
 * the migration test replays it against the digest of the resolutions that
 * 3.x computed on every shipped scenario.</p>
 */
public final class HorairesRecurrents {

    /** Why a day nobody declared came out closed, carried onto the plage generated for it. */
    public static final String MOTIF_JOUR_NON_DECLARE = "Jour non déclaré par le stand";

    private HorairesRecurrents() {}

    /**
     * One recurring rule of 3.x: a day selector and the windows it applies. Read from JSON by the
     * 3.x editor's bridge ({@code Stand#setHoraires}), whose rules still carry an {@code id}.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @Schema(name = "HoraireStand")
    public record Regle(
            ModeHoraire mode,
            TypeJoursHoraire jours,
            Set<DayOfWeek> joursSemaine,
            LocalDate dateDebut,
            LocalDate dateFin,
            Set<LocalDate> dates,
            List<FenetreHoraire> fenetres,
            String motif,
            Long id) {

        /** A rule not stored yet. */
        public Regle(
                ModeHoraire mode,
                TypeJoursHoraire jours,
                Set<DayOfWeek> joursSemaine,
                LocalDate dateDebut,
                LocalDate dateFin,
                Set<LocalDate> dates,
                List<FenetreHoraire> fenetres,
                String motif) {
            this(mode, jours, joursSemaine, dateDebut, dateFin, dates, fenetres, motif, null);
        }

        public Regle {
            mode = mode != null ? mode : ModeHoraire.OUVERTURE;
            jours = jours != null ? jours : TypeJoursHoraire.TOUS;
            joursSemaine = joursSemaine != null ? new TreeSet<>(joursSemaine) : new TreeSet<>();
            dates = dates != null ? new TreeSet<>(dates) : new TreeSet<>();
            fenetres = fenetres != null ? List.copyOf(fenetres) : List.of();
        }

        /** A rule applying to every event day — the shape of the overwhelming majority of them. */
        public static Regle everyDay(ModeHoraire mode, FenetreHoraire... fenetres) {
            return new Regle(mode, TypeJoursHoraire.TOUS, null, null, null, null, List.of(fenetres), null);
        }

        /** The same rule without its row id: what two rules are compared on. */
        public Regle withoutId() {
            return new Regle(mode, jours, joursSemaine, dateDebut, dateFin, dates, fenetres, motif, null);
        }

        /** True when this rule has something to say about {@code date}. */
        public boolean couvre(LocalDate date) {
            if (date == null) {
                return false;
            }
            return switch (jours) {
                case TOUS -> true;
                case JOURS_SEMAINE -> joursSemaine.contains(date.getDayOfWeek());
                case PLAGE ->
                    (dateDebut == null || !date.isBefore(dateDebut)) && (dateFin == null || !date.isAfter(dateFin));
                case DATES -> dates.contains(date);
            };
        }

        /** Windows worth honouring: half-entered ones are filtered out defensively. */
        List<FenetreHoraire> validFenetres() {
            return fenetres.stream().filter(FenetreHoraire::hasValidRange).toList();
        }
    }

    /**
     * Days a resolution has to cover: every date carrying a créneau, plus the
     * day after a créneau that crosses midnight — the only case a window dated
     * later than the slot's own date is ever read.
     */
    public static Set<LocalDate> datesConcernees(Collection<Creneau> creneaux) {
        Set<LocalDate> dates = new TreeSet<>();
        for (Creneau creneau : creneaux) {
            dates.addAll(datesConcernees(creneau.getDate(), creneau.getHeureDebut(), creneau.getHeureFin()));
        }
        return dates;
    }

    /** The dates one créneau makes a resolution cover: its own, and the next when it crosses midnight. */
    public static Set<LocalDate> datesConcernees(LocalDate date, LocalTime debut, LocalTime fin) {
        if (date == null) {
            return Set.of();
        }
        Set<LocalDate> dates = new LinkedHashSet<>();
        dates.add(date);
        if (debut != null && fin != null && !fin.isAfter(debut)) {
            dates.add(date.plusDays(1));
        }
        return dates;
    }

    /**
     * The plages the rules stand for over {@code jours}, <b>after</b> the dated
     * {@code exceptions}: an exception day keeps exactly its own plages and the
     * rules say nothing about it. The input list is not modified.
     */
    public static List<PlageStand> expand(
            List<Regle> regles, List<PlageStand> exceptions, Collection<LocalDate> jours) {
        List<PlageStand> plages = new ArrayList<>(exceptions);
        if (regles.isEmpty()) {
            return plages;
        }
        Set<LocalDate> joursAvecException = new LinkedHashSet<>();
        exceptions.stream().map(PlageStand::getDate).filter(Objects::nonNull).forEach(joursAvecException::add);
        boolean declaresOpenings = declaresOpenings(regles);
        for (LocalDate date : new TreeSet<>(jours)) {
            if (!joursAvecException.contains(date)) {
                expandDay(regles, date, declaresOpenings, plages);
            }
        }
        return plages;
    }

    private static void expandDay(List<Regle> regles, LocalDate date, boolean declaresOpenings, List<PlageStand> out) {
        Jour jour = resolveDay(regles, date);
        if (jour == null) {
            if (declaresOpenings) {
                out.add(PlageStand.fermeture(null, date, LocalTime.MIDNIGHT, null, MOTIF_JOUR_NON_DECLARE));
            }
            return;
        }
        for (FenetreHoraire fenetre : jour.fenetres()) {
            if (jour.mode() == ModeHoraire.OUVERTURE) {
                out.add(PlageStand.ouverture(
                        null,
                        date,
                        fenetre.getHeureDebut(),
                        fenetre.getHeureFin(),
                        jour.motif(),
                        fenetre.getEffectif()));
            } else {
                out.add(PlageStand.fermeture(null, date, fenetre.getHeureDebut(), fenetre.getHeureFin(), jour.motif()));
            }
        }
    }

    /**
     * True when the rules state, somewhere, hours at which the stand opens —
     * which makes them a schedule rather than a list of exceptions to being
     * always open, and a day none of them names a day the stand is shut.
     */
    static boolean declaresOpenings(List<Regle> regles) {
        return regles.stream()
                .anyMatch(regle -> regle.mode() == ModeHoraire.OUVERTURE
                        && !regle.validFenetres().isEmpty());
    }

    private record Jour(ModeHoraire mode, List<FenetreHoraire> fenetres, String motif) {}

    private static Jour resolveDay(List<Regle> regles, LocalDate date) {
        List<Regle> couvrantes = regles.stream()
                .filter(regle -> regle.couvre(date))
                .filter(regle -> !regle.validFenetres().isEmpty())
                .toList();
        if (couvrantes.isEmpty()) {
            return null;
        }
        int specificiteMax = couvrantes.stream()
                .mapToInt(regle -> regle.jours().specificite())
                .max()
                .orElseThrow();
        List<Regle> gagnantes = couvrantes.stream()
                .filter(regle -> regle.jours().specificite() == specificiteMax)
                .toList();
        ModeHoraire mode = gagnantes.stream().anyMatch(regle -> regle.mode() == ModeHoraire.OUVERTURE)
                ? ModeHoraire.OUVERTURE
                : ModeHoraire.FERMETURE;
        List<Regle> retenues =
                gagnantes.stream().filter(regle -> regle.mode() == mode).toList();
        List<FenetreHoraire> fenetres = retenues.stream()
                .flatMap(regle -> regle.validFenetres().stream())
                .distinct()
                .sorted(Comparator.comparing(FenetreHoraire::getHeureDebut))
                .toList();
        String motif = retenues.stream()
                .map(Regle::motif)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
        return new Jour(mode, fenetres, motif);
    }
}
