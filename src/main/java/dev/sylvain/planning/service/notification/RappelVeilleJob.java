package dev.sylvain.planning.service.notification;

import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jboss.logging.Logger;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.ParametresNotifications;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.service.ApplicationLinks;
import dev.sylvain.planning.service.PlanPublieService;
import dev.sylvain.planning.service.PublicationDiffService;
import dev.sylvain.planning.service.ReferenceDataService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;

/**
 * « Vous êtes attendu·e demain » (issue #298).
 *
 * <p>Reads the <b>published</b> plan and no other. That is the whole point: a
 * solve run last night, a repair applied this morning or a validated échange
 * all move the working plan, and reminding somebody of a seat nobody ever
 * communicated to them would be the application announcing a change at 6 pm, by
 * mail, with no human having reviewed it. What this job repeats is what was
 * already sent.</p>
 *
 * <p><b>Idempotence is claimed, not checked.</b> One key per (animateur, day)
 * is taken in {@link JournalNotificationsRepository} before the notification is
 * fired, and the mail goes out only for the call that won the insert — so the
 * job may run hourly, twice in a row or after a restart without anybody
 * receiving the same reminder twice.</p>
 *
 * <p>An animateur with no address on their fiche is skipped, and the fact is
 * recorded as an alert of the Notifications screen: the organiser is the one
 * who can pick up a phone, and dropping the person silently would be the worst
 * of the three possible behaviours.</p>
 */
@ApplicationScoped
public class RappelVeilleJob {

    private static final Logger LOG = Logger.getLogger(RappelVeilleJob.class);

    @Inject
    PlanPublieService planPublieService;

    @Inject
    ReferenceDataService referenceDataService;

    @Inject
    PublicationDiffService diffService;

    @Inject
    ApplicationLinks liens;

    @Inject
    JournalNotificationsRepository journal;

    @Inject
    Event<Notification> notifications;

    /**
     * Sends tomorrow's reminders, if the edition's own sending time has passed.
     *
     * @param maintenant current time in the deployment's notification zone —
     *                   passed in rather than read here, so the whole run shares
     *                   one clock and a test can place itself in the day
     * @return how many reminders actually left
     */
    public int run(ParametresNotifications parametres, ZonedDateTime maintenant) {
        if (maintenant.toLocalTime().isBefore(parametres.heureRappelVeille())) {
            return 0;
        }
        LocalDate demain = maintenant.toLocalDate().plusDays(1);
        Map<String, List<PosteAffectation>> parAnimateur = postesOf(demain);
        if (parAnimateur.isEmpty()) {
            return 0;
        }

        Map<String, Animateur> fiches = new LinkedHashMap<>();
        for (Animateur animateur : referenceDataService.listAnimateurs()) {
            fiches.put(animateur.getId(), animateur);
        }

        int envoyes = 0;
        for (Map.Entry<String, List<PosteAffectation>> entree : parAnimateur.entrySet()) {
            Animateur fiche = fiches.get(entree.getKey());
            if (fiche == null) {
                continue;
            }
            envoyes += remind(fiche, demain, entree.getValue()) ? 1 : 0;
        }
        LOG.debugf("Day-before reminders: %d sent for %s", envoyes, demain);
        return envoyes;
    }

    /**
     * One person's reminder.
     *
     * @return true when a mail was actually fired — false when it had already
     *         gone out, or when there is nobody to write to
     */
    private boolean remind(Animateur fiche, LocalDate demain, List<PosteAffectation> postes) {
        String cle = fiche.getId() + "|" + demain;
        if (fiche.getEmail() == null || fiche.getEmail().isBlank()) {
            // Not a failure to retry: a fiche without an address stays without
            // one until somebody edits it, so the claim also stops this alert
            // from reappearing every hour.
            journal.claim(JournalNotificationsRepository.Type.RAPPEL_VEILLE_INJOIGNABLE, cle, fiche.getId(),
                    "Rappel de la veille impossible : aucune adresse e-mail sur la fiche."
                            + " Cette personne est affectée le " + demain + " et doit être prévenue à la main.",
                    JournalNotificationsRepository.Severite.WARNING);
            return false;
        }
        if (!journal.claim(JournalNotificationsRepository.Type.RAPPEL_VEILLE, cle, fiche.getId())) {
            return false;
        }
        notifications.fire(new Notification.RappelVeille(
                fiche.getEmail(), fiche.getPrenom(), demain, lines(postes),
                liens.espaceAnimateur(fiche.getAccessToken()).orElse(null)));
        return true;
    }

    /** « Cirque 14h-18h », in the order the day is worked. */
    private List<String> lines(List<PosteAffectation> postes) {
        List<String> lines = new ArrayList<>();
        for (PosteAffectation poste : postes) {
            lines.add(diffService.libelleCreneauSeul(new PublicationDiffService.Vacation(
                    poste.getCreneau().getDate(), poste.heureDebutEffectif(), poste.heureFinEffectif(),
                    poste.getStand().getId(), poste.getStand().getNom())));
        }
        return List.copyOf(lines);
    }

    /**
     * Tomorrow's seats of the published plan, per animateur id.
     *
     * <p>A seat with no animateur is skipped, and that check carries weight:
     * since deleting an animateur empties their seats instead of removing them,
     * an unassigned {@link PosteAffectation} <b>is</b> a vacancy — there is
     * nobody to remind about it.</p>
     */
    private Map<String, List<PosteAffectation>> postesOf(LocalDate jour) {
        PlanningEvenement publie = planPublieService.planPublie();
        Map<String, List<PosteAffectation>> parAnimateur = new LinkedHashMap<>();
        for (PosteAffectation poste : publie.getPostes()) {
            if (poste.getAnimateur() == null || poste.getStand() == null || poste.getCreneau() == null
                    || !jour.equals(poste.getCreneau().getDate())) {
                continue;
            }
            parAnimateur.computeIfAbsent(poste.getAnimateur().getId(), unused -> new ArrayList<>()).add(poste);
        }
        for (List<PosteAffectation> postes : parAnimateur.values()) {
            postes.sort(Comparator.comparing(PosteAffectation::heureDebutEffectif,
                    Comparator.nullsLast(Comparator.naturalOrder())));
        }
        return parAnimateur;
    }
}
