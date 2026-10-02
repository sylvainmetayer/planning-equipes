// Pure logic of the history screen: what the filters keep, and how a line
// reads. No Angular here, so it is unit-tested without rendering anything.

import { HistoryQuery } from '../../core/api/analyses-api';
import { ActionHistorique, EntreeHistorique } from '../../core/models';
import { correspondAuFiltre } from '../../core/text-filter';
import { compareCodeUnits } from '../../core/string-order';
import { historyLabel } from '../staffing/verification';

/** Which actors the list keeps. `TOUS` is the default: the history is read whole. */
export type FiltreActeur = 'TOUS' | 'ADMIN' | 'ANIMATEUR' | 'ANONYME' | 'ASSISTANT' | 'SYSTEME';

/** Which outcomes the list keeps. A refusal is often the line being looked for. */
export type FiltreResultat = 'TOUS' | 'SUCCES' | 'REFUS';

/**
 * Which kinds of action the list keeps. `EXPORTS` answers « qui a sorti quoi,
 * et quand » : every file that left the application, from the admin screens
 * as from an animateur's espace. `DONNEES` keeps the changes a solve would be
 * given — what the Solveur counts as « modification(s) depuis la dernière
 * résolution », and opens here.
 */
export type FiltreNature = 'TOUTES' | 'EXPORTS' | 'DONNEES';

/** How many lines a page shows; « Charger plus » asks for the next as many. */
export const PAGE_HISTORIQUE = 200;

const ACTEURS: ReadonlySet<string | null> = new Set<FiltreActeur>([
  'TOUS',
  'ADMIN',
  'ANIMATEUR',
  'ANONYME',
  'ASSISTANT',
  'SYSTEME',
]);
const RESULTATS: ReadonlySet<string | null> = new Set<FiltreResultat>(['TOUS', 'SUCCES', 'REFUS']);

/**
 * What `GET /api/historique` is asked for, and what the address says: the
 * nature is applied by the server, over the whole retention, never over the
 * page already loaded — so the URL carries the server's own word for it.
 */
export function natureQuery(nature: FiltreNature): 'exports' | 'donnees' | null {
  switch (nature) {
    case 'EXPORTS':
      return 'exports';
    case 'DONNEES':
      return 'donnees';
    default:
      return null;
  }
}

/**
 * The query of a page. One line more than a page is asked: when it comes
 * back, there is a next page, and the server needs no second shape to say so.
 */
export function historyQuery(
  nature: FiltreNature,
  depuis: string,
  jusqua: string,
  avant: number | null,
): HistoryQuery {
  return {
    nature: natureQuery(nature),
    depuis: depuis || null,
    jusqua: jusqua || null,
    avant,
    limite: PAGE_HISTORIQUE + 1,
  };
}

/**
 * A page as the server answered it, cut to its size: the lines to show, and
 * the cursor of the next page — the id of the last line shown — when the
 * extra line came back.
 */
export function cutPage(lignes: EntreeHistorique[]): {
  entrees: EntreeHistorique[];
  suivant: number | null;
} {
  if (lignes.length <= PAGE_HISTORIQUE) {
    return { entrees: lignes, suivant: null };
  }
  const entrees = lignes.slice(0, PAGE_HISTORIQUE);
  return { entrees, suivant: entrees[entrees.length - 1].id };
}

/** The codes the server's catalogue flags as a file leaving the application. */
export function exportCodes(actions: ActionHistorique[]): ReadonlySet<string> {
  return new Set(actions.filter((action) => action.export).map((action) => action.code));
}

/** Reads a filter off the URL, falling back to its default on anything unknown. */
export function readActorFilter(valeur: string | null): FiltreActeur {
  return ACTEURS.has(valeur) ? (valeur as FiltreActeur) : 'TOUS';
}

export function readOutcomeFilter(valeur: string | null): FiltreResultat {
  return RESULTATS.has(valeur) ? (valeur as FiltreResultat) : 'TOUS';
}

/**
 * Tolerant of the case, so the Solveur's `nature=donnees` and an address
 * written before the URL took the server's word (`EXPORTS`) both read.
 */
export function readNatureFilter(valeur: string | null): FiltreNature {
  switch (valeur?.toLowerCase()) {
    case 'exports':
      return 'EXPORTS';
    case 'donnees':
      return 'DONNEES';
    default:
      return 'TOUTES';
  }
}

/**
 * An ISO-8601 instant as written in an address: a date, a time — the
 * seconds and their fraction optional —, and an offset.
 */
const INSTANT = /^(\d{4}-\d{2}-\d{2}T\d{2}:\d{2})(?::(\d{2})(?:\.(\d{1,9}))?)?(Z|[+-]\d{2}:\d{2})$/;

/** What a `datetime-local` field holds once filled: a date and a time, no offset. */
const LOCAL_DATE_TIME = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(:\d{2}(\.\d{1,3})?)?$/;

/**
 * An instant written in an address, read twice: `iso`, as the server will
 * parse it — `Instant.parse` wants the seconds, so `:00` is added when they
 * are missing, and nothing else is touched —, and `date`, for the display.
 * The date is read from a fraction cut to the millisecond, the one form
 * `Date.parse` is specified to accept: six digits are an implementation's
 * goodwill. `null` when it is no instant at all.
 */
function parseInstant(valeur: string): { iso: string; date: Date } | null {
  const parts = INSTANT.exec(valeur);
  if (!parts) {
    return null;
  }
  const [, minute, secondes = '00', fraction, offset] = parts;
  const millis = (fraction ?? '').padEnd(3, '0').slice(0, 3);
  const date = new Date(`${minute}:${secondes}.${millis}${offset}`);
  if (Number.isNaN(date.getTime())) {
    return null;
  }
  const iso = `${minute}:${secondes}${fraction === undefined ? '' : `.${fraction}`}${offset}`;
  return { iso, date };
}

/**
 * A bound of the period off the URL: kept verbatim when it reads as an
 * instant — the Solveur passes the end of a solve to the microsecond, which a
 * `Date` would round —, seconds added when they are missing, dropped
 * otherwise rather than sent to be refused.
 */
export function readInstant(valeur: string | null): string {
  return (valeur && parseInstant(valeur)?.iso) || '';
}

/**
 * An instant as a `datetime-local` field shows it — the reader's own clock,
 * to the minute; `''` for no bound.
 */
export function localInputValue(iso: string): string {
  const date = iso ? parseInstant(iso)?.date : undefined;
  if (!date) {
    return '';
  }
  const deux = (valeur: number) => `${valeur}`.padStart(2, '0');
  return (
    `${date.getFullYear()}-${deux(date.getMonth() + 1)}-${deux(date.getDate())}` +
    `T${deux(date.getHours())}:${deux(date.getMinutes())}`
  );
}

/**
 * What a `datetime-local` field typed, as the instant the server reads: the
 * field carries no zone, and `new Date` reads such a value on the reader's own
 * clock — the one they typed it on. `''` for an empty or unfinished field.
 * The start of what was typed: the lower bound, « depuis ».
 */
export function instantFromLocalInput(local: string): string {
  if (!LOCAL_DATE_TIME.test(local)) {
    return '';
  }
  const date = new Date(local);
  return Number.isNaN(date.getTime()) ? '' : date.toISOString();
}

/**
 * The upper bound, « jusqu'à », which the server reads inclusive: the
 * **end** of what was typed, so « jusqu'à 14:32 » keeps the line of
 * 14:32:40. The field's own precision — the minute, or the second when it
 * carries them — is covered to the microsecond, the precision of the
 * history's timestamps.
 */
export function endInstantFromLocalInput(local: string): string {
  const parts = LOCAL_DATE_TIME.exec(local);
  const start = parts ? new Date(local).getTime() : Number.NaN;
  if (!parts || Number.isNaN(start)) {
    return '';
  }
  const [, secondes, fraction] = parts;
  const precision = secondes === undefined ? 60_000 : fraction === undefined ? 1_000 : 1;
  return new Date(start + precision - 1).toISOString().replace(/Z$/, '999Z');
}

/**
 * The lines a reader asked for, among those loaded — the nature was already
 * chosen by the server. The free-text search covers what is on screen —
 * the sentence, the actor, the entity and its id — and nothing else: searching
 * a name that the table does not store would silently return nothing.
 */
export function filter(
  entrees: EntreeHistorique[],
  acteur: FiltreActeur,
  resultat: FiltreResultat,
  entite: string,
  recherche: string,
): EntreeHistorique[] {
  return entrees.filter((entree) => {
    if (acteur !== 'TOUS' && entree.acteur !== acteur) {
      return false;
    }
    if (resultat !== 'TOUS' && entree.resultat !== resultat) {
      return false;
    }
    if (entite && entree.entite !== entite) {
      return false;
    }
    return correspondAuFiltre(recherche, [
      entree.libelle,
      entree.acteurNom ?? entree.acteurId ?? '',
      entree.entiteNom ?? '',
      entree.entiteId ?? '',
      entree.champs.join(' '),
      resultOf(entree),
    ]);
  });
}

/** The entity families present in what was loaded, so the filter offers only real ones. */
export function entitesPresentes(entrees: EntreeHistorique[]): string[] {
  return [
    ...new Set(
      entrees.map((entree) => entree.entite).filter((entite): entite is string => !!entite),
    ),
  ].sort(compareCodeUnits);
}

/**
 * Who did it, in one readable phrase. An animateur still on the roster is
 * named; one deleted since is not, and that is the point of storing an id
 * rather than a name — so the id stands in rather than an empty cell.
 */
export function qui(entree: EntreeHistorique): string {
  if (entree.acteur === 'SYSTEME') {
    return $localize`:@@historique.acteur.systeme:Application`;
  }
  if (entree.acteur === 'ASSISTANT') {
    return $localize`:@@historique.acteur.assistant:Assistant (MCP)`;
  }
  if (entree.acteur === 'ANIMATEUR') {
    return (
      entree.acteurNom ?? entree.acteurId ?? $localize`:@@historique.acteur.animateur:Animateur`
    );
  }
  if (entree.acteur === 'ANONYME') {
    return $localize`:@@historique.acteur.anonyme:Visiteur non identifié`;
  }
  return entree.acteurId ?? $localize`:@@historique.acteur.admin:Administration`;
}

/** What it bore upon, named when the referential still knows it. */
export function surQuoi(entree: EntreeHistorique): string {
  if (!entree.entiteId) {
    return '';
  }
  return entree.entiteNom ? `${entree.entiteNom} (${entree.entiteId})` : entree.entiteId;
}

/**
 * What the line's staffing check gave — its team, its time, its outcome — or
 * `''` on any other line. Joined by the server from the check itself, so an
 * organiser trying sizes one after the other reads « 140 : 12 sièges vides,
 * 160 : tous pourvus » down the list.
 */
export function resultOf(entree: EntreeHistorique): string {
  return entree.verification ? historyLabel(entree.verification) : '';
}

/**
 * The calendar day an instant falls on **where the reader is**, as
 * `YYYY-MM-DD`.
 *
 * Not `survenuLe.slice(0, 10)`, which is the UTC date: the hour beside it is
 * rendered in the browser's zone, so in Europe/Paris an action at 00h30 on the
 * 8th (22:30Z on the 7th) would sit under « lundi 7 septembre » showing
 * « 00:30 ». Evening and night work is exactly when this screen is read.
 */
export function journeeLocale(iso: string): string {
  const date = new Date(iso);
  const mois = `${date.getMonth() + 1}`.padStart(2, '0');
  const jour = `${date.getDate()}`.padStart(2, '0');
  return `${date.getFullYear()}-${mois}-${jour}`;
}

/** Groups the lines by calendar day, newest first — an event week piles up hundreds. */
export function parJournee(
  entrees: EntreeHistorique[],
): { jour: string; entrees: EntreeHistorique[] }[] {
  const journees = new Map<string, EntreeHistorique[]>();
  for (const entree of entrees) {
    const jour = journeeLocale(entree.survenuLe);
    const existantes = journees.get(jour);
    if (existantes) {
      existantes.push(entree);
    } else {
      journees.set(jour, [entree]);
    }
  }
  return [...journees.entries()].map(([jour, lignes]) => ({ jour, entrees: lignes }));
}
