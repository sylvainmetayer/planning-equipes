// « Se représenter l'ensemble » (issue #615): the four figures of a planning,
// and the frieze that puts all its days on one screen.
//
// Pure, and the reference day comes in as an argument, for the same reason as
// `espace-maintenant`: a helper reading the clock itself could not be tested
// on the case that matters — a phone whose timezone puts today on another UTC
// date. Nothing here draws: it returns percentages, and the template turns
// them into bars.

import { JourPlanning } from './espace-maintenant';

/** Minutes since midnight, from the `10:00:00` the API sends. `null` when unreadable. */
export function minutesSinceMidnight(heure: string | null | undefined): number | null {
  const lu = /^(\d{2}):(\d{2})/.exec(heure ?? '');
  return lu ? Number(lu[1]) * 60 + Number(lu[2]) : null;
}

/**
 * The end of a shift, in minutes, on the scale its start is read on.
 *
 * <p>A shift whose end does not follow its start runs past midnight —
 * « 22:00–02:00 » — and the plan dates it on the evening that opens it, the
 * product rule the whole application reads it by. On a frieze whose axis is
 * one day, that end belongs after its start, not twenty hours before it.</p>
 */
export function finSurLAxe(debut: number, fin: number): number {
  return fin <= debut ? fin + 24 * 60 : fin;
}

/**
 * Whether a break falls inside a seat: same stand, and a window the seat
 * contains — read on the seat's own axis, so a shift running past midnight
 * holds the breaks of the small hours.
 *
 * <p>A bare `HH:mm` comparison drops them: 00:30 is « before » 22:00 on the
 * clock, and a legal break would leave the shift it cuts into to be listed
 * apart, as if no seat carried it.</p>
 */
export function pauseInsidePoste(
  pause: { standId: string | null; debut: string | null; fin: string | null },
  poste: { standId: string | null; heureDebut: string | null; heureFin: string | null },
): boolean {
  if (pause.standId !== poste.standId) {
    return false;
  }
  const debut = minutesSinceMidnight(poste.heureDebut);
  const finBrute = minutesSinceMidnight(poste.heureFin);
  const pauseDebut = minutesSinceMidnight(pause.debut);
  const pauseFin = minutesSinceMidnight(pause.fin);
  if (debut === null || finBrute === null || pauseDebut === null || pauseFin === null) {
    return false;
  }
  const fin = finSurLAxe(debut, finBrute);
  // Only a shift that really crosses midnight may pull an earlier clock time
  // forward: without that guard, a morning break would be read as belonging
  // to the following night's shift.
  const traverseMinuit = fin > 24 * 60;
  const debutPause = traverseMinuit && pauseDebut < debut ? pauseDebut + 24 * 60 : pauseDebut;
  const finPause = pauseFin < debutPause ? pauseFin + 24 * 60 : pauseFin;
  return debutPause >= debut && finPause <= fin;
}

/** The horizontal axis of the frieze: the span its bars are drawn on. */
export interface AxeFrise {
  /** Minutes since midnight of the left edge, always on the hour. */
  debut: number;
  /** Minutes since midnight of the right edge, always on the hour. */
  fin: number;
  /** The hours to label, in minutes since midnight — left edge and right edge included. */
  graduations: number[];
}

/** The axis a planning with no readable hour falls back on: an ordinary event day. */
const DEFAULT_AXE = { debut: 9 * 60, fin: 20 * 60 };

/** Below this, a one-hour shift would fill the whole frieze and say nothing. */
const MIN_AMPLITUDE = 4 * 60;

/**
 * The axis the days are read on: the first start and the last end of the whole
 * planning, each to its own hour.
 *
 * <p>Derived rather than fixed at 9h–20h: a night shift drawn on a daytime axis
 * would fall off it, and a planning of three mornings drawn on twelve hours is
 * three thin bars at the left of an empty strip.</p>
 */
export function axeFrise(jours: readonly JourPlanning[]): AxeFrise {
  let debut: number | null = null;
  let fin: number | null = null;
  for (const jour of jours) {
    for (const poste of jour.postes) {
      const depart = minutesSinceMidnight(poste.heureDebut);
      const arrivee = minutesSinceMidnight(poste.heureFin);
      if (depart === null || arrivee === null) {
        continue;
      }
      debut = debut === null ? depart : Math.min(debut, depart);
      fin = fin === null ? finSurLAxe(depart, arrivee) : Math.max(fin, finSurLAxe(depart, arrivee));
    }
  }
  if (debut === null || fin === null) {
    return {
      ...DEFAULT_AXE,
      graduations: graduations(DEFAULT_AXE.debut, DEFAULT_AXE.fin),
    };
  }
  const gauche = Math.floor(debut / 60) * 60;
  let droite = Math.ceil(fin / 60) * 60;
  if (droite - gauche < MIN_AMPLITUDE) {
    droite = gauche + MIN_AMPLITUDE;
  }
  return { debut: gauche, fin: droite, graduations: graduations(gauche, droite) };
}

/**
 * At most six labels, on whole hours, both edges included: more than that and
 * they collide on a 390 px screen.
 */
function graduations(debut: number, fin: number): number[] {
  const heures = (fin - debut) / 60;
  const pas = Math.max(1, Math.ceil(heures / 5));
  const valeurs: number[] = [];
  for (let minute = debut; minute < fin; minute += pas * 60) {
    valeurs.push(minute);
  }
  valeurs.push(fin);
  return valeurs;
}

/** One shift of the frieze, placed on the axis in percentages of its width. */
export interface BarreFrise {
  gauchePct: number;
  largeurPct: number;
  standNom: string;
  typologieId: string | null;
  typologieLibelle: string | null;
  heureDebut: string;
  heureFin: string;
}

/** One day of the frieze: one row of the « Aperçu » tab. */
export interface LigneFrise {
  date: string;
  barres: BarreFrise[];
  /** Hours worked that day, for the total printed at the right of the row. */
  heures: number;
  repos: boolean;
  /** A consigne governs that day: its hours are not the usual ones. */
  consigne: boolean;
  aujourdhui: boolean;
  /** True on the first day of a week, which is where the frieze draws a separator. */
  debutDeSemaine: boolean;
}

/** Hours worked on a day, breaks included — the same reading the stat block prints. */
export function dayHours(jour: JourPlanning): number {
  const heures = jour.postes.reduce((total, poste) => {
    const debut = minutesSinceMidnight(poste.heureDebut);
    const fin = minutesSinceMidnight(poste.heureFin);
    return debut === null || fin === null ? total : total + (finSurLAxe(debut, fin) - debut) / 60;
  }, 0);
  return arrondiHeures(heures);
}

/**
 * Hours to one decimal, as every hour figure of this space is read.
 *
 * <p>Rounded here rather than by a pipe at each display: four of them print
 * this number — the day strip, the day title, the Aperçu stat, the frieze —
 * and two of those are aria-labels, where no pipe applies. A 09:00–09:50 seat
 * is not rare in the scenarios of this repository, and it reads
 * « 0.8333333333333334 h » without this.</p>
 */
export function arrondiHeures(heures: number): number {
  return Math.round(heures * 10) / 10;
}

/** True for a Monday, read through the calendar rather than through string arithmetic. */
function isMonday(date: string): boolean {
  const [annee, mois, jour] = date.split('-').map(Number);
  return new Date(annee, mois - 1, jour).getDay() === 1;
}

/**
 * One row per day of the planning, rest days included: a planning that skips
 * its empty days reads as a planning with holes.
 */
export function lignesFrise(
  jours: readonly JourPlanning[],
  axe: AxeFrise,
  aujourdhui: string | null,
  datesSousConsigne: ReadonlySet<string>,
): LigneFrise[] {
  const largeurAxe = axe.fin - axe.debut;
  return jours.map((jour, index) => ({
    date: jour.date,
    heures: dayHours(jour),
    repos: jour.repos || jour.postes.length === 0,
    consigne: datesSousConsigne.has(jour.date),
    aujourdhui: jour.date === aujourdhui,
    // Never on the first row: a separator above the first day separates it
    // from nothing.
    debutDeSemaine: index > 0 && isMonday(jour.date),
    barres: jour.postes.flatMap((poste) => {
      const debut = minutesSinceMidnight(poste.heureDebut);
      const fin = minutesSinceMidnight(poste.heureFin);
      if (debut === null || fin === null) {
        return [];
      }
      const droite = Math.min(finSurLAxe(debut, fin), axe.fin);
      const gauche = Math.max(debut, axe.debut);
      return [
        {
          gauchePct: ((gauche - axe.debut) / largeurAxe) * 100,
          // Never zero: a shift shorter than a pixel still happened, and a bar
          // of no width is a shift nobody can touch.
          largeurPct: Math.max(((droite - gauche) / largeurAxe) * 100, 1.5),
          standNom: poste.standNom,
          typologieId: poste.typologieId ?? null,
          typologieLibelle: poste.typologieLibelle ?? null,
          heureDebut: (poste.heureDebut ?? '').slice(0, 5),
          heureFin: (poste.heureFin ?? '').slice(0, 5),
        },
      ];
    }),
  }));
}

/** The four figures in the head of the « Aperçu » tab. */
export interface StatsPlanning {
  heures: number;
  creneaux: number;
  stands: number;
  jours: number;
}

/** Counted on the days actually worked: a rest day is not a day of work. */
export function statsPlanning(jours: readonly JourPlanning[]): StatsPlanning {
  const stands = new Set<string>();
  let creneaux = 0;
  let heures = 0;
  let workedDays = 0;
  for (const jour of jours) {
    if (jour.postes.length === 0) {
      continue;
    }
    workedDays += 1;
    creneaux += jour.postes.length;
    heures += dayHours(jour);
    for (const poste of jour.postes) {
      stands.add(poste.standId);
    }
  }
  return { heures: arrondiHeures(heures), creneaux, stands: stands.size, jours: workedDays };
}

/** The typologies of a planning, once each, in label order — the frieze's legend. */
export function legendeTypologies(
  jours: readonly JourPlanning[],
): { id: string; libelle: string }[] {
  const byId = new Map<string, string>();
  for (const jour of jours) {
    for (const poste of jour.postes) {
      if (poste.typologieId) {
        byId.set(poste.typologieId, poste.typologieLibelle ?? poste.typologieId);
      }
    }
  }
  return [...byId.entries()]
    .map(([id, libelle]) => ({ id, libelle }))
    .sort((left, right) => left.libelle.localeCompare(right.libelle));
}
