package dev.sylvain.planning.service.analyse;

import dev.sylvain.planning.domain.FenetreHoraire;
import dev.sylvain.planning.domain.HoraireStand;
import dev.sylvain.planning.domain.ModeHoraire;
import dev.sylvain.planning.domain.OuvertureStand;
import dev.sylvain.planning.service.referentiel.HoraireStandResolver;
import dev.sylvain.planning.service.referentiel.HoraireStandResolver.RuledDay;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * What {@link HoraireStandResolver} settles in silence between the rules of
 * one stand: two rules merged on a same day whose windows overlap, a rule
 * that decides no day at all, two windows of one rule (or of one dated
 * exception) that overlap at different headcounts. None of it is refused —
 * the resolution is deterministic (union of windows, highest headcount on an
 * overlap) and some of it is deliberate — but none of it should go unseen.
 *
 * <p>The findings carry <b>indexes</b>, not sentences: the stand's own rule
 * list and window lists, in the order they are stored. The analyzer turns
 * them into the report's messages, and the rule editor of the frontend runs
 * the same detection on the rules being typed ({@code core/horaire-stand.ts},
 * {@code anomaliesHoraires}); a shared case file
 * ({@code horaire-stand-anomalies.cas.json}) keeps the two in step.</p>
 *
 * <p>Judged on the edition's real days, not on the selectors alone: a rule
 * masked on paper but alone on every day that has a timeslot is not masked.
 * Without a single day, only the window overlaps — which need no calendar —
 * are reported. A consigne is not a layer of this reading: it shuts a band on
 * the effective windows, it does not take a rule's place.</p>
 *
 * <p>Pure and static, like the resolver it replays.</p>
 */
public final class HoraireRuleOverlaps {

    private static final int MINUTES_PER_DAY = 24 * 60;

    private HoraireRuleOverlaps() {}

    /**
     * One day of the edition: a date carrying a timeslot, or the morning after
     * one that crosses midnight — the days the resolver resolves — and where its
     * opening span ends — what an open-ended window (« jusqu'à la fermeture »)
     * runs to. Minutes from midnight, capped at midnight: a window never
     * crosses it.
     */
    public record EventDay(LocalDate date, int endMinute) {}

    /**
     * Two rules deciding a same day, whose windows overlap.
     *
     * @param rule          the first rule, by index in the stand's list
     * @param otherRule     the second one, always after {@code rule}
     * @param start         the overlap's start, minutes from midnight
     * @param end           its end
     * @param effectif      headcount of {@code rule}'s window there, the stand's minimum when unnamed
     * @param otherEffectif the same for {@code otherRule}
     */
    public record RulesOverlap(
            int rule, int otherRule, LocalDate date, int start, int end, Integer effectif, Integer otherEffectif) {}

    /**
     * A rule that covers days of the edition and decides none of them.
     *
     * @param maskingRules       the rules deciding its days instead, by index
     * @param maskedByExceptions some of its days are stated by a dated exception
     */
    public record MaskedRule(int rule, List<Integer> maskingRules, boolean maskedByExceptions) {}

    /**
     * Two windows of one rule — or of one day's dated openings — overlapping
     * at different headcounts.
     *
     * @param rule   the rule, by index; {@code null} for dated openings
     * @param date   the day of the dated openings; {@code null} for a rule
     * @param window index in the rule's windows, or in the stand's dated openings
     */
    public record WindowsOverlap(
            Integer rule,
            LocalDate date,
            int window,
            int otherWindow,
            int start,
            int end,
            Integer effectif,
            Integer otherEffectif) {}

    /** Everything found on one stand. */
    public record Findings(
            List<RulesOverlap> rulesOverlaps, List<MaskedRule> maskedRules, List<WindowsOverlap> windowsOverlaps) {

        public boolean isEmpty() {
            return rulesOverlaps.isEmpty() && maskedRules.isEmpty() && windowsOverlaps.isEmpty();
        }
    }

    /**
     * @param horaires         the stand's rules, in stored order
     * @param exceptionDates   the days a dated exception states, which no rule decides
     * @param datedOpenings    the stand's dated openings, in stored order
     * @param days             the edition's days, sorted; empty when it has no timeslot
     * @param defaultEffectif  what a window naming no headcount asks for; {@code null} when unknown
     */
    public static Findings detect(
            List<HoraireStand> horaires,
            Collection<LocalDate> exceptionDates,
            List<OuvertureStand> datedOpenings,
            List<EventDay> days,
            Integer defaultEffectif) {
        Set<LocalDate> exceptions = new TreeSet<>();
        exceptionDates.stream().filter(Objects::nonNull).forEach(exceptions::add);
        Map<HoraireStand, Integer> index = new IdentityHashMap<>();
        for (int i = 0; i < horaires.size(); i++) {
            index.put(horaires.get(i), i);
        }

        // Which rules decide each day, replayed through the resolver itself.
        Map<LocalDate, List<Integer>> deciders = new LinkedHashMap<>();
        for (EventDay day : days) {
            if (exceptions.contains(day.date())) {
                continue;
            }
            RuledDay ruled = HoraireStandResolver.resolveDayWithRules(horaires, day.date());
            deciders.put(
                    day.date(),
                    ruled == null
                            ? List.of()
                            : ruled.rules().stream().map(index::get).toList());
        }

        return new Findings(
                rulesOverlaps(horaires, days, deciders, defaultEffectif),
                maskedRules(horaires, days, exceptions, deciders),
                windowsOverlaps(horaires, datedOpenings, days, defaultEffectif));
    }

    private static List<RulesOverlap> rulesOverlaps(
            List<HoraireStand> horaires,
            List<EventDay> days,
            Map<LocalDate, List<Integer>> deciders,
            Integer defaultEffectif) {
        List<RulesOverlap> found = new ArrayList<>();
        Set<String> seen = new TreeSet<>();
        for (EventDay day : days) {
            List<Integer> rules = deciders.getOrDefault(day.date(), List.of());
            for (int a = 0; a < rules.size(); a++) {
                for (int b = a + 1; b < rules.size(); b++) {
                    int i = Math.min(rules.get(a), rules.get(b));
                    int j = Math.max(rules.get(a), rules.get(b));
                    if (seen.contains(i + "#" + j)) {
                        continue;
                    }
                    RulesOverlap overlap = firstOverlap(horaires, i, j, day, defaultEffectif);
                    if (overlap != null) {
                        seen.add(i + "#" + j);
                        found.add(overlap);
                    }
                }
            }
        }
        return found;
    }

    private static RulesOverlap firstOverlap(
            List<HoraireStand> horaires, int i, int j, EventDay day, Integer defaultEffectif) {
        for (FenetreHoraire a : horaires.get(i).validFenetres()) {
            for (FenetreHoraire b : horaires.get(j).validFenetres()) {
                int[] overlap = overlap(
                        a.getHeureDebut(), a.getHeureFin(), b.getHeureDebut(), b.getHeureFin(), day.endMinute());
                if (overlap != null) {
                    return new RulesOverlap(
                            i,
                            j,
                            day.date(),
                            overlap[0],
                            overlap[1],
                            effectif(a.getEffectif(), defaultEffectif),
                            effectif(b.getEffectif(), defaultEffectif));
                }
            }
        }
        return null;
    }

    private static List<MaskedRule> maskedRules(
            List<HoraireStand> horaires,
            List<EventDay> days,
            Set<LocalDate> exceptions,
            Map<LocalDate, List<Integer>> deciders) {
        List<MaskedRule> found = new ArrayList<>();
        for (int i = 0; i < horaires.size(); i++) {
            HoraireStand horaire = horaires.get(i);
            if (horaire.validFenetres().isEmpty()) {
                // Already ignored by the resolver, and already refused at write
                // time: saying it twice would only bury the first message.
                continue;
            }
            List<LocalDate> covered =
                    days.stream().map(EventDay::date).filter(horaire::couvre).toList();
            if (covered.isEmpty()) {
                // Outside the edition: another situation, which the windows
                // without effect and the never-open stand already name.
                continue;
            }
            int rule = i;
            boolean decides = covered.stream()
                    .anyMatch(date -> deciders.getOrDefault(date, List.of()).contains(rule));
            if (decides) {
                continue;
            }
            Set<Integer> masking = new TreeSet<>();
            boolean byExceptions = false;
            for (LocalDate date : covered) {
                if (exceptions.contains(date)) {
                    byExceptions = true;
                } else {
                    masking.addAll(deciders.getOrDefault(date, List.of()));
                }
            }
            found.add(new MaskedRule(i, List.copyOf(masking), byExceptions));
        }
        return found;
    }

    private static List<WindowsOverlap> windowsOverlaps(
            List<HoraireStand> horaires,
            List<OuvertureStand> datedOpenings,
            List<EventDay> days,
            Integer defaultEffectif) {
        List<WindowsOverlap> found = new ArrayList<>();
        for (int r = 0; r < horaires.size(); r++) {
            HoraireStand horaire = horaires.get(r);
            if (horaire.getMode() != ModeHoraire.OUVERTURE) {
                // A closure carries no headcount: two of its windows overlapping
                // shut the same minutes twice, and nothing is decided.
                continue;
            }
            int end = days.stream()
                    .filter(day -> horaire.couvre(day.date()))
                    .mapToInt(EventDay::endMinute)
                    .max()
                    .orElse(MINUTES_PER_DAY);
            List<FenetreHoraire> fenetres = horaire.getFenetres();
            for (int a = 0; a < fenetres.size(); a++) {
                for (int b = a + 1; b < fenetres.size(); b++) {
                    FenetreHoraire first = fenetres.get(a);
                    FenetreHoraire second = fenetres.get(b);
                    WindowsOverlap overlap = windowsOverlap(
                            r,
                            null,
                            a,
                            b,
                            first.hasValidRange() ? first.getHeureDebut() : null,
                            first.getHeureFin(),
                            first.getEffectif(),
                            second.hasValidRange() ? second.getHeureDebut() : null,
                            second.getHeureFin(),
                            second.getEffectif(),
                            end,
                            defaultEffectif);
                    if (overlap != null) {
                        found.add(overlap);
                    }
                }
            }
        }
        for (int a = 0; a < datedOpenings.size(); a++) {
            for (int b = a + 1; b < datedOpenings.size(); b++) {
                OuvertureStand first = datedOpenings.get(a);
                OuvertureStand second = datedOpenings.get(b);
                if (first.getDate() == null || !first.getDate().equals(second.getDate())) {
                    continue;
                }
                LocalDate date = first.getDate();
                int end = days.stream()
                        .filter(day -> day.date().equals(date))
                        .mapToInt(EventDay::endMinute)
                        .findFirst()
                        .orElse(MINUTES_PER_DAY);
                WindowsOverlap overlap = windowsOverlap(
                        null,
                        date,
                        a,
                        b,
                        valid(first.getHeureDebut(), first.getHeureFin()) ? first.getHeureDebut() : null,
                        first.getHeureFin(),
                        first.getEffectif(),
                        valid(second.getHeureDebut(), second.getHeureFin()) ? second.getHeureDebut() : null,
                        second.getHeureFin(),
                        second.getEffectif(),
                        end,
                        defaultEffectif);
                if (overlap != null) {
                    found.add(overlap);
                }
            }
        }
        return found;
    }

    private static WindowsOverlap windowsOverlap(
            Integer rule,
            LocalDate date,
            int a,
            int b,
            LocalTime startA,
            LocalTime endA,
            Integer effectifA,
            LocalTime startB,
            LocalTime endB,
            Integer effectifB,
            int dayEnd,
            Integer defaultEffectif) {
        if (startA == null || startB == null) {
            return null;
        }
        Integer first = effectif(effectifA, defaultEffectif);
        Integer second = effectif(effectifB, defaultEffectif);
        if (Objects.equals(first, second)) {
            // Same headcount: the union is exactly what was meant.
            return null;
        }
        int[] overlap = overlap(startA, endA, startB, endB, dayEnd);
        return overlap == null ? null : new WindowsOverlap(rule, date, a, b, overlap[0], overlap[1], first, second);
    }

    private static boolean valid(LocalTime start, LocalTime end) {
        return start != null && (end == null || end.isAfter(start));
    }

    private static Integer effectif(Integer effectif, Integer defaultEffectif) {
        return effectif != null ? effectif : defaultEffectif;
    }

    /**
     * The overlap of two windows in minutes, an open end running to
     * {@code dayEnd}; {@code null} when they merely touch or do not meet.
     */
    static int[] overlap(LocalTime startA, LocalTime endA, LocalTime startB, LocalTime endB, int dayEnd) {
        int cap = Math.min(dayEnd, MINUTES_PER_DAY);
        int start = Math.max(minutes(startA), minutes(startB));
        int end = Math.min(endA == null ? cap : minutes(endA), endB == null ? cap : minutes(endB));
        return end > start ? new int[] {start, end} : null;
    }

    private static int minutes(LocalTime time) {
        return time.toSecondOfDay() / 60;
    }

    /** Minutes from midnight as {@code HH:MM}; midnight at the end of a day reads {@code 24:00}. */
    public static String hour(int minutes) {
        return String.format("%02d:%02d", minutes / 60, minutes % 60);
    }
}
