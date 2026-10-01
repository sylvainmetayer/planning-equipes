package dev.sylvain.planning.service.analyse;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.service.notification.JournalNotificationsRepository;
import dev.sylvain.planning.service.referentiel.ReferenceDataService;
import dev.sylvain.planning.service.weather.WeatherAlertService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * The alerts left by the scheduled jobs, resolved for the recent messages of the home screen.
 *
 * <p>Its only real job is the last step: the journal stores an
 * {@code animateur_id} and never a name, so the identity is joined <b>here</b>,
 * at read time, from the referential. That is what keeps a person's name out of
 * a table that survives them — an animateur deleted from the referential leaves
 * an alert that no longer names anybody, which is the correct outcome rather
 * than a bug (see {@code docs/rgpd.md}).</p>
 */
@ApplicationScoped
public class AlerteService {

    /**
     * Alerts kept <b>per type</b>, not in total: one noisy kind must not push
     * another off the screen. Fifty covers an event week of a given kind, and
     * the count stays small enough to read.
     */
    private static final int LIMITE_PAR_DEFAUT = 50;

    private static final int LIMITE_MAX = 500;

    private final JournalNotificationsRepository journal;

    private final ReferenceDataService referenceDataService;

    private final WeatherAlertService weather;

    @Inject
    public AlerteService(
            JournalNotificationsRepository journal,
            ReferenceDataService referenceDataService,
            WeatherAlertService weather) {
        this.journal = journal;
        this.referenceDataService = referenceDataService;
        this.weather = weather;
    }

    /**
     * One alert as the screen shows it.
     *
     * @param type       which job raised it — the screen groups and icons on it
     * @param nomAffiche the person concerned, resolved now from the
     *                   referential; {@code null} when the alert is about no
     *                   one in particular, or when the fiche is gone
     * @param lien       where the alert is acted upon, an address of the
     *                   application — today the consignes of a weather
     *                   alert's date, the form open on the suggested preset;
     *                   {@code null} for the others
     */
    public record AlerteView(
            String type,
            String cle,
            Instant declencheLe,
            String libelle,
            String severite,
            String animateurId,
            String nomAffiche,
            String lien) {}

    /**
     * @param limite how many alerts to bring back <b>of each type</b>; clamped,
     *               so a client asking for a million gets the cap rather than
     *               the database
     */
    public List<AlerteView> alertes(Integer limite) {
        int plafond = limite == null || limite <= 0 ? LIMITE_PAR_DEFAUT : Math.min(limite, LIMITE_MAX);
        List<JournalNotificationsRepository.Alerte> alertes = journal.alertes(plafond);
        if (alertes.isEmpty()) {
            return List.of();
        }
        Map<String, String> noms = new LinkedHashMap<>();
        for (Animateur animateur : referenceDataService.listAnimateurs()) {
            noms.put(animateur.getId(), animateur.nomAffiche());
        }
        // Read once for the whole list, and only when a weather alert is in it.
        Function<String, Optional<String>> routes = alertes.stream().anyMatch(AlerteService::isWeather)
                ? weather.consigneRoutes()
                : key -> Optional.empty();
        List<AlerteView> vues = new ArrayList<>();
        for (JournalNotificationsRepository.Alerte alerte : alertes) {
            vues.add(new AlerteView(
                    alerte.type(),
                    alerte.cle(),
                    alerte.declencheLe(),
                    alerte.libelle(),
                    alerte.severite(),
                    alerte.animateurId(),
                    alerte.animateurId() == null ? null : noms.get(alerte.animateurId()),
                    isWeather(alerte) ? routes.apply(alerte.cle()).orElse(null) : null));
        }
        return List.copyOf(vues);
    }

    /** A weather alert's link is read now, not stored: the preset suggested and the consigne in place are today's. */
    private static boolean isWeather(JournalNotificationsRepository.Alerte alerte) {
        return JournalNotificationsRepository.Type.METEO_ALERTE.name().equals(alerte.type());
    }
}
