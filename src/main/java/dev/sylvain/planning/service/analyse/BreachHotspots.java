package dev.sylvain.planning.service.analyse;

import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.service.diagnostic.MatchFacts;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 * timeslot; a match naming stands and timeslots apart crosses them (a stand
 * and its timeslot, in practice); a match naming a day only counts on that
 * day. Ids only, never names — the screen resolves the labels, and nothing
 * here travels further than the ids the pivot already sends.</p>
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

    /** The {@link #LIMIT} places gathering most breaches, most first; empty when the breaches name no place. */
    public static List<Hotspot> of(List<MatchFacts> matches) {
        if (matches == null || matches.isEmpty()) {
            return List.of();
        }
        Map<Place, Integer> tally = new LinkedHashMap<>();
        for (MatchFacts match : matches) {
            for (Place place : places(match)) {
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

    /** Every place one match names, without duplicates. */
    private static Set<Place> places(MatchFacts match) {
        Set<Place> places = new LinkedHashSet<>();
        List<Stand> stands = new ArrayList<>();
        List<Creneau> creneaux = new ArrayList<>();
        List<LocalDate> dates = new ArrayList<>();
        for (Object fact : PivotEcarts.flatten(match.facts())) {
            switch (fact) {
                case PosteAffectation poste
                when poste.getCreneau() != null -> places.add(place(poste.getStand(), poste.getCreneau()));
                case Stand stand -> stands.add(stand);
                case Creneau creneau -> creneaux.add(creneau);
                case LocalDate date -> dates.add(date);
                default -> {
                    // An animateur, an exception: a person, not a place — the pivot counts them.
                }
            }
        }
        if (!places.isEmpty()) {
            return places;
        }
        if (!creneaux.isEmpty()) {
            for (Creneau creneau : creneaux) {
                if (stands.isEmpty()) {
                    places.add(place(null, creneau));
                }
                for (Stand stand : stands) {
                    places.add(place(stand, creneau));
                }
            }
            return places;
        }
        for (Stand stand : stands) {
            places.add(new Place(stand.getId(), dates.isEmpty() ? null : dates.getFirst(), null));
        }
        if (stands.isEmpty()) {
            dates.forEach(date -> places.add(new Place(null, date, null)));
        }
        return places;
    }

    private static Place place(Stand stand, Creneau creneau) {
        return new Place(stand == null ? null : stand.getId(), creneau.getDate(), creneau.getId());
    }
}
