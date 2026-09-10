package dev.sylvain.planning.domain;

import java.time.LocalTime;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * What the scheduled notifications are allowed to do on one edition (issues
 * #298, #299, #300).
 *
 * <p>{@code actives} is the whole guard rail of the feature, and it is off by
 * default. An {@code Edition} carries neither dates nor an "ongoing" flag, so a
 * nightly job has no way of telling last year's volunteers from this year's:
 * arming an edition is therefore an explicit gesture on the Paramètres screen,
 * never something inferred. Everything else here is a delay, and a delay only
 * matters once something is allowed to leave.</p>
 *
 * @param actives                 nothing at all leaves this edition while false
 * @param heureRappelVeille       local time from which the J-1 reminder may go
 *                                out; the job runs more often than that and
 *                                simply waits for the hour to pass
 * @param delaiRelanceHeures      how long a silence lasts before it becomes a
 *                                reminder, counted from the publication
 * @param ancienneteEchangeJours  how long a swap request may wait for a
 *                                decision before the admin is alerted
 */
@Schema(requiredProperties = {"actives", "ancienneteEchangeJours", "delaiRelanceHeures"})
public record ParametresNotifications(
        boolean actives, LocalTime heureRappelVeille, int delaiRelanceHeures, int ancienneteEchangeJours) {

    /** Late enough that the next day's planning is settled, early enough to be read. */
    public static final LocalTime HEURE_RAPPEL_VEILLE_PAR_DEFAUT = LocalTime.of(18, 0);

    /**
     * Latest sending time the scheduler can honour, and the reason it exists.
     *
     * <p>The reminder for day D goes out on D-1, between {@link
     * #heureRappelVeille()} and midnight — after that the day it announces has
     * begun, and « demain » would be a lie. The job wakes up <b>hourly</b>
     * (see {@code planning.notifications.cron}), so an hour later than 23:00
     * can fall entirely between two runs: the last run of the evening is still
     * too early, the next one has already rolled over to the following day,
     * and the reminder is never sent at all.</p>
     *
     * <p>That silence is the failure this bound exists to prevent. An
     * organiser who sets 23:30, sees it saved and believes the reminders are
     * on has no way of finding out that nothing will ever leave — so the value
     * is refused at the door rather than accepted and quietly ignored.</p>
     */
    public static final LocalTime HEURE_RAPPEL_VEILLE_MAX = LocalTime.of(23, 0);

    /** Three days: long enough for a weekend to pass without chasing anybody. */
    public static final int DELAI_RELANCE_HEURES_PAR_DEFAUT = 72;

    public static final int ANCIENNETE_ECHANGE_JOURS_PAR_DEFAUT = 3;

    /** What an edition that has never been configured answers. */
    public ParametresNotifications() {
        this(
                false,
                HEURE_RAPPEL_VEILLE_PAR_DEFAUT,
                DELAI_RELANCE_HEURES_PAR_DEFAUT,
                ANCIENNETE_ECHANGE_JOURS_PAR_DEFAUT);
    }
}
