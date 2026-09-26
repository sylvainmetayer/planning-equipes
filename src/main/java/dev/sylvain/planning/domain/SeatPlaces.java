package dev.sylvain.planning.domain;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * How a report counts the seats of a plan once a seat was split on the day
 * (ADR 0066): two rows, one place.
 *
 * <p>A seat split at « now » becomes its origin — cut short, kept by whoever
 * held it — and a continuation naming it. Every report that <b>counts
 * places</b> (seats, staffed seats, seats to fill) counts the place once, on
 * its <b>current part</b>: the last of its chain, the one no other seat
 * continues. An origin continued by an empty remainder is a place to fill,
 * one continued by somebody is a staffed place. Every report that <b>sums
 * hours</b> sums every part on its own window, which is what the history
 * says: 09:00-09:20 for one person, 09:20-12:00 for the other, three hours of
 * seat between them. A narrowed seat names no origin and is continued by
 * nobody: it is one place, on its narrowed window.</p>
 *
 * <p>Pure and static, over the domain seat or any row carrying the two ids
 * (a snapshot line), so the reports and their tests read one rule.</p>
 */
public final class SeatPlaces {

    private SeatPlaces() {}

    /** The ids of the seats another seat of {@code postes} continues: the parts that are no longer current. */
    public static Set<String> continuedIds(Collection<PosteAffectation> postes) {
        return continuedIds(postes, PosteAffectation::getSuiteDe);
    }

    /** Same, over any row: {@code suiteDe} reads the id of the seat a row continues. */
    public static <T> Set<String> continuedIds(Collection<T> rows, Function<T, String> suiteDe) {
        Set<String> continues = new HashSet<>();
        if (rows == null) {
            return continues;
        }
        for (T row : rows) {
            String origine = suiteDe.apply(row);
            if (origine != null) {
                continues.add(origine);
            }
        }
        return continues;
    }

    /** The seats counted as places: every seat but those a later part continues. */
    public static List<PosteAffectation> places(Collection<PosteAffectation> postes) {
        if (postes == null) {
            return List.of();
        }
        Set<String> continues = continuedIds(postes);
        if (continues.isEmpty()) {
            return List.copyOf(postes);
        }
        return postes.stream()
                .filter(poste -> !continues.contains(poste.getId()))
                .toList();
    }

    /**
     * The seats that continue a seat <b>the same person</b> held: the rest of
     * one stretch of theirs, not a seat more. A person counted seat by seat
     * — their number of seats, the schedule a publication compares — counts
     * such a continuation with its origin. Empty in the common case, where
     * the remainder went to somebody else or to nobody.
     */
    public static Set<String> sameHolderContinuations(Collection<PosteAffectation> postes) {
        Set<String> ids = new HashSet<>();
        if (postes == null) {
            return ids;
        }
        Map<String, PosteAffectation> byId = byId(postes);
        for (PosteAffectation poste : postes) {
            PosteAffectation origine = poste.getSuiteDe() == null ? null : byId.get(poste.getSuiteDe());
            if (origine != null && sameHolder(origine, poste)) {
                ids.add(poste.getId());
            }
        }
        return ids;
    }

    /** Each seat's continuation, by the id of the seat it continues. */
    public static Map<String, PosteAffectation> continuationByOrigin(Collection<PosteAffectation> postes) {
        Map<String, PosteAffectation> suites = new HashMap<>();
        if (postes != null) {
            for (PosteAffectation poste : postes) {
                if (poste.getSuiteDe() != null) {
                    suites.putIfAbsent(poste.getSuiteDe(), poste);
                }
            }
        }
        return suites;
    }

    /** Whether two seats are held by one and the same person. */
    public static boolean sameHolder(PosteAffectation une, PosteAffectation autre) {
        return une.getAnimateur() != null
                && autre.getAnimateur() != null
                && Objects.equals(
                        une.getAnimateur().getId(), autre.getAnimateur().getId());
    }

    private static Map<String, PosteAffectation> byId(Collection<PosteAffectation> postes) {
        Map<String, PosteAffectation> byId = new HashMap<>();
        for (PosteAffectation poste : postes) {
            if (poste.getId() != null) {
                byId.putIfAbsent(poste.getId(), poste);
            }
        }
        return byId;
    }
}
