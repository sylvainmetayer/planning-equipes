package dev.sylvain.planning.service.consigne;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.PastHorizon;
import dev.sylvain.planning.domain.PlafondsLegauxMajeurs;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.TypeVerrouillage;
import dev.sylvain.planning.domain.VerrouillagePlanning;
import dev.sylvain.planning.service.solve.FrozenPast;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Whether lifting a consigne hands its day back seats that the days kept
 * frozen around it leave people unable to take (ADR 0089). A lift gives the
 * day its nominal grid back; a full solve re-plans it but pins every seat a
 * lock covers and every seat already started. An animateur who holds a frozen
 * seat on each of the six other days of the day's ISO week can no longer
 * work it ({@code maxJoursTravaillesParSemaine}), whoever the day needs.
 *
 * <p>A risk, not a proof: the day may still be staffable without them. It is
 * raised only when both hold — the lift adds seat-minutes to the day, and at
 * least one animateur available that day is out of weekly days — and it says
 * which JOUR-locked days to reopen to get back the ones a reopening can free.
 * Pure and static, read on the persisted plan; the solver is never called.</p>
 */
final class LiftStaffingRisk {

    /**
     * @param animateursSansJourLibre animateurs available on the day whose
     *                                frozen seats already fill the six other
     *                                days of its week
     * @param animateursLiberes       how many of them reopening
     *                                {@code joursADeverrouiller} gives a day
     *                                back; the others are held by the past or
     *                                by a lock other than a day's
     * @param joursADeverrouiller     JOUR-locked days to reopen, fewest first
     */
    record Risk(int animateursSansJourLibre, int animateursLiberes, List<LocalDate> joursADeverrouiller) {}

    private LiftStaffingRisk() {}

    /**
     * @param minutesNow   seat-minutes the day carries under its consigne
     * @param minutesAfter seat-minutes it carries once lifted
     * @param horizon      the past as the solve judges it (ADR 0044), {@code
     *                     null} when the freeze is off
     */
    static Optional<Risk> assess(
            LocalDate date,
            int minutesNow,
            int minutesAfter,
            List<Animateur> animateurs,
            List<PosteAffectation> persisted,
            List<VerrouillagePlanning> locks,
            PastHorizon horizon) {
        if (minutesAfter <= minutesNow) {
            return Optional.empty();
        }
        Map<String, Map<LocalDate, Boolean>> frozen = frozenDays(date, persisted, locks, horizon);
        List<Set<LocalDate>> reopenable = new ArrayList<>();
        int blocked = 0;
        for (Animateur animateur : animateurs) {
            Map<LocalDate, Boolean> days = frozen.getOrDefault(animateur.getId(), Map.of());
            if (animateur.isIndisponibleOn(date)
                    || days.size() < PlafondsLegauxMajeurs.JOURS_TRAVAILLES_MAX_PAR_SEMAINE) {
                continue;
            }
            blocked++;
            Set<LocalDate> theirs = days.entrySet().stream()
                    .filter(Map.Entry::getValue)
                    .map(Map.Entry::getKey)
                    .collect(Collectors.toSet());
            if (!theirs.isEmpty()) {
                reopenable.add(theirs);
            }
        }
        if (blocked == 0) {
            return Optional.empty();
        }
        return Optional.of(new Risk(blocked, reopenable.size(), cover(reopenable, date)));
    }

    /** Per animateur, each frozen day of the week of {@code date} — and whether a JOUR lock alone holds it there. */
    private static Map<String, Map<LocalDate, Boolean>> frozenDays(
            LocalDate date, List<PosteAffectation> persisted, List<VerrouillagePlanning> locks, PastHorizon horizon) {
        String week = Creneau.semaineIso(date);
        Map<String, Map<LocalDate, Boolean>> frozen = new HashMap<>();
        for (PosteAffectation poste : persisted) {
            LocalDate day =
                    poste.getCreneau() == null ? null : poste.getCreneau().getDate();
            if (poste.getAnimateur() == null
                    || day == null
                    || day.equals(date)
                    || !week.equals(Creneau.semaineIso(day))) {
                continue;
            }
            List<VerrouillagePlanning> covering =
                    locks.stream().filter(lock -> lock.couvre(poste)).toList();
            boolean past = FrozenPast.isPast(poste, horizon);
            if (past || !covering.isEmpty()) {
                boolean dayLockOnly = !past
                        && covering.stream()
                                .allMatch(
                                        lock -> lock.getType() == TypeVerrouillage.JOUR && day.equals(lock.getJour()));
                frozen.computeIfAbsent(poste.getAnimateur().getId(), id -> new HashMap<>())
                        .merge(day, dayLockOnly, Boolean::logicalAnd);
            }
        }
        return frozen;
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
