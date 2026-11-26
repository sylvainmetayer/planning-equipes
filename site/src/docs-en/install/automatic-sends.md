---
twin: installer/envois-automatiques
---
# Tune the automatic sends

You set the scheduled task that sends the day-before reminder, the follow-ups and the alerts, you activate the right edition, and you know in which order to check when "nobody received anything".

Four sends share a single task: the day-before reminder, the follow-up of staff members who have not acknowledged their schedule, the alert on swap requests waiting for a decision, and the follow-up of the availability collection.

## 1. Set the rhythm and the time zone

```properties
NOTIFICATIONS_CRON=0 5 * * * ?
NOTIFICATIONS_TIMEZONE=Europe/Paris
```

![The hourly send of scheduled notifications](repo:docs/diagrammes/notifications-planifiees.svg)

<sub>Source: [`diagrammes/notifications-planifiees.puml`](repo:docs/diagrammes/notifications-planifiees.puml).</sub>

The cron is hourly, on purpose. The time the reminder goes out is decided **per edition**, in *Settings*, *Notifications* tab, *Automatic reminders and follow-ups* card; a single task cannot leave at four different times for four editions. It therefore wakes up often, and each edition sends once its own time has passed. This is harmless because every send is reserved in the database before it leaves: running twelve times in the evening writes to nobody twice.

The time zone is that of the event, set separately (`NOTIFICATIONS_TIMEZONE`), and not that of the container (`TZ`, see [back up and restore](sauvegarde-et-restauration.md#1-laisser-la-sauvegarde-de-nuit-tourner)): "18:00" on the screen must mean 18:00 for the person reading it, whatever the time zone of the machine.

**The reminder's send time is capped at 23:00**, and the screen refuses later. The sending window runs from the set time to midnight: after that, the announced day has begun and "tomorrow" would be wrong. A later time would leave a window shorter than the interval between two runs, and the reminder would fall between two wake-ups without leaving or saying anything. If you space `NOTIFICATIONS_CRON` beyond one run per hour, this cap is no longer enough to guarantee it.

## 2. Activate the edition that must write

**Nothing leaves except from the active edition** ([ADR 0072](repo:docs/decisions/0072-une-seule-edition-active.md)). The *Editions* page carries the "Activate" action; at most one edition is active, and none between two events. It is the only safeguard: an edition carries neither dates nor an "ongoing" flag, so without it the first night after going into production would remind last year's staff that they are expected tomorrow. Every edition is born inactive (creation, duplication, import): someone must decide to activate it.

**On updating to `V118`**, the active edition is chosen as follows: the only armed edition if there was exactly one, otherwise the default edition, otherwise the oldest. The choice is written in the startup log (a Flyway `NOTICE`): check it on the *Editions* page before the first night.

The "Action required" banner of the administration flags an active edition whose last day has passed, or an inactive edition that starts within the next `EDITIONS_RAPPEL_JOURS` days (7 by default).

## 3. Check when nothing arrives

In the order in which things break:

1. The active edition. If the event's edition was not activated (step 2), nothing leaves and nothing signals it: it is the cause of "I received nothing" in nine cases out of ten.
2. The plan must be published. The reminder reads the **published** schedule, never the working plan: nobody is reminded of a timeslot that has not been communicated to them.
3. Records without an address. They are skipped, counted in "To handle today" on the home page and named in its "Recent messages": these are the people to warn by hand.
4. Failed sends. A reminder or follow-up that the relay refused leaves a red alert in the home page's "Recent messages", and the person is **not counted as reminded**: they stay "Failed to send" on the Staff page. The night does not retry, because a relay that is down at 19:00 often still is at 20:00 and rewriting every hour would help nothing. "Remind now" stays open for them once the relay is back, whatever e-mail has gone out since, and two simultaneous clicks write only once. The one exception: a refused address, whose reminder resumes by itself at the run following the correction of the record ([the failure categories](courriels.md#4-lire-ce-qui-est-gardé-de-chaque-envoi)).

## 4. The availability collection follow-up

It is switched on with the collection window, in *Settings*, *Edition* tab, *Counters* card. It is **off by default**, including on a collection already open before the update. When on, it writes once to each **invited** staff member with **no declaration at all**, with the link to the "My availability" page of their espace. The organiser-side guide is [Collect availability](../regles/collecter-disponibilites.md).

| Rule | Why |
| --- | --- |
| Invited = an invitation to declare that **was sent** (`envoi_mail`, template `invitation-declaration`, state `ENVOYE`) | It is the only trace of the invitation; a failed invitation taught the person nothing. Beyond `JOURNAL_RETENTION`, the line is purged and the person no longer counts as invited |
| No declaration = none, whatever its status | An applied or refused declaration is an answer |
| At the first run at 09:00 or later (`NOTIFICATIONS_TIMEZONE`) of a day whose end is in 0 to 3 days | Nobody reads a midnight e-mail. With the shipped hourly cron, this is the 09:05 run; a `NOTIFICATIONS_CRON` that never runs at 09:00 or after (only once at night, for example) **sends no follow-up**. A day without a run is made up the next day, up to the last day of the collection |
| Once per person and per end date (key `animateur\|fin`) | Moving the end is a new deadline: a new follow-up becomes possible |
| Nothing without an end date, with a closed collection or an inactive edition | No deadline to remind, nobody who can still answer, or not the event under way ([ADR 0072](repo:docs/decisions/0072-une-seule-edition-active.md)) |

A failed send is not retried every hour: each day reserves its own attempt (key `animateur|fin|jour`), but the failure gives back the reservation of the deadline, so that the next day's 09:00 run retries, up to the last day of the collection. It leaves a red alert in the home page's "Recent messages", one per deadline. An address that the mail server has refused is no longer tried as long as the record has not been modified, and a record without an address is not tried at all: each of these two cases leaves a warning, once per deadline, that designates the record without naming it. Every send, sent or failed, is recorded in `envoi_mail` under the `relance-collecte` template.
