package dev.sylvain.planning.service.referentiel;

import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.HorairesRecurrents;
import dev.sylvain.planning.domain.ModeHoraire;
import dev.sylvain.planning.domain.PlageStand;
import dev.sylvain.planning.domain.Stand;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The one reading of a stand's schedule: its stored {@link PlageStand}s, the
 * plages a consigne wrote included (ADR 0104, ADR 0125). There is no recurring
 * rule to expand and no consigne to look up: a stand's days are exactly the
 * plages it carries.
 *
 * <p>A day is read in one mode from the stand's own plages (see
 * {@link PlageStand}). The plages a consigne wrote on it lie <b>above</b>
 * them: its closures take their stretch out of the stand's own hours — the
 * band, and the créneaux it added — and its openings are added to what is
 * left, outside the band, whatever the stand's own plages say there. Such a
 * day is restated as explicit openings, each piece keeping its headcount, or
 * as a whole-day closure when nothing is left, so every reader of the
 * effective plages ({@code ProfilOuverture} and the screens built on it) still
 * meets one mode per day. A day without a consigne's plage, in one mode, is
 * left as stored.</p>
 *
 * <p>Pure and static, no CDI, no database, unit-testable on plain objects.
 * Production code reaches it through {@code StandService.resolve}
 * ({@code ConsigneCoucheStructurelleTest}).</p>
 */
public final class HoraireStandResolver {

    private static final int MINUTES_PAR_JOUR = 24 * 60;

    private HoraireStandResolver() {}

    /** Lays every stand's stored plages down as its effective ones; calling it twice yields the same result. */
    public static void apply(Collection<Stand> stands, Collection<Creneau> creneaux) {
        for (Stand stand : stands) {
            apply(stand, Set.of());
        }
    }

    /** The same for one stand; {@code dates} is kept for the callers that resolve a single stand over a known set of days. */
    public static void apply(Stand stand, Collection<LocalDate> dates) {
        if (stand != null) {
            stand.setPlagesEffectives(effective(stand.getPlages()));
        }
    }

    /**
     * The stands' <b>own</b> hours: their plages without the ones a consigne wrote — the day a consigne
     * departs from, and what the write-time warnings read a stand against.
     */
    public static void applyOwnHours(Collection<Stand> stands) {
        for (Stand stand : stands) {
            List<PlageStand> propres = stand.getPlages().stream()
                    .filter(plage -> !plage.isConsigne())
                    .toList();
            List<PlageStand> effectives = effective(propres);
            stand.setPlagesEffectives(effectives != null ? effectives : propres);
        }
    }

    /**
     * {@code plages} with every day that carries a consigne's plage, or both modes, restated as explicit
     * openings; {@code null} when there is none, which leaves the stand reading its stored list as it is.
     */
    public static List<PlageStand> effective(List<PlageStand> plages) {
        Map<LocalDate, List<PlageStand>> parJour = new LinkedHashMap<>();
        for (PlageStand plage : plages) {
            if (plage.getDate() != null) {
                parJour.computeIfAbsent(plage.getDate(), d -> new ArrayList<>()).add(plage);
            }
        }
        Set<LocalDate> aRedire = new HashSet<>();
        parJour.forEach((date, duJour) -> {
            if (duJour.stream().anyMatch(PlageStand::isConsigne)
                    || duJour.stream().map(PlageStand::getMode).distinct().count() > 1) {
                aRedire.add(date);
            }
        });
        if (aRedire.isEmpty()) {
            return null;
        }
        List<PlageStand> effectives = new ArrayList<>();
        for (PlageStand plage : plages) {
            if (plage.getDate() == null || !aRedire.contains(plage.getDate())) {
                effectives.add(plage);
            }
        }
        for (LocalDate date : aRedire) {
            effectives.addAll(restated(date, parJour.get(date)));
        }
        return effectives;
    }

    /** One stretch of a day, {@code [debut, fin)} in minutes, its headcount ({@code null} = minimum) and its reason. */
    private record Piece(int debut, int fin, Integer effectif, String motif) {}

    /**
     * One day: the stand's own open stretches minus the consigne's closures, then the consigne's openings,
     * as openings — or a whole-day closure when nothing is left open.
     */
    private static List<PlageStand> restated(LocalDate date, List<PlageStand> duJour) {
        List<PlageStand> propres =
                duJour.stream().filter(plage -> !plage.isConsigne()).toList();
        List<PlageStand> consigne =
                duJour.stream().filter(PlageStand::isConsigne).toList();
        List<int[]> fermeturesConsigne = consigne.stream()
                .filter(plage -> plage.getMode() == ModeHoraire.FERMETURE && plage.hasValidRange())
                .map(plage -> minutes(plage.getHeureDebut(), plage.getHeureFin()))
                .toList();
        List<Piece> ouverts = new ArrayList<>();
        for (Piece piece : ownOpenStretches(propres)) {
            for (int[] reste : subtract(new int[] {piece.debut(), piece.fin()}, fermeturesConsigne)) {
                ouverts.add(new Piece(reste[0], reste[1], piece.effectif(), piece.motif()));
            }
        }
        for (PlageStand ouverture : consigne) {
            if (ouverture.getMode() == ModeHoraire.OUVERTURE && ouverture.hasValidRange()) {
                int[] bornes = minutes(ouverture.getHeureDebut(), ouverture.getHeureFin());
                ouverts.add(new Piece(bornes[0], bornes[1], ouverture.getEffectif(), ouverture.getMotif()));
            }
        }
        List<PlageStand> restes = new ArrayList<>();
        for (Piece piece : ouverts) {
            restes.add(PlageStand.ouverture(
                    null, date, time(piece.debut()), endOrNull(piece.fin()), piece.motif(), piece.effectif()));
        }
        if (restes.isEmpty()) {
            String motif = duJour.stream()
                    .filter(plage -> plage.getMode() == ModeHoraire.FERMETURE)
                    .map(PlageStand::getMotif)
                    .filter(java.util.Objects::nonNull)
                    .findFirst()
                    .orElse(null);
            restes.add(PlageStand.fermeture(null, date, LocalTime.MIDNIGHT, null, motif));
        }
        return restes;
    }

    /**
     * The stand's own open stretches of a day: its openings when it states any (minus its own closures,
     * should both modes meet), the whole day minus its closures otherwise.
     */
    private static List<Piece> ownOpenStretches(List<PlageStand> propres) {
        List<int[]> fermetures = propres.stream()
                .filter(plage -> plage.getMode() == ModeHoraire.FERMETURE && plage.hasValidRange())
                .map(plage -> minutes(plage.getHeureDebut(), plage.getHeureFin()))
                .toList();
        List<PlageStand> ouvertures = propres.stream()
                .filter(plage -> plage.getMode() == ModeHoraire.OUVERTURE && plage.hasValidRange())
                .toList();
        List<Piece> pieces = new ArrayList<>();
        if (ouvertures.isEmpty()) {
            for (int[] reste : subtract(new int[] {0, MINUTES_PAR_JOUR}, fermetures)) {
                pieces.add(new Piece(reste[0], reste[1], null, null));
            }
            return pieces;
        }
        for (PlageStand ouverture : ouvertures) {
            for (int[] reste : subtract(minutes(ouverture.getHeureDebut(), ouverture.getHeureFin()), fermetures)) {
                pieces.add(new Piece(reste[0], reste[1], ouverture.getEffectif(), ouverture.getMotif()));
            }
        }
        return pieces;
    }

    /** {@code [debut, fin)} in minutes of the day; a {@code null} end, or {@code 00:00} as an end, is midnight. */
    public static int[] minutes(LocalTime debut, LocalTime fin) {
        int d = debut.toSecondOfDay() / 60;
        int f = fin == null ? MINUTES_PAR_JOUR : fin.toSecondOfDay() / 60;
        return new int[] {d, f == 0 ? MINUTES_PAR_JOUR : f};
    }

    /** True when the two stretches share a minute. */
    public static boolean overlap(int[] a, int[] b) {
        return a[0] < b[1] && b[0] < a[1];
    }

    /** {@code fenetre} minus every band: the stretches left open, in order. */
    public static List<int[]> subtract(int[] fenetre, List<int[]> bandes) {
        List<int[]> restes = new ArrayList<>();
        restes.add(fenetre);
        for (int[] bande : bandes) {
            List<int[]> suivants = new ArrayList<>();
            for (int[] reste : restes) {
                if (!overlap(reste, bande)) {
                    suivants.add(reste);
                    continue;
                }
                if (reste[0] < bande[0]) {
                    suivants.add(new int[] {reste[0], bande[0]});
                }
                if (bande[1] < reste[1]) {
                    suivants.add(new int[] {bande[1], reste[1]});
                }
            }
            restes = suivants;
        }
        return restes;
    }

    public static LocalTime time(int minutes) {
        return LocalTime.ofSecondOfDay((minutes % MINUTES_PAR_JOUR) * 60L);
    }

    /** Midnight as an end is written the way every plage writes it: {@code null}, « until closing ». */
    public static LocalTime endOrNull(int minutes) {
        return minutes >= MINUTES_PAR_JOUR ? null : time(minutes);
    }

    /**
     * Days a resolution has to cover: every date carrying a créneau, plus the
     * day after a créneau that crosses midnight — the only case
     * {@link Creneau#segmentsOuvertsMinutes(Stand)} reads a window dated later
     * than the slot's own date.
     */
    public static Set<LocalDate> datesConcernees(Collection<Creneau> creneaux) {
        return HorairesRecurrents.datesConcernees(creneaux);
    }

    /** What decided a given day of a stand's schedule. */
    public enum SourceHoraire {
        /** Nothing states anything about that day: open all day. */
        DEFAUT,
        /** Only plages the application unfolded from the stand's model state that day: its normal regime. */
        REGLE,
        /** The stand carries at least one plage somebody typed for that day. */
        EXCEPTION
    }

    /**
     * What governs {@code date} for {@code stand}. Reads the <b>stored</b>
     * plages without the ones a consigne wrote, so the answer stays the same
     * whether or not a consigne is laid on that day.
     */
    public static SourceHoraire sourceOfDay(Stand stand, LocalDate date) {
        if (stand == null || date == null) {
            return SourceHoraire.DEFAUT;
        }
        List<PlageStand> duJour = stand.getPlages().stream()
                .filter(plage -> date.equals(plage.getDate()) && !plage.isConsigne())
                .toList();
        if (duJour.isEmpty()) {
            return SourceHoraire.DEFAUT;
        }
        return duJour.stream().allMatch(PlageStand::isDepliee) ? SourceHoraire.REGLE : SourceHoraire.EXCEPTION;
    }
}
