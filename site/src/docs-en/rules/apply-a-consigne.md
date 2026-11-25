---
twin: regles/appliquer-une-consigne
---
# Apply a consigne from the authority

An arrêté closes stands at certain hours on a few days: you set it once for the whole event, without destroying anything in the grid, and you lift it the same way.

A **consigne** (an official order, such as an arrêté, relayed to the whole event) is a closure imposed from outside (prefectural order, heatwave) and applied to all stands for a few dates. It is managed in *Planning rules*, *Consignes* tab.

Outdoor events are the first concerned: see [when the authority or the weather steps in](/planning-evenement-sportif/) for a sports event.

## Set a consigne

1. Open *Planning rules*, *Consignes* tab, and choose the time band and the upcoming dates.
2. Review the proposal: the application ticks the stands that lose hours, and you can reopen them on **compensation** windows (evening, morning).
3. Read the **figures preview** that comes before every setting, extension or lifting.
4. Apply.
5. Re-run the solve: the application offers a partial correction on the spot.

![The consignes of an edition](/images/fr/docs/consignes.webp "The consignes set on an edition")

The consigne destroys nothing in the grid. The closed shifts lose their seats, and the only timeslots added are those the reopenings require.

There are also shortcuts:

- "Close stands tomorrow" is offered on the Solver and on the Today screen;
- a **named preset** ("Heatwave plan") makes each wave identical down to the gesture.

## What the staff see

The e-mail, the espace and the PDF say "hours changed" with the reason. A publication does the rest; see [Publish the schedule](publier-planning.md).

## Lift a consigne

Lifting gives their hours back to the holders of the closed seats. It is previewed like a setting. A past day keeps for ever the consigne that governed it.

## Start from a weather alert

Every morning, the application reads the Open-Meteo forecast for the event's places. When heat, gusts or storms cross the edition's thresholds, it alerts the organisation (on the home page, by e-mail and by webhook) and suggests the consigne preset planned for that phenomenon.

- "Prepare the consigne" opens the form already filled in on the right date. Nothing is applied without your review.
- A given forecast rings only once, and again only if it gets worse.
- The alert is switched on and set per edition, in Settings, *Edition* tab (thresholds per phenomenon, suggested preset). It assumes the host has not turned it off for the instance (see [Allow webhooks and the weather alert](../installer/appels-sortants.md)).

## A same-day fallback plan without a consigne

To try a real variant (another grid, another team), duplicate the edition rather than setting a consigne: see [Chain editions](enchainer-les-editions.md#essayer-une-variante).
