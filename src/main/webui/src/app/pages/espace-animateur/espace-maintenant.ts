// « Où j'en suis, là, maintenant » (issue #535).
//
// The espace planning page is the only screen of the product read DURING the
// event, standing, on a phone. Rendering every day alike is the right form to
// prepare an event, not to live it: on Saturday at 1pm the animateur scrolls
// past Friday — which they have nothing left to do with — to find their
// afternoon.
//
// Pure functions, and the clock comes in as an argument: the browser's is the
// one this page reads, and a helper that read it itself could not be tested on
// the one case that matters — a machine whose timezone puts the current day on
// another UTC date. The server only ever overrides its date, and only in
// development: see `maintenantEffectif`.

import { toDateKey } from '../../core/date-utils';
import { PauseAnimateurView, PosteAnimateurView } from '../../core/models';

/** One day of the animateur's planning, as the page groups it. */
export interface JourPlanning {
  /** ISO date, `''` for postes without one. */
  date: string;
  postes: PosteAnimateurView[];
  /** True for an event day without any seat: the card says « Repos » instead of listing shifts. */
  repos: boolean;
  /** The legal breaks this day owes — « 20 min at the latest at 19:00 » — in deadline order. */
  pauses: PauseAnimateurView[];
}

/** What the head block says. `null` outside the event: see {@link repereMaintenant}. */
export interface RepereMaintenant {
  /** The seat being held right now, `null` between two of them. */
  enCours: PosteAnimateurView | null;
  /** The first seat still to come, `null` once the last one has started. */
  prochain: PosteAnimateurView | null;
  /** True when today is one of their « Repos » days — an answer, not a hole. */
  reposAujourdhui: boolean;
  /**
   * The breaks still ahead: « pause à prendre au plus tard à 19:00 » only
   * serves while its window is open — the day card below goes on listing every
   * break of the day.
   *
   * <p>Not only today's. A break dated yesterday evening can end after
   * midnight, and is then still to be taken this morning — which is why this
   * is named for what is left rather than for the day it is read on.</p>
   */
  remainingPauses: PauseAnimateurView[];
  /** Today, as an ISO date — what the page anchors the reading on. */
  aujourdhui: string;
  /**
   * The earliest day the page must leave unfolded: today, except while a shift
   * started yesterday is still running past midnight — folding away the day of
   * the shift somebody is working is the defect this exists to avoid.
   */
  jourPlancher: string;
}

/**
 * Today, from the browser's own clock and its own timezone.
 *
 * <p>Never `toISOString()`: that one answers in UTC, and a phone set to UTC+14
 * would call the current day « yesterday » for its first fourteen hours —
 * folding away, as an elapsed day, the very day being lived.</p>
 */
export function aujourdhuiLocal(maintenant: Date): string {
  return toDateKey(maintenant);
}

/**
 * The moment the marker reasons on: the browser's own, unless a developer froze
 * the server's clock (`/api/horloge`).
 *
 * <p>The same rule as the mode jour J screen: the frozen date replaces the
 * phone's, and the time of day follows the phone's unless it was frozen too —
 * a frozen day alone must still see its seats fall behind as the afternoon goes
 * on. A time is never read without its date, and a malformed value is ignored
 * rather than trusted: a marker on « Invalid Date » folds every day away.</p>
 */
export function maintenantEffectif(
  horloge: Date,
  dateFigee: string | null,
  heureFigee: string | null = null,
): Date {
  const jour = /^(\d{4})-(\d{2})-(\d{2})$/.exec(dateFigee ?? '');
  if (!jour) {
    return horloge;
  }
  const heure = /^(\d{2}):(\d{2})(?::(\d{2}))?$/.exec(heureFigee ?? '');
  return new Date(
    Number(jour[1]),
    Number(jour[2]) - 1,
    Number(jour[3]),
    heure ? Number(heure[1]) : horloge.getHours(),
    heure ? Number(heure[2]) : horloge.getMinutes(),
    heure ? Number(heure[3] ?? 0) : horloge.getSeconds(),
  );
}

/** `HH:mm`, the shape both sides of every comparison below are reduced to. */
function heureCourte(heure: string | null): string | null {
  return heure ? heure.slice(0, 5) : null;
}

function heureMaintenant(maintenant: Date): string {
  return `${String(maintenant.getHours()).padStart(2, '0')}:${String(
    maintenant.getMinutes(),
  ).padStart(2, '0')}`;
}

/** The day before, through the calendar rather than through string arithmetic. */
function veille(maintenant: Date): string {
  const hier = new Date(maintenant.getFullYear(), maintenant.getMonth(), maintenant.getDate() - 1);
  return toDateKey(hier);
}

/** `2026-07-12` from `2026-07-11`, through the calendar. */
function lendemain(date: string): string {
  const [annee, mois, jourDuMois] = date.split('-').map(Number);
  return toDateKey(new Date(annee, mois - 1, jourDuMois + 1));
}

/**
 * When a break really ends, as `AAAA-MM-JJTHH:mm` — comparable as text.
 *
 * <p>A break is dated on the day of the seat it falls in, and a seat past
 * midnight is dated on the evening that opens it. So « 23:50–00:10 » and
 * « 00:30–00:50 » inside a 22:00–02:00 seat both end on the next calendar day:
 * compared as bare hours, both read as over at 23:00 — while still ahead.</p>
 */
function breakRealEnd(pause: PauseAnimateurView, jour: JourPlanning): string {
  const debut = heureCourte(pause.debut)!;
  const fin = heureCourte(pause.fin)!;
  const pastMidnight =
    traverseMinuit(debut, fin) ||
    jour.postes.some((poste) => {
      const debutPoste = heureCourte(poste.heureDebut);
      const finPoste = heureCourte(poste.heureFin);
      return (
        !!debutPoste &&
        traverseMinuit(debutPoste, finPoste) &&
        debut < debutPoste &&
        fin <= finPoste!
      );
    });
  return `${pastMidnight ? lendemain(pause.date) : pause.date}T${fin}`;
}

/**
 * True for « 22:00–02:00 »: a seat whose end does not follow its start runs
 * past midnight. The plan dates it on the evening that opens it — the product
 * rule the whole application reads it by — so its last hours fall on a day the
 * espace would otherwise call yesterday.
 */
function traverseMinuit(debut: string, fin: string | null): boolean {
  return fin !== null && fin <= debut;
}

/**
 * True while today falls between the first and the last day of this planning,
 * rest days included.
 *
 * Outside it the page goes back to its plain form: before the event, « votre
 * prochain poste » would announce in July what is read in March; after it,
 * there is no next seat and nothing to fold — a head block would only be a way
 * of saying « it's over » to somebody who knows.
 */
export function duringTheEvent(jours: JourPlanning[], aujourdhui: string): boolean {
  const dates = jours.map((jour) => jour.date).filter((date) => !!date);
  if (dates.length === 0) {
    return false;
  }
  return aujourdhui >= dates[0] && aujourdhui <= (dates.at(-1) ?? dates[0]);
}

/** True for a day already over — strictly before today, never « ends in an hour ». */
export function isPasse(jour: JourPlanning, aujourdhui: string): boolean {
  return !!jour.date && jour.date < aujourdhui;
}

/**
 * Where one seat stands against now: being held, still ahead, or neither
 * (over, or undated).
 */
function situerPoste(
  poste: PosteAnimateurView,
  jourDate: string | null,
  now: { aujourdhui: string; hier: string; heure: string },
): 'enCours' | 'prochain' | null {
  const { aujourdhui, hier, heure } = now;
  const date = poste.date ?? jourDate;
  const debut = heureCourte(poste.heureDebut);
  const fin = heureCourte(poste.heureFin);
  if (!date || !debut) {
    return null;
  }
  if (date < aujourdhui) {
    // Only one thing from before today can still be running: a shift that
    // crossed midnight into it, and only until its own end.
    return date === hier && traverseMinuit(debut, fin) && heure < fin! ? 'enCours' : null;
  }
  if (date > aujourdhui) {
    return 'prochain';
  }
  const finie = !traverseMinuit(debut, fin) && fin !== null && fin <= heure;
  if (debut <= heure && !finie) {
    return 'enCours';
  }
  return debut > heure ? 'prochain' : null;
}

/**
 * The seat in progress, the next one, and what today owes — `null` outside the
 * event, where the page has nothing truthful to put in a head block.
 *
 * <p>The days are expected sorted, which is how the page builds them.</p>
 *
 * <p>A night shift is read on the day that opens it, and that day is not
 * always the day it is being worked: at 00:30, « 22h–02h » is dated yesterday
 * and still held. Both halves of the answer follow from that — it is the seat
 * in progress, and its day must stay unfolded while it lasts ({@code
 * jourPlancher}). Reading it as a shift that ended twenty hours ago is how a
 * night shift disappears from the screen of the person doing it.</p>
 */
export function repereMaintenant(jours: JourPlanning[], maintenant: Date): RepereMaintenant | null {
  const aujourdhui = aujourdhuiLocal(maintenant);
  if (!duringTheEvent(jours, aujourdhui)) {
    return null;
  }
  const heure = heureMaintenant(maintenant);
  const hier = veille(maintenant);
  let held: PosteAnimateurView | null = null;
  let prochain: PosteAnimateurView | null = null;
  for (const jour of jours) {
    for (const poste of jour.postes) {
      const place = situerPoste(poste, jour.date, { aujourdhui, hier, heure });
      if (place === 'enCours') {
        held ??= poste;
      } else if (place === 'prochain') {
        prochain ??= poste;
      }
    }
  }
  const jourAujourdhui = jours.find((jour) => jour.date === aujourdhui);
  // Yesterday's breaks too: one that crosses midnight, or falls after it in a
  // night seat, is still ahead this morning while its own end is.
  const instant = `${aujourdhui}T${heure}`;
  const remainingPauses = jours
    .filter((jour) => jour.date === hier || jour.date === aujourdhui)
    .flatMap((jour) => jour.pauses.filter((pause) => breakRealEnd(pause, jour) > instant));
  const dateTenue = held ? (held.date ?? aujourdhui) : aujourdhui;
  return {
    enCours: held,
    prochain,
    reposAujourdhui: !!jourAujourdhui?.repos,
    remainingPauses,
    aujourdhui,
    jourPlancher: dateTenue < aujourdhui ? dateTenue : aujourdhui,
  };
}
