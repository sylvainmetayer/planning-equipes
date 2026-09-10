package dev.sylvain.planning.domain;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * A single closure window of a {@link Stand}: the stand cannot be staffed
 * between {@code heureDebut} and {@code heureFin} on {@code date}. Replaces
 * the former {@code Creneau.standsOuvertsIds} (a per-créneau, whole-slot
 * open/closed toggle) so a stand can be closed for only part of a créneau —
 * see {@code Creneau#segmentsOuvertsMinutes(Stand)}, which subtracts these
 * windows from a créneau to find its still-open sub-intervals.
 *
 * <p>Window shape and midnight/open-end rules are shared with
 * {@link OuvertureStand} — see {@link FenetreDateeStand}.</p>
 */
public class IndisponibiliteStand extends FenetreDateeStand {

    public IndisponibiliteStand() {}

    public IndisponibiliteStand(Long id, LocalDate date, LocalTime heureDebut, LocalTime heureFin, String motif) {
        super(id, date, heureDebut, heureFin, motif);
    }
}
