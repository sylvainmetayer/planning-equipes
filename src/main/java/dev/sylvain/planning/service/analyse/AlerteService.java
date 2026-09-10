package dev.sylvain.planning.service.analyse;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.service.notification.JournalNotificationsRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import dev.sylvain.planning.service.referentiel.ReferenceDataService;

/**
 * The alerts left by the scheduled jobs, resolved for the Notifications screen.
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

    @Inject
    JournalNotificationsRepository journal;

    @Inject
    ReferenceDataService referenceDataService;

    /**
     * One alert as the screen shows it.
     *
     * @param type       which job raised it — the screen groups and icons on it
     * @param nomAffiche the person concerned, resolved now from the
     *                   referential; {@code null} when the alert is about no
     *                   one in particular, or when the fiche is gone
     */
    public record AlerteView(String type, String cle, Instant declencheLe, String libelle, String severite,
            String animateurId, String nomAffiche) {
    }

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
        List<AlerteView> vues = new ArrayList<>();
        for (JournalNotificationsRepository.Alerte alerte : alertes) {
            vues.add(new AlerteView(alerte.type(), alerte.cle(), alerte.declencheLe(), alerte.libelle(),
                    alerte.severite(), alerte.animateurId(),
                    alerte.animateurId() == null ? null : noms.get(alerte.animateurId())));
        }
        return List.copyOf(vues);
    }
}
