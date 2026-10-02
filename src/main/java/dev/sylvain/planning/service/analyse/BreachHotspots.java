package dev.sylvain.planning.service.analyse;

import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.PastHorizon;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.service.diagnostic.MatchFacts;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * The few places where a rule in default bites hardest: the stand on the
 * timeslot of a day that gathers most of its breaches.
 *
 * <p>The pivot ({@link PivotEcarts}) counts each axis on its own — this stand,
 * this day — and cannot say which timeslot of which day on which stand, the
 * one place a reader opens to fix something. This crosses them, for the
 * three worst places only: « 554 correspondances » becomes « Stand 07,
 * Saturday evening », a link that opens that very seat on the Journée.</p>
 *
 * <p>A match counts once per place it names. A seat names its own stand and
 * timeslot; a match naming stands and timeslots apart pairs them when one
 * side is single (a stand and its timeslot, in practice) — crossing two
 * stands with two timeslots would invent places the match never named, so
 * the timeslots then count alone; a match naming a day only counts on that
 * day. Ids only, never names — the screen resolves the labels, and nothing
 * here travels further than the ids the pivot already sends.</p>
 *
 * <p>The frozen past of ADR 0044 is no place to fix: a seat already started,
 * a timeslot every seat of which has, a day already over are left out, and
 * « Où » points at what a gesture can still change.</p>
 */
public final class BreachHotspots {

    /** Three places: beyond that, the pivot says where the rest is. */
    public static final int LIMIT = 3;

    private BreachHotspots() {}

    /**
     * One place: a stand on a timeslot, or whatever part of it the breaches
     * name.
     *
     * @param standId   the stand, {@code null} when the breaches name none
     * @param date      the day, {@code null} when they name none
     * @param creneauId the timeslot, {@code null} when they name none — the
     *                  Journée opens the Siège panel on it when there is one
     * @param ecarts    how many breaches of the rule name this place
     */
    @Schema(requiredProperties = {"ecarts"})
    public record Hotspot(String standId, LocalDate date, Long creneauId, int ecarts) {}

    /** The {@link #LIMIT} places gathering most breaches, most first, with no past to leave out. */
    public static List<Hotspot> of(List<MatchFacts> matches) {
        return of(matches, creneau -> false, null);
    }

    /**
     * The {@link #LIMIT} places gathering most breaches still ahead, most
     * first; empty when the breaches name no such place.
     *
     * @param timeslotFrozen whether a timeslot is in the frozen past — every
     *                       seat of it started, as the diagnostic reads it; a
     *                       seat is read on its own {@link PosteAffectation#isPasse()}
     * @param horizon        the moment the plan was judged against; a day
     *                       before its date is over. {@code null}: none is
     */
    public static List<Hotspot> of(List<MatchFacts> matches, Predicate<Creneau> timeslotFrozen, PastHorizon horizon) {
        if (matches == null || matches.isEmpty()) {
            return List.of();
        }
        Map<Place, Integer> tally = new LinkedHashMap<>();
        for (MatchFacts match : matches) {
            for (Place place : places(match, timeslotFrozen, horizon)) {
                tally.merge(place, 1, Integer::sum);
            }
        }
        return tally.entrySet().stream()
                .map(entry -> new Hotspot(
                        entry.getKey().standId(),
                        entry.getKey().date(),
                        entry.getKey().creneauId(),
                        entry.getValue()))
                .sorted(Comparator.comparingInt(Hotspot::ecarts)
                        .reversed()
                        .thenComparing(Hotspot::date, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(Hotspot::standId, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(Hotspot::creneauId, Comparator.nullsLast(Comparator.naturalOrder())))
                .limit(LIMIT)
                .toList();
    }

    /** Tally key. */
    private record Place(String standId, LocalDate date, Long creneauId) {}

    /**
     * Every place one match names still ahead, without duplicates. A match
     * whose seats are all started names no place — its other facts are those
     * seats' stands and timeslots, already accounted for.
     */
    private static Set<Place> places(MatchFacts match, Predicate<Creneau> timeslotFrozen, PastHorizon horizon) {
        Named named = Named.of(match);
        if (named.seats) {
            return named.seatPlaces;
        }
        if (!named.creneaux.isEmpty()) {
            named.creneaux.removeIf(timeslotFrozen);
            return timeslotPlaces(named.stands, named.creneaux);
        }
        Set<LocalDate> dates = named.dates;
        if (!dates.isEmpty()) {
            dates.removeIf(date -> horizon != null && date.isBefore(horizon.today()));
            if (dates.isEmpty()) {
                // Every day it names is over: nothing left to fix there.
                return new LinkedHashSet<>();
            }
        }
        return dayPlaces(named.stands, dates);
    }

    /** The places a match naming timeslots points at, its stands paired with them when that is no guess. */
    private static Set<Place> timeslotPlaces(Set<Stand> stands, Set<Creneau> creneaux) {
        Set<Place> places = new LinkedHashSet<>();
        if (stands.isEmpty() || (stands.size() > 1 && creneaux.size() > 1)) {
            // No stand named, or which stand on which timeslot the match does not say.
            creneaux.forEach(creneau -> places.add(place(null, creneau)));
        } else {
            for (Stand stand : stands) {
                creneaux.forEach(creneau -> places.add(place(stand, creneau)));
            }
        }
        return places;
    }

    /** The same reading for a day: the stands count only when paired without a guess. */
    private static Set<Place> dayPlaces(Set<Stand> stands, Set<LocalDate> dates) {
        Set<Place> places = new LinkedHashSet<>();
        if (stands.isEmpty() || (stands.size() > 1 && dates.size() > 1)) {
            dates.forEach(date -> places.add(new Place(null, date, null)));
        } else if (dates.isEmpty()) {
            stands.forEach(stand -> places.add(new Place(stand.getId(), null, null)));
        } else {
            for (Stand stand : stands) {
                dates.forEach(date -> places.add(new Place(stand.getId(), date, null)));
            }
        }
        return places;
    }

    /** What one match names, sorted by kind: its seats still ahead, and its stands, timeslots and days. */
    private static final class Named {
        private final Set<Place> seatPlaces = new LinkedHashSet<>();
        private final Set<Stand> stands = new LinkedHashSet<>();
        private final Set<Creneau> creneaux = new LinkedHashSet<>();
        private final Set<LocalDate> dates = new LinkedHashSet<>();
        private boolean seats;

        static Named of(MatchFacts match) {
            Named named = new Named();
            for (Object fact : PivotEcarts.flatten(match.facts())) {
                switch (fact) {
                    case PosteAffectation poste
                    when poste.getCreneau() != null -> {
                        named.seats = true;
                        if (!poste.isPasse()) {
                            named.seatPlaces.add(place(poste.getStand(), poste.getCreneau()));
                        }
                    }
                    case Stand stand -> named.stands.add(stand);
                    case Creneau creneau -> named.creneaux.add(creneau);
                    case LocalDate date -> named.dates.add(date);
                    default -> {
                        // An animateur, an exception: a person, not a place — the pivot counts them.
                    }
                }
            }
            return named;
        }
    }

    private static Place place(Stand stand, Creneau creneau) {
        return new Place(stand == null ? null : stand.getId(), creneau.getDate(), creneau.getId());
    }
}
