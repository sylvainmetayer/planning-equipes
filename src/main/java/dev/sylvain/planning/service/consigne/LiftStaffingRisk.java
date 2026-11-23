package dev.sylvain.planning.service.consigne;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.PlafondsLegauxMajeurs;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.TypeVerrouillage;
import dev.sylvain.planning.domain.VerrouillagePlanning;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Whether lifting a consigne hands its day back seats that the days kept
 * frozen around it leave people unable to take (ADR 0089). A lift gives the
 * day its nominal grid back; a full solve re-plans it but pins every seat a
 * lock covers and every day already past. An animateur who holds a frozen
 * seat on each of the six other days of the day's ISO week can no longer
 * work it ({@code maxJoursTravaillesParSemaine}), whoever the day needs.
 *
 * <p>A risk, not a proof: the day may still be staffable without them. It is
 * raised only when both hold — the lift adds seat-minutes to the day, and at
 * least one animateur available that day is out of weekly days — and it says
 * which locked days to reopen to get them back. Pure and static, read on the
 * persisted plan; the solver is never called.</p>
 */
final class LiftStaffingRisk {

    /**
     * @param animateursSansJourLibre animateurs available on the day whose
     *                                frozen seats already fill the six other
     *                                days of its week
     * @param joursADeverrouiller     JOUR-locked days whose reopening gives
     *                                each of them back a day, fewest first
     */
    record Risk(int animateursSansJourLibre, List<LocalDate> joursADeverrouiller) {}

    private LiftStaffingRisk() {}

    /**
     * @param minutesNow   seat-minutes the day carries under its consigne
     * @param minutesAfter seat-minutes it carries once lifted
     * @param today        the first day not yet past: earlier days are frozen
     */
    static Optional<Risk> assess(
            LocalDate date,
            int minutesNow,
            int minutesAfter,
            List<Animateur> animateurs,
            List<PosteAffectation> persisted,
            List<VerrouillagePlanning> locks,
            LocalDate today) {
        if (minutesAfter <= minutesNow) {
            return Optional.empty();
        }
        String week = Creneau.semaineIso(date);
        Set<LocalDate> lockedDays = new HashSet<>();
        for (VerrouillagePlanning lock : locks) {
            if (lock.getType() == TypeVerrouillage.JOUR && lock.getJour() != null) {
                lockedDays.add(lock.getJour());
            }
        }
        Map<String, Set<LocalDate>> frozenDays = new HashMap<>();
        for (PosteAffectation poste : persisted) {
            LocalDate day =
                    poste.getCreneau() == null ? null : poste.getCreneau().getDate();
            if (poste.getAnimateur() == null
                    || day == null
                    || day.equals(date)
                    || !week.equals(Creneau.semaineIso(day))) {
                continue;
            }
            if (day.isBefore(today) || locks.stream().anyMatch(lock -> lock.couvre(poste))) {
                frozenDays
                        .computeIfAbsent(poste.getAnimateur().getId(), id -> new TreeSet<>())
                        .add(day);
            }
        }
        List<Set<LocalDate>> unlockable = new ArrayList<>();
        int blocked = 0;
        for (Animateur animateur : animateurs) {
            Set<LocalDate> days = frozenDays.getOrDefault(animateur.getId(), Set.of());
            if (animateur.isIndisponibleOn(date)
                    || days.size() < PlafondsLegauxMajeurs.JOURS_TRAVAILLES_MAX_PAR_SEMAINE) {
                continue;
            }
            blocked++;
            Set<LocalDate> reopenable = new HashSet<>(days);
            reopenable.retainAll(lockedDays);
            reopenable.removeIf(day -> day.isBefore(today));
            if (!reopenable.isEmpty()) {
                unlockable.add(reopenable);
            }
        }
        if (blocked == 0) {
            return Optional.empty();
        }
        return Optional.of(new Risk(blocked, cover(unlockable, date)));
    }

    /** Greedy: the day reopening the most people first, the nearest to {@code date} on a tie, then the earliest. */
    private static List<LocalDate> cover(List<Set<LocalDate>> people, LocalDate date) {
        List<Set<LocalDate>> left = new ArrayList<>(people);
        TreeSet<LocalDate> chosen = new TreeSet<>();
        while (!left.isEmpty()) {
            Map<LocalDate, Integer> reach = new HashMap<>();
            left.forEach(days -> days.forEach(day -> reach.merge(day, 1, Integer::sum)));
            LocalDate best = reach.keySet().stream()
                    .min(Comparator.comparing((LocalDate day) -> -reach.get(day))
                            .thenComparing(day -> Math.abs(ChronoUnit.DAYS.between(date, day)))
                            .thenComparing(day -> day))
                    .orElseThrow();
            chosen.add(best);
            left.removeIf(days -> days.contains(best));
        }
        return List.copyOf(chosen);
    }
}
