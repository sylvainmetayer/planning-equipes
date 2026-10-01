package dev.sylvain.planning.service.weather;

import dev.sylvain.planning.config.ConfigMeteo;
import dev.sylvain.planning.domain.ConsigneEdition;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.Edition;
import dev.sylvain.planning.domain.Emplacement;
import dev.sylvain.planning.domain.ParametresMeteo;
import dev.sylvain.planning.domain.PrereglageConsigne;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.service.BusinessError;
import dev.sylvain.planning.service.EditionContext;
import dev.sylvain.planning.service.consigne.ConsigneService;
import dev.sylvain.planning.service.edition.EditionRepository;
import dev.sylvain.planning.service.espace.ApplicationLinks;
import dev.sylvain.planning.service.espace.JourJClock;
import dev.sylvain.planning.service.journal.CurrentAction;
import dev.sylvain.planning.service.journal.JournalActionService;
import dev.sylvain.planning.service.notification.JournalNotificationsRepository;
import dev.sylvain.planning.service.notification.Notification;
import dev.sylvain.planning.service.notification.OutboundEditionPolicy;
import dev.sylvain.planning.service.referentiel.JoursEvenement;
import dev.sylvain.planning.service.referentiel.ReferenceDataService;
import dev.sylvain.planning.service.weather.OpenMeteoClient.Coordinates;
import dev.sylvain.planning.service.weather.OpenMeteoClient.DailyForecast;
import dev.sylvain.planning.service.weather.OpenMeteoClient.WeatherUnavailable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * The weather alert of an edition (ADR 0074): which dates and places to watch,
 * what Open-Meteo forecasts for them, and which alert — with which consigne
 * preset — that calls for. It <b>suggests</b>; nothing here writes a
 * consigne, and {@code GET /api/consignes} reads the same before and after a
 * run.
 *
 * <p>The dates are those of the event (its timeslots) within the horizon,
 * counted from the day {@link JourJClock} says it is — a rehearsal on a
 * simulated date sees the days it simulates — and within the window the
 * service really forecasts, sixteen days from the <em>real</em> today. When
 * the two do not meet, nothing is queried and the screen says « hors
 * prévision ».</p>
 *
 * <p>Today is watched — a storm this afternoon is worth saying —, but a
 * consigne can only be posed on a day still ahead, so an alert about today
 * suggests no preset and links to no form.</p>
 *
 * <p>The places are the located places of the stands open on those dates,
 * read through {@code ReferenceDataService.listSolvedStands()} — consignes
 * included — and rounded to 0.01° so neighbouring stands share one point. One
 * request carries them all.</p>
 *
 * <p>Each alert is claimed in {@code notification_planifiee} under {@code
 * date|phénomène|palier}: a stable forecast stays silent, and only a forecast
 * that <b>worsens</b> by a level — two more degrees, ten more km/h, hail on
 * top of thunder — raises a new one. The levels below are claimed too, as
 * plain locks, so a forecast that eases off and climbs back is not news.</p>
 */
@ApplicationScoped
public class WeatherAlertService {

    /** Open-Meteo forecasts sixteen days, today included. */
    static final int FORECAST_DAYS_AHEAD = 15;

    /** WMO codes of a thunderstorm: 95, and 96 and 99 with hail. */
    static final Set<Integer> STORM_CODES = Set.of(95, 96, 99);

    private static final int HEAT_STEP = 2;

    private static final int GUST_STEP = 10;

    /** How many place names a sentence carries before « et N autres ». */
    private static final int PLACES_NAMED = 4;

    private static final DateTimeFormatter JOUR = DateTimeFormatter.ofPattern("EEE dd/MM", Locale.FRENCH);

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.FRENCH);

    /** What the alert watches. */
    public enum Phenomenon {
        HEAT("chaleur"),
        GUST("rafales"),
        STORM("orage");

        private final String code;

        Phenomenon(String code) {
            this.code = code;
        }

        /** The published code, in the webhook payload and the MCP answer. */
        public String code() {
            return code;
        }
    }

    /**
     * One alert: a date, a phenomenon, how strong (rounded) against which
     * threshold, its level above it, and the places concerned.
     */
    public record Alert(
            LocalDate date, Phenomenon phenomenon, int value, int threshold, int level, List<String> places) {

        /** The claim key: {@code date|PHENOMENON|level}. */
        String key() {
            return key(level);
        }

        String key(int palier) {
            return date + "|" + phenomenon.name() + "|" + palier;
        }
    }

    /** The settings and what the last run saw, as the Paramètres block reads them. */
    @Schema(requiredProperties = {"settings", "state", "serviceEnabled", "editionMayEmit"})
    public record WeatherSettingsView(
            ParametresMeteo settings, WeatherState state, boolean serviceEnabled, boolean editionMayEmit) {}

    /** One date of « Tester maintenant »: the maxima over its places, and what crosses a threshold. */
    @Schema(requiredProperties = {"date", "places", "storm", "exceeded"})
    public record WeatherDaySummary(
            LocalDate date,
            Integer maxTemperature,
            Integer maxGust,
            boolean storm,
            int places,
            List<String> exceeded) {}

    /**
     * What « Tester maintenant » answers: the forecast summed up per date, or
     * why there is none. It raises no alert and writes nothing.
     */
    @Schema(requiredProperties = {"outOfForecast", "noPlace", "days"})
    public record WeatherTestReport(
            boolean outOfForecast, boolean noPlace, String error, List<WeatherDaySummary> days) {}

    /** An alert already raised, as the home screen keeps it. */
    @Schema(requiredProperties = {"date", "phenomenon", "level", "label"})
    public record RaisedAlert(
            LocalDate date, String phenomenon, int level, String label, Instant raisedAt, String suggestedPreset) {}

    /** What the MCP tool answers: whether the alert runs, what it last saw, and what it raised. */
    @Schema(requiredProperties = {"active", "state", "alerts"})
    public record WeatherAlertsView(boolean active, WeatherState state, List<RaisedAlert> alerts) {}

    /** How one run ended; {@code outcome} is {@code null} when the alert is off for the edition. */
    public record RunResult(WeatherState.Outcome outcome, int newAlerts) {}

    private final WeatherRepository repository;

    private final OpenMeteoClient client;

    private final ReferenceDataService referenceData;

    private final ConsigneService consignes;

    private final JourJClock clock;

    private final JournalNotificationsRepository journal;

    private final JournalActionService actions;

    private final Event<Notification> notifications;

    private final ConfigMeteo config;

    private final ApplicationLinks links;

    private final EditionContext editionContext;

    private final EditionRepository editionRepository;

    private final OutboundEditionPolicy policy;

    private final CurrentAction currentAction;

    private final String zone;

    @Inject
    public WeatherAlertService(
            WeatherRepository repository,
            OpenMeteoClient client,
            ReferenceDataService referenceData,
            ConsigneService consignes,
            JourJClock clock,
            JournalNotificationsRepository journal,
            JournalActionService actions,
            Event<Notification> notifications,
            ConfigMeteo config,
            ApplicationLinks links,
            EditionContext editionContext,
            EditionRepository editionRepository,
            OutboundEditionPolicy policy,
            CurrentAction currentAction,
            @ConfigProperty(name = "planning.notifications.zone") String zone) {
        this.repository = repository;
        this.client = client;
        this.referenceData = referenceData;
        this.consignes = consignes;
        this.clock = clock;
        this.journal = journal;
        this.actions = actions;
        this.notifications = notifications;
        this.config = config;
        this.links = links;
        this.editionContext = editionContext;
        this.editionRepository = editionRepository;
        this.policy = policy;
        this.currentAction = currentAction;
        this.zone = zone;
    }

    /* ------------------------------- Settings -------------------------------- */

    public WeatherSettingsView view() {
        return new WeatherSettingsView(
                repository.settings(),
                repository.state(),
                config.enabled(),
                policy.mayEmit(editionContext.editionIdCourant()));
    }

    /**
     * Saves the settings of this edition. A preset is checked against the
     * edition's own, and the write carries its precondition: a save based on
     * a stale read is refused rather than silently overwriting.
     */
    public WeatherSettingsView save(ParametresMeteo parametres) {
        check(parametres);
        ParametresMeteo before = repository.settings();
        Instant written = repository.save(parametres);
        if (written == null) {
            throw new BusinessError.Stale(
                    "Les réglages de l'alerte météo ont été modifiés par une autre session après leur ouverture "
                            + "ici. Rechargez pour voir ce qui a changé.",
                    repository.settings().modifieLe());
        }
        currentAction.champsModifies(changedFields(before, parametres));
        return view();
    }

    /** The names of what an edit changed, for the history — never the values. */
    static List<String> changedFields(ParametresMeteo before, ParametresMeteo after) {
        List<String> fields = new ArrayList<>();
        if (before.actif() != after.actif()) {
            fields.add("actif");
        }
        if (before.horizonJours() != after.horizonJours()) {
            fields.add("horizonJours");
        }
        if (before.seuilTemperature() != after.seuilTemperature()) {
            fields.add("seuilTemperature");
        }
        if (before.seuilRafales() != after.seuilRafales()) {
            fields.add("seuilRafales");
        }
        if (before.orage() != after.orage()) {
            fields.add("orage");
        }
        if (!java.util.Objects.equals(before.prereglageChaleur(), after.prereglageChaleur())) {
            fields.add("prereglageChaleur");
        }
        if (!java.util.Objects.equals(before.prereglageVent(), after.prereglageVent())) {
            fields.add("prereglageVent");
        }
        if (!java.util.Objects.equals(before.prereglageOrage(), after.prereglageOrage())) {
            fields.add("prereglageOrage");
        }
        return fields;
    }

    private void check(ParametresMeteo parametres) {
        if (parametres == null) {
            throw new BusinessError.Invalid("Les réglages de l'alerte météo sont obligatoires.");
        }
        if (parametres.horizonJours() < 1 || parametres.horizonJours() > ParametresMeteo.HORIZON_MAX) {
            throw new BusinessError.Invalid(
                    "L'horizon de l'alerte météo va de 1 à " + ParametresMeteo.HORIZON_MAX + " jours.");
        }
        if (parametres.seuilTemperature() < 20 || parametres.seuilTemperature() > 50) {
            throw new BusinessError.Invalid("Le seuil de température va de 20 à 50 °C.");
        }
        if (parametres.seuilRafales() < 20 || parametres.seuilRafales() > 200) {
            throw new BusinessError.Invalid("Le seuil de rafales va de 20 à 200 km/h.");
        }
        Set<String> ids =
                consignes.listPrereglages().stream().map(PrereglageConsigne::id).collect(Collectors.toSet());
        for (String id :
                new String[] {parametres.prereglageChaleur(), parametres.prereglageVent(), parametres.prereglageOrage()
                }) {
            if (id != null && !ids.contains(id)) {
                throw new BusinessError.Invalid("Préréglage de consigne inconnu dans cette édition : « " + id + " ».");
            }
        }
    }

    /* --------------------------------- The run -------------------------------- */

    /**
     * The daily run, inside the edition the job entered: query, compare,
     * claim, tell. A service that does not answer is recorded, never thrown —
     * and said once to the admin when it has failed two days in a row.
     */
    public RunResult runForCurrentEdition(Instant now) {
        ParametresMeteo settings = repository.settings();
        if (!settings.actif()) {
            return new RunResult(null, 0);
        }
        WeatherState before = repository.state();
        Plan plan = plan(settings);
        if (plan.dates().isEmpty()) {
            repository.saveState(
                    new WeatherState(WeatherState.Outcome.OUT_OF_FORECAST, now, before.lastReadAt(), null, null));
            return new RunResult(WeatherState.Outcome.OUT_OF_FORECAST, 0);
        }
        if (plan.places().isEmpty()) {
            repository.saveState(new WeatherState(WeatherState.Outcome.NO_PLACE, now, before.lastReadAt(), null, null));
            return new RunResult(WeatherState.Outcome.NO_PLACE, 0);
        }
        Map<Coordinates, Map<LocalDate, DailyForecast>> forecasts;
        try {
            forecasts = query(plan);
        } catch (WeatherUnavailable e) {
            Instant since = before.unreachableSince() != null ? before.unreachableSince() : now;
            repository.saveState(new WeatherState(
                    WeatherState.Outcome.UNREACHABLE, now, before.lastReadAt(), since, e.getMessage()));
            warnWhenLong(since, now, e.getMessage());
            return new RunResult(WeatherState.Outcome.UNREACHABLE, 0);
        }
        repository.saveState(new WeatherState(WeatherState.Outcome.READ, now, now, null, null));
        List<Notification.WeatherAlert.Line> fresh = raise(settings, plan, forecasts);
        if (!fresh.isEmpty()) {
            notifications.fire(new Notification.WeatherAlert(editionName(), List.copyOf(fresh)));
            actions.recordSystemAction("ALERTE_METEO_LEVEE", null);
        }
        return new RunResult(WeatherState.Outcome.READ, fresh.size());
    }

    /**
     * Claims every alert of the forecasts — the levels below its own
     * silently — and returns the lines of those claimed for the first time.
     */
    private List<Notification.WeatherAlert.Line> raise(
            ParametresMeteo settings, Plan plan, Map<Coordinates, Map<LocalDate, DailyForecast>> forecasts) {
        List<Notification.WeatherAlert.Line> fresh = new ArrayList<>();
        Map<LocalDate, ConsigneEdition> enPlace = consignes.byDate();
        Map<String, PrereglageConsigne> presets = presets();
        for (Alert alert : evaluate(settings, plan.places(), forecasts)) {
            for (int palier = 0; palier < alert.level(); palier++) {
                journal.claim(JournalNotificationsRepository.Type.METEO_ALERTE, alert.key(palier), null);
            }
            boolean ahead = alert.date().isAfter(plan.today());
            PrereglageConsigne preset = ahead ? presets.get(presetFor(settings, alert.phenomenon())) : null;
            ConsigneEdition consigne = enPlace.get(alert.date());
            String label = label(alert, preset == null ? null : preset.nom(), consigneName(consigne));
            if (journal.claim(
                    JournalNotificationsRepository.Type.METEO_ALERTE,
                    alert.key(),
                    null,
                    label,
                    JournalNotificationsRepository.Severite.WARNING)) {
                fresh.add(line(alert, label, ahead, preset, consigne));
            }
        }
        return fresh;
    }

    /**
     * One line of the alert: the preset is suggested only where no consigne
     * is in place, and the link only for a date still ahead.
     */
    private Notification.WeatherAlert.Line line(
            Alert alert, String label, boolean ahead, PrereglageConsigne preset, ConsigneEdition consigne) {
        String presetId = preset == null ? null : preset.id();
        String link = ahead
                ? links.consigneScreen(alert.date(), presetId, consigne == null).orElse(null)
                : null;
        return new Notification.WeatherAlert.Line(
                alert.date(),
                alert.phenomenon().code(),
                label,
                alert.value(),
                alert.threshold(),
                alert.level(),
                alert.places().size(),
                consigne == null ? presetId : null,
                link);
    }

    /**
     * One warning, on the second day in a row without an answer: one failure
     * is a network hiccup, two days of them mean nobody is watching the sky
     * for this event any more. Claimed on the first day of the streak, so a
     * streak warns once however long it lasts.
     */
    private void warnWhenLong(Instant since, Instant now, String error) {
        LocalDate first = LocalDate.ofInstant(since, zoneId());
        if (!LocalDate.ofInstant(now, zoneId()).isAfter(first)) {
            return;
        }
        if (journal.claim(
                JournalNotificationsRepository.Type.METEO_INJOIGNABLE,
                "injoignable|" + first,
                null,
                "Le service météo est injoignable depuis le " + DATE.format(first)
                        + " : les prévisions ne sont plus vérifiées. " + error,
                JournalNotificationsRepository.Severite.WARNING)) {
            notifications.fire(new Notification.WeatherUnreachable(editionName(), first, error));
        }
    }

    /* ---------------------------------- Test ---------------------------------- */

    /**
     * Queries now, with this edition's thresholds, whether the alert is on or
     * not — the check before arming it, and the only way to try it on a
     * simulated date. Raises no alert and records nothing.
     */
    public WeatherTestReport test() {
        if (!config.enabled()) {
            throw new BusinessError.Conflict("L'alerte météo est coupée sur cette instance (METEO_ENABLED=false).");
        }
        ParametresMeteo settings = repository.settings();
        Plan plan = plan(settings);
        if (plan.dates().isEmpty()) {
            return new WeatherTestReport(true, false, null, List.of());
        }
        if (plan.places().isEmpty()) {
            return new WeatherTestReport(false, true, null, List.of());
        }
        Map<Coordinates, Map<LocalDate, DailyForecast>> forecasts;
        try {
            forecasts = query(plan);
        } catch (WeatherUnavailable e) {
            return new WeatherTestReport(false, false, e.getMessage(), List.of());
        }
        List<Alert> alerts = evaluate(settings, plan.places(), forecasts);
        List<WeatherDaySummary> days = new ArrayList<>();
        for (Map.Entry<LocalDate, Map<Coordinates, SortedSet<String>>> day :
                plan.places().entrySet()) {
            Integer temperature = null;
            Integer gust = null;
            boolean storm = false;
            for (Coordinates place : day.getValue().keySet()) {
                DailyForecast forecast = forecasts.getOrDefault(place, Map.of()).get(day.getKey());
                if (forecast == null) {
                    continue;
                }
                temperature = max(temperature, forecast.maxTemperature());
                gust = max(gust, forecast.maxGust());
                storm |= forecast.weatherCode() != null && STORM_CODES.contains(forecast.weatherCode());
            }
            List<String> exceeded = alerts.stream()
                    .filter(alert -> alert.date().equals(day.getKey()))
                    .map(alert -> alert.phenomenon().code())
                    .toList();
            days.add(new WeatherDaySummary(
                    day.getKey(), temperature, gust, storm, day.getValue().size(), exceeded));
        }
        return new WeatherTestReport(false, false, null, days);
    }

    /* ---------------------------------- Reads --------------------------------- */

    /**
     * The alerts raised on this edition, newest first, with the preset
     * suggested now — none for a date no longer ahead, where no consigne can
     * be posed.
     */
    public WeatherAlertsView alerts() {
        ParametresMeteo settings = repository.settings();
        LocalDate today = clock.today();
        List<RaisedAlert> raised = new ArrayList<>();
        for (JournalNotificationsRepository.Alerte alerte : journal.alertes(500)) {
            if (!JournalNotificationsRepository.Type.METEO_ALERTE.name().equals(alerte.type())) {
                continue;
            }
            parseKey(alerte.cle())
                    .ifPresent(key -> raised.add(new RaisedAlert(
                            key.date(),
                            key.phenomenon().code(),
                            key.level(),
                            alerte.libelle(),
                            alerte.declencheLe(),
                            key.date().isAfter(today) ? presetFor(settings, key.phenomenon()) : null)));
        }
        return new WeatherAlertsView(settings.actif(), repository.state(), raised);
    }

    /**
     * Where the home screen's « Préparer la consigne » leads, for a list of
     * alerts read at once: the consignes, the settings and today are read
     * here once, not once per alert. See {@link #consigneRoute(String, Set,
     * ParametresMeteo, LocalDate)}.
     */
    public Function<String, Optional<String>> consigneRoutes() {
        Set<LocalDate> underConsigne = consignes.datesSousConsigne();
        ParametresMeteo settings = repository.settings();
        LocalDate today = clock.today();
        return key -> consigneRoute(key, underConsigne, settings, today);
    }

    /**
     * Where « Préparer la consigne » leads for one alert: the consignes tab on
     * its date, the form open with the suggested preset — or the existing
     * consigne of that date, which the alert then names. Empty for a key that
     * is not a weather alert's, and for a date that is not ahead of
     * {@code today}: {@code ConsigneService} poses a consigne on a coming day
     * only, and a form that can only be refused is no suggestion.
     */
    static Optional<String> consigneRoute(
            String key, Set<LocalDate> underConsigne, ParametresMeteo settings, LocalDate today) {
        Optional<AlertKey> parsed = parseKey(key);
        if (parsed.isEmpty() || !parsed.get().date().isAfter(today)) {
            return Optional.empty();
        }
        LocalDate date = parsed.get().date();
        boolean create = !underConsigne.contains(date);
        String preset = create ? presetFor(settings, parsed.get().phenomenon()) : null;
        return Optional.of(ApplicationLinks.consigneRoute(date, preset, create));
    }

    /* --------------------------------- Helpers -------------------------------- */

    /** The day it is, the dates to watch, and the places open on each. */
    record Plan(LocalDate today, List<LocalDate> dates, Map<LocalDate, Map<Coordinates, SortedSet<String>>> places) {}

    private Plan plan(ParametresMeteo settings) {
        List<Creneau> creneaux = referenceData.listCreneaux();
        LocalDate today = clock.today();
        // The real today in the zone JourJClock reads its own in: without a
        // simulated date the two are the same day, even around midnight.
        LocalDate realToday = LocalDate.now(ZoneId.systemDefault());
        List<LocalDate> dates = window(today, realToday, settings.horizonJours(), JoursEvenement.of(creneaux));
        if (dates.isEmpty()) {
            return new Plan(today, dates, Map.of());
        }
        return new Plan(today, dates, placesByDate(dates, referenceData.listSolvedStands(), creneaux));
    }

    private Map<Coordinates, Map<LocalDate, DailyForecast>> query(Plan plan) {
        List<Coordinates> coordinates = plan.places().values().stream()
                .flatMap(places -> places.keySet().stream())
                .distinct()
                .sorted(Comparator.comparingDouble(Coordinates::latitude).thenComparingDouble(Coordinates::longitude))
                .toList();
        LocalDate start =
                plan.places().keySet().stream().min(Comparator.naturalOrder()).orElseThrow();
        LocalDate end =
                plan.places().keySet().stream().max(Comparator.naturalOrder()).orElseThrow();
        return client.forecast(coordinates, start, end, zoneId());
    }

    /**
     * The event's dates in {@code [today ; today + horizon]}, as far as the
     * service forecasts: {@code [real today ; real today + 15]}.
     */
    static List<LocalDate> window(LocalDate today, LocalDate realToday, int horizon, JoursEvenement days) {
        LocalDate from = today.isAfter(realToday) ? today : realToday;
        LocalDate horizonEnd = today.plusDays(horizon);
        LocalDate forecastEnd = realToday.plusDays(FORECAST_DAYS_AHEAD);
        LocalDate to = horizonEnd.isBefore(forecastEnd) ? horizonEnd : forecastEnd;
        return days.jours().stream()
                .filter(date -> !date.isBefore(from) && !date.isAfter(to))
                .sorted()
                .toList();
    }

    /**
     * For each date, the located places of the stands open that day — open
     * on at least one of its timeslots, consignes included — rounded, with
     * the names they carry. A stand without a place, or a place without
     * coordinates, is not watched.
     */
    static Map<LocalDate, Map<Coordinates, SortedSet<String>>> placesByDate(
            List<LocalDate> dates, List<Stand> resolvedStands, List<Creneau> creneaux) {
        Map<LocalDate, Map<Coordinates, SortedSet<String>>> places = new TreeMap<>();
        for (LocalDate date : dates) {
            List<Creneau> ofDay = creneaux.stream()
                    .filter(creneau -> date.equals(creneau.getDate()))
                    .toList();
            for (Stand stand : resolvedStands) {
                Emplacement emplacement = stand.getEmplacement();
                if (emplacement == null || emplacement.getLatitude() == null || emplacement.getLongitude() == null) {
                    continue;
                }
                boolean open = ofDay.stream().anyMatch(creneau -> creneau.isStandOpen(stand));
                if (open) {
                    places.computeIfAbsent(date, ignored -> new LinkedHashMap<>())
                            .computeIfAbsent(
                                    Coordinates.rounded(emplacement.getLatitude(), emplacement.getLongitude()),
                                    ignored -> new TreeSet<>())
                            .add(emplacement.getNom());
                }
            }
        }
        return places;
    }

    /** Every alert the forecast calls for: one per date and phenomenon, at its highest place. */
    static List<Alert> evaluate(
            ParametresMeteo settings,
            Map<LocalDate, Map<Coordinates, SortedSet<String>>> places,
            Map<Coordinates, Map<LocalDate, DailyForecast>> forecasts) {
        List<Alert> alerts = new ArrayList<>();
        for (Map.Entry<LocalDate, Map<Coordinates, SortedSet<String>>> day : new TreeMap<>(places).entrySet()) {
            LocalDate date = day.getKey();
            exceeding(day.getValue(), forecasts, date, DailyForecast::maxTemperature, settings.seuilTemperature())
                    .ifPresent(found -> alerts.add(new Alert(
                            date,
                            Phenomenon.HEAT,
                            (int) Math.round(found.max()),
                            settings.seuilTemperature(),
                            (int) Math.floor((found.max() - settings.seuilTemperature()) / HEAT_STEP),
                            found.places())));
            exceeding(day.getValue(), forecasts, date, DailyForecast::maxGust, settings.seuilRafales())
                    .ifPresent(found -> alerts.add(new Alert(
                            date,
                            Phenomenon.GUST,
                            (int) Math.round(found.max()),
                            settings.seuilRafales(),
                            (int) Math.floor((found.max() - settings.seuilRafales()) / GUST_STEP),
                            found.places())));
            if (settings.orage()) {
                Function<DailyForecast, Double> storm =
                        forecast -> forecast.weatherCode() != null && STORM_CODES.contains(forecast.weatherCode())
                                ? forecast.weatherCode().doubleValue()
                                : null;
                exceeding(day.getValue(), forecasts, date, storm, 95)
                        .ifPresent(found -> alerts.add(new Alert(
                                date,
                                Phenomenon.STORM,
                                (int) found.max(),
                                95,
                                found.max() > 95 ? 1 : 0,
                                found.places())));
            }
        }
        return alerts;
    }

    private record Exceeding(double max, List<String> places) {}

    private static Optional<Exceeding> exceeding(
            Map<Coordinates, SortedSet<String>> places,
            Map<Coordinates, Map<LocalDate, DailyForecast>> forecasts,
            LocalDate date,
            Function<DailyForecast, Double> reading,
            int threshold) {
        double max = Double.NEGATIVE_INFINITY;
        SortedSet<String> names = new TreeSet<>();
        for (Map.Entry<Coordinates, SortedSet<String>> place : places.entrySet()) {
            DailyForecast forecast =
                    forecasts.getOrDefault(place.getKey(), Map.of()).get(date);
            Double value = forecast == null ? null : reading.apply(forecast);
            if (value != null && value >= threshold) {
                max = Math.max(max, value);
                names.addAll(place.getValue());
            }
        }
        return names.isEmpty() ? Optional.empty() : Optional.of(new Exceeding(max, List.copyOf(names)));
    }

    /**
     * The sentence of the home screen and of the mail: the date, what is
     * forecast where, the threshold — and either the preset suggested or the
     * consigne already in place. Places are named (they are not people), at
     * most four.
     */
    static String label(Alert alert, String presetName, String consigneInPlace) {
        String jour = JOUR.format(alert.date());
        StringBuilder sentence =
                new StringBuilder(Character.toUpperCase(jour.charAt(0)) + jour.substring(1)).append(" : ");
        String where = places(alert.places());
        switch (alert.phenomenon()) {
            case HEAT ->
                sentence.append(alert.value())
                        .append(" °C prévus à ")
                        .append(where)
                        .append(" (seuil ")
                        .append(alert.threshold())
                        .append(" °C).");
            case GUST ->
                sentence.append("rafales de ")
                        .append(alert.value())
                        .append(" km/h prévues à ")
                        .append(where)
                        .append(" (seuil ")
                        .append(alert.threshold())
                        .append(" km/h).");
            case STORM ->
                sentence.append(alert.level() > 0 ? "orage avec grêle prévu à " : "orage prévu à ")
                        .append(where)
                        .append('.');
        }
        if (consigneInPlace != null) {
            sentence.append(" Consigne « ").append(consigneInPlace).append(" » déjà en place.");
        } else if (presetName != null) {
            sentence.append(" Suggestion : ").append(presetName).append('.');
        }
        return sentence.toString();
    }

    private static String places(List<String> names) {
        if (names.size() <= PLACES_NAMED) {
            return String.join(", ", names);
        }
        int others = names.size() - PLACES_NAMED;
        return String.join(", ", names.subList(0, PLACES_NAMED)) + " et " + others
                + (others > 1 ? " autres lieux" : " autre lieu");
    }

    private static String consigneName(ConsigneEdition consigne) {
        if (consigne == null) {
            return null;
        }
        return consigne.prereglage() != null ? consigne.prereglage() : consigne.motif();
    }

    static String presetFor(ParametresMeteo settings, Phenomenon phenomenon) {
        return switch (phenomenon) {
            case HEAT -> settings.prereglageChaleur();
            case GUST -> settings.prereglageVent();
            case STORM -> settings.prereglageOrage();
        };
    }

    private Map<String, PrereglageConsigne> presets() {
        Map<String, PrereglageConsigne> byId = new LinkedHashMap<>();
        consignes.listPrereglages().forEach(preset -> byId.put(preset.id(), preset));
        return byId;
    }

    /** A claim key read back: {@code 2026-07-15|HEAT|1}. */
    record AlertKey(LocalDate date, Phenomenon phenomenon, int level) {}

    static Optional<AlertKey> parseKey(String key) {
        if (key == null) {
            return Optional.empty();
        }
        String[] parts = key.split("\\|");
        if (parts.length != 3) {
            return Optional.empty();
        }
        try {
            return Optional.of(
                    new AlertKey(LocalDate.parse(parts[0]), Phenomenon.valueOf(parts[1]), Integer.parseInt(parts[2])));
        } catch (RuntimeException _) {
            return Optional.empty();
        }
    }

    private static Integer max(Integer current, Double value) {
        if (value == null) {
            return current;
        }
        int rounded = (int) Math.round(value);
        return current == null || rounded > current ? rounded : current;
    }

    private String editionName() {
        String id = editionContext.editionIdCourant();
        return editionRepository.listEditions().stream()
                .filter(edition -> edition.getId().equals(id))
                .map(Edition::getNom)
                .findFirst()
                .orElse(id);
    }

    private ZoneId zoneId() {
        try {
            return ZoneId.of(zone);
        } catch (RuntimeException _) {
            return ZoneId.systemDefault();
        }
    }
}
