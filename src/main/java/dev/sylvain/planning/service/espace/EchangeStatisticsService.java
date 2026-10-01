package dev.sylvain.planning.service.espace;

import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.DemandeEchange;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.domain.StatutDemandeEchange;
import dev.sylvain.planning.service.BusinessError;
import dev.sylvain.planning.service.espace.DemandeEchangeService.FenetreFoire;
import dev.sylvain.planning.service.espace.EchangeStatistics.EchangeConstraintCount;
import dev.sylvain.planning.service.espace.EchangeStatistics.EchangeDayCount;
import dev.sylvain.planning.service.espace.EchangeStatistics.EchangeDelay;
import dev.sylvain.planning.service.espace.EchangeStatistics.EchangeRate;
import dev.sylvain.planning.service.espace.EchangeStatistics.EchangeStandCount;
import dev.sylvain.planning.service.notification.NotificationsPlanifieesService;
import dev.sylvain.planning.service.referentiel.ReferenceDataService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * The statistics of the foire au planning: whether it works, beyond the raw
 * list of swap requests. Everything is derived from the timestamps the
 * lifecycle already writes — no column of its own, nothing stored.
 *
 * <p>The computation is {@link #compute}, pure and static, so its test pins
 * every figure down without a database; this bean only gathers its inputs —
 * the edition's requests, the dates of its timeslots, the names of its stands,
 * the foire window and the zone the days are cut in.</p>
 *
 * <p>Three rates rather than one « taux d'acceptation »: a refusal by the
 * colleague, a refusal by the organisation and a withdrawal do not say the
 * same thing, which is the distinction {@code annuleLe} was split from
 * {@code decideLe} for. Delays are given as a median and a 90th percentile —
 * a few requests forgotten for two weeks make a mean say nothing typical —
 * with the mean kept beside them.</p>
 *
 * <p>« Answered by the colleague » is read off {@code cibleDecideLe}, which only
 * the colleague's answer writes — never off the statut: the organisation may
 * refuse a request the colleague has not answered yet ({@code REFUSEE} without
 * {@code cibleDecideLe}), and a colleague may agree to a request its demandeur
 * then withdraws ({@code ANNULEE} with it). The one fallback on the statut is a
 * request older than the colleague's step (migration V47): {@code PROPOSEE} or
 * {@code ACCEPTEE} without {@code cibleDecideLe} can only be one of those, and it
 * went straight to the organisation's queue, which read it as agreed. A
 * {@code REFUSEE} of that age cannot be told from a refusal before any answer and
 * is read as the latter.</p>
 *
 * <p>The creation days are cut in the nightly jobs' zone, while whether the
 * foire is open today is judged in the server's default zone
 * ({@link DemandeEchangeService#fenetre}).</p>
 */
@ApplicationScoped
public class EchangeStatisticsService {

    /** The organisation's decisions. A withdrawal is not one. */
    private static final Set<StatutDemandeEchange> DECIDED_BY_ORGANISATION =
            EnumSet.of(StatutDemandeEchange.ACCEPTEE, StatutDemandeEchange.REFUSEE);

    /**
     * What a request older than the colleague's step could be in the
     * organisation's queue or past it: read as agreed, as the queue read it.
     */
    private static final Set<StatutDemandeEchange> AGREED_BEFORE_THE_COLLEAGUES_STEP =
            EnumSet.of(StatutDemandeEchange.PROPOSEE, StatutDemandeEchange.ACCEPTEE);

    private static final Set<StatutDemandeEchange> FINISHED = EnumSet.of(
            StatutDemandeEchange.ACCEPTEE,
            StatutDemandeEchange.REFUSEE,
            StatutDemandeEchange.REFUSEE_CIBLE,
            StatutDemandeEchange.ANNULEE);

    /** Creation to the colleague's answer, over the requests the colleague answered. */
    private static final DelaySpan REPLY = new DelaySpan(
            EchangeStatisticsService::answeredByColleague, DemandeEchange::getCreeLe, DemandeEchange::getCibleDecideLe);

    /**
     * The colleague's answer to the organisation's decision. A colleague's
     * refusal stamps cibleDecideLe and never decideLe, and is no arbitration;
     * a refusal before any answer has no start — the organisation decided
     * without waiting for the colleague, which is not the time a request
     * waited on its desk.
     */
    private static final DelaySpan ARBITRATION = new DelaySpan(
            demande -> DECIDED_BY_ORGANISATION.contains(demande.getStatut()) && answeredByColleague(demande),
            DemandeEchange::getCibleDecideLe,
            DemandeEchange::getDecideLe);

    /**
     * The organisation's decision to the publication announcing it. A decision
     * the next publication has yet to announce has no delay yet: it is
     * pending, not missing a timestamp.
     */
    private static final DelaySpan COMMUNICATION = new DelaySpan(
            demande -> DECIDED_BY_ORGANISATION.contains(demande.getStatut())
                    && (demande.getCommuniqueeLe() != null || demande.getDecideLe() == null),
            DemandeEchange::getDecideLe,
            DemandeEchange::getCommuniqueeLe);

    private static final DelaySpan CANCELLATION = new DelaySpan(
            demande -> demande.getStatut() == StatutDemandeEchange.ANNULEE,
            DemandeEchange::getCreeLe,
            DemandeEchange::getAnnuleLe);

    /**
     * What a figure of the statistics counts that no criterion of the list
     * says on its own — the colleague's answer, the population a delay was
     * measured over — so the list can be narrowed to exactly those requests
     * ({@code GET /api/echanges?mesure=}). The predicates are the ones the
     * figures are computed with: the link cannot open a different population.
     */
    public enum Measure {
        ANSWERED_BY_COLLEAGUE("repondues"),
        AGREED_BY_COLLEAGUE("accordees"),
        REPLY_DELAY("delai-reponse"),
        ARBITRATION_DELAY("delai-arbitrage"),
        COMMUNICATION_DELAY("delai-communication"),
        CANCELLATION_DELAY("delai-annulation");

        private final String param;

        Measure(String param) {
            this.param = param;
        }

        /** The value of {@code ?mesure=}; {@code null} for a blank one, an unknown one refused. */
        public static Measure fromParam(String value) {
            if (value == null || value.isBlank()) {
                return null;
            }
            for (Measure measure : values()) {
                if (measure.param.equals(value.trim())) {
                    return measure;
                }
            }
            throw new BusinessError.Invalid("Paramètre « mesure » inconnu : " + value);
        }

        boolean test(DemandeEchange demande) {
            return switch (this) {
                case ANSWERED_BY_COLLEAGUE -> answeredByColleague(demande);
                case AGREED_BY_COLLEAGUE -> agreedByColleague(demande);
                case REPLY_DELAY -> REPLY.measured(demande);
                case ARBITRATION_DELAY -> ARBITRATION.measured(demande);
                case COMMUNICATION_DELAY -> COMMUNICATION.measured(demande);
                case CANCELLATION_DELAY -> CANCELLATION.measured(demande);
            };
        }
    }

    private final DemandeEchangeService demandeEchangeService;

    private final ReferenceDataService referenceDataService;

    private final NotificationsPlanifieesService notifications;

    @Inject
    public EchangeStatisticsService(
            DemandeEchangeService demandeEchangeService,
            ReferenceDataService referenceDataService,
            NotificationsPlanifieesService notifications) {
        this.demandeEchangeService = demandeEchangeService;
        this.referenceDataService = referenceDataService;
        this.notifications = notifications;
    }

    /**
     * The statistics of the current edition over one period of creation days.
     *
     * <p>With neither bound given, the period is the foire window when it is
     * bounded — the question is usually « how did this foire go? » — and the
     * whole edition otherwise; {@code wholeEdition} asks for the latter
     * explicitly. A bound given is taken as is, the other side then unbounded.</p>
     */
    public EchangeStatistics statistics(LocalDate du, LocalDate au, boolean wholeEdition) {
        LocalDate from = du;
        LocalDate to = au;
        boolean window = false;
        if (du == null && au == null && !wholeEdition) {
            FenetreFoire fenetre = demandeEchangeService.fenetre();
            if (fenetre.debut() != null || fenetre.fin() != null) {
                from = fenetre.debut();
                to = fenetre.fin();
                window = true;
            }
        }
        Map<Long, LocalDate> creneauDates = new HashMap<>();
        for (Creneau creneau : referenceDataService.listCreneaux()) {
            if (creneau.getId() != null) {
                creneauDates.put(creneau.getId(), creneau.getDate());
            }
        }
        Map<String, String> standNames = new HashMap<>();
        for (Stand stand : referenceDataService.listStands()) {
            standNames.put(stand.getId(), stand.getNom());
        }
        return compute(
                demandeEchangeService.list(), from, to, window, notifications.zoneId(), creneauDates, standNames);
    }

    /**
     * The current edition's requests created between the two days, both
     * included, cut in the same zone as {@link #statistics}, and narrowed to
     * one {@link Measure} when given — what a figure of the statistics links
     * to, so the list shows exactly the requests it counted.
     */
    public List<DemandeEchange> createdBetween(LocalDate du, LocalDate au, Measure measure) {
        return select(demandeEchangeService.list(), du, au, notifications.zoneId(), measure);
    }

    /** The pure half of {@link #createdBetween}; {@code measure} may be {@code null}. */
    static List<DemandeEchange> select(
            List<DemandeEchange> demandes, LocalDate du, LocalDate au, ZoneId zone, Measure measure) {
        return demandes.stream()
                .filter(demande -> createdIn(demande, du, au, zone))
                .filter(demande -> measure == null || measure.test(demande))
                .toList();
    }

    /**
     * Whether the colleague answered: {@code cibleDecideLe}, which only the
     * colleague's answer writes, or a request older than that step that the
     * organisation's queue read as agreed.
     */
    static boolean answeredByColleague(DemandeEchange demande) {
        return demande.getCibleDecideLe() != null || AGREED_BEFORE_THE_COLLEAGUES_STEP.contains(demande.getStatut());
    }

    /** Answered, and not with a refusal — a request agreed then withdrawn included. */
    static boolean agreedByColleague(DemandeEchange demande) {
        return answeredByColleague(demande) && demande.getStatut() != StatutDemandeEchange.REFUSEE_CIBLE;
    }

    /** True when the request was created inside the period; a request without a creation time is outside any bound. */
    static boolean createdIn(DemandeEchange demande, LocalDate du, LocalDate au, ZoneId zone) {
        if (du == null && au == null) {
            return true;
        }
        if (demande.getCreeLe() == null) {
            return false;
        }
        LocalDate jour = demande.getCreeLe().atZone(zone).toLocalDate();
        return (du == null || !jour.isBefore(du)) && (au == null || !jour.isAfter(au));
    }

    /**
     * The whole computation, pure.
     *
     * @param demandes     every request of the edition, any statut; filtered here on {@code creeLe}
     * @param du           first creation day kept, {@code null} = unbounded
     * @param au           last creation day kept, {@code null} = unbounded
     * @param fenetreFoire whether the bounds are the foire window, echoed for the screen
     * @param zone         the zone the creation days are cut in
     * @param creneauDates the date of every timeslot that still exists
     * @param standNames   the name of every stand that still exists
     */
    public static EchangeStatistics compute(
            List<DemandeEchange> demandes,
            LocalDate du,
            LocalDate au,
            boolean fenetreFoire,
            ZoneId zone,
            Map<Long, LocalDate> creneauDates,
            Map<String, String> standNames) {
        List<DemandeEchange> kept = demandes.stream()
                .filter(demande -> createdIn(demande, du, au, zone))
                .toList();
        Map<StatutDemandeEchange, Long> byStatut =
                kept.stream().collect(Collectors.groupingBy(DemandeEchange::getStatut, Collectors.counting()));
        List<DemandeEchange> failedPrevalidation = kept.stream()
                .filter(demande -> Boolean.FALSE.equals(demande.getPrevalidationOk()))
                .toList();

        return new EchangeStatistics(
                du,
                au,
                fenetreFoire,
                zone.getId(),
                kept.size(),
                count(kept, demande -> demande.getCreneauCibleId() != null),
                countOf(byStatut, StatutDemandeEchange.EN_ATTENTE_CIBLE),
                countOf(byStatut, StatutDemandeEchange.PROPOSEE),
                countOf(byStatut, StatutDemandeEchange.ACCEPTEE),
                countOf(byStatut, StatutDemandeEchange.REFUSEE),
                countOf(byStatut, StatutDemandeEchange.REFUSEE_CIBLE),
                countOf(byStatut, StatutDemandeEchange.ANNULEE),
                new EchangeRate(
                        count(kept, EchangeStatisticsService::agreedByColleague),
                        count(kept, EchangeStatisticsService::answeredByColleague)),
                rate(kept, EnumSet.of(StatutDemandeEchange.ACCEPTEE), DECIDED_BY_ORGANISATION),
                rate(kept, EnumSet.of(StatutDemandeEchange.ACCEPTEE), FINISHED),
                new EchangeRate(count(kept, demande -> Boolean.TRUE.equals(demande.getPrevalidationOk())), kept.size()),
                rate(failedPrevalidation, EnumSet.of(StatutDemandeEchange.ACCEPTEE), DECIDED_BY_ORGANISATION),
                delay(kept, REPLY),
                delay(kept, ARBITRATION),
                delay(kept, COMMUNICATION),
                delay(kept, CANCELLATION),
                byCreationDay(kept, zone),
                byEventDay(kept, creneauDates),
                count(kept, demande -> !creneauDates.containsKey(demande.getCreneauId())),
                byStand(kept, standNames),
                byConstraint(failedPrevalidation));
    }

    private static int count(List<DemandeEchange> demandes, Predicate<DemandeEchange> predicate) {
        return (int) demandes.stream().filter(predicate).count();
    }

    private static int countOf(Map<StatutDemandeEchange, Long> byStatut, StatutDemandeEchange statut) {
        return byStatut.getOrDefault(statut, 0L).intValue();
    }

    private static EchangeRate rate(
            List<DemandeEchange> demandes, Set<StatutDemandeEchange> numerator, Set<StatutDemandeEchange> denominator) {
        return new EchangeRate(
                count(demandes, demande -> numerator.contains(demande.getStatut())),
                count(demandes, demande -> denominator.contains(demande.getStatut())));
    }

    /**
     * One delay: which requests it concerns, and the two timestamps it runs
     * between. A concerned request is measured when it carries both, in order;
     * otherwise it is « sans horodatage ».
     */
    private record DelaySpan(
            Predicate<DemandeEchange> concerned,
            Function<DemandeEchange, Instant> start,
            Function<DemandeEchange, Instant> end) {

        boolean measured(DemandeEchange demande) {
            if (!concerned.test(demande)) {
                return false;
            }
            Instant from = start.apply(demande);
            Instant to = end.apply(demande);
            return from != null && to != null && !to.isBefore(from);
        }
    }

    private static EchangeDelay delay(List<DemandeEchange> demandes, DelaySpan span) {
        List<Long> seconds = new ArrayList<>();
        int sansHorodatage = 0;
        for (DemandeEchange demande : demandes) {
            if (span.measured(demande)) {
                seconds.add(
                        Duration.between(span.start().apply(demande), span.end().apply(demande))
                                .toSeconds());
            } else if (span.concerned().test(demande)) {
                sansHorodatage++;
            }
        }
        if (seconds.isEmpty()) {
            return new EchangeDelay(0, sansHorodatage, null, null, null);
        }
        long[] sorted = seconds.stream().mapToLong(Long::longValue).sorted().toArray();
        long sum = 0;
        for (long value : sorted) {
            sum += value;
        }
        return new EchangeDelay(
                sorted.length,
                sansHorodatage,
                percentile(sorted, 0.5),
                percentile(sorted, 0.9),
                Math.round((double) sum / sorted.length));
    }

    /**
     * Linear interpolation between the closest ranks (the spreadsheet's
     * {@code PERCENTILE.INC}): the median of 1, 2, 3, 4 is 2.5, not 2.
     */
    static long percentile(long[] sorted, double fraction) {
        double position = fraction * (sorted.length - 1);
        int low = (int) Math.floor(position);
        int high = (int) Math.ceil(position);
        double value = sorted[low] + (position - low) * (sorted[high] - sorted[low]);
        return Math.round(value);
    }

    /** Every day from the first creation to the last, the empty ones included: a histogram with gaps lies. */
    private static List<EchangeDayCount> byCreationDay(List<DemandeEchange> demandes, ZoneId zone) {
        TreeMap<LocalDate, Integer> byDay = new TreeMap<>();
        for (DemandeEchange demande : demandes) {
            if (demande.getCreeLe() != null) {
                byDay.merge(demande.getCreeLe().atZone(zone).toLocalDate(), 1, Integer::sum);
            }
        }
        List<EchangeDayCount> days = new ArrayList<>();
        if (byDay.isEmpty()) {
            return days;
        }
        for (LocalDate day = byDay.firstKey(); !day.isAfter(byDay.lastKey()); day = day.plusDays(1)) {
            days.add(new EchangeDayCount(day, byDay.getOrDefault(day, 0)));
        }
        return days;
    }

    /** The date of the seat given up; a timeslot since deleted is counted apart, never guessed. */
    private static List<EchangeDayCount> byEventDay(List<DemandeEchange> demandes, Map<Long, LocalDate> creneauDates) {
        TreeMap<LocalDate, Integer> byDay = new TreeMap<>();
        for (DemandeEchange demande : demandes) {
            LocalDate day = creneauDates.get(demande.getCreneauId());
            if (day != null) {
                byDay.merge(day, 1, Integer::sum);
            }
        }
        return byDay.entrySet().stream()
                .map(entry -> new EchangeDayCount(entry.getKey(), entry.getValue()))
                .toList();
    }

    private static List<EchangeStandCount> byStand(List<DemandeEchange> demandes, Map<String, String> standNames) {
        Map<String, Integer> byStand = new LinkedHashMap<>();
        for (DemandeEchange demande : demandes) {
            byStand.merge(demande.getStandId(), 1, Integer::sum);
        }
        return byStand.entrySet().stream()
                .map(entry -> new EchangeStandCount(entry.getKey(), standNames.get(entry.getKey()), entry.getValue()))
                .sorted(Comparator.comparingInt(EchangeStandCount::nombre)
                        .reversed()
                        .thenComparing(line -> Objects.requireNonNullElse(line.standNom(), line.standId())))
                .toList();
    }

    private static List<EchangeConstraintCount> byConstraint(List<DemandeEchange> failedPrevalidation) {
        Map<String, Integer> byConstraint = new HashMap<>();
        for (DemandeEchange demande : failedPrevalidation) {
            if (demande.getContraintesViolees() == null) {
                continue;
            }
            // Once per request: a request breaking the same rule twice is one request.
            demande.getContraintesViolees().stream()
                    .filter(Objects::nonNull)
                    .distinct()
                    .forEach(constraint -> byConstraint.merge(constraint, 1, Integer::sum));
        }
        return byConstraint.entrySet().stream()
                .map(entry -> new EchangeConstraintCount(entry.getKey(), entry.getValue()))
                .sorted(Comparator.comparingInt(EchangeConstraintCount::nombre)
                        .reversed()
                        .thenComparing(EchangeConstraintCount::contrainte))
                .toList();
    }
}
