// The pure side of the wall display: which shift is under way and which comes
// next, how the tiles are paginated, how long the screen has been offline.
// Everything is read against the server's moment (`now`), advanced by
// the time elapsed since the read — never against the television's own clock,
// which nobody sets.

import { MuralShift, MuralStand } from '../../core/models';

/** How often the screen reads the server again. */
export const RAFRAICHISSEMENT_MS = 60_000;

/**
 * How long a read may take before it counts as failed: shorter than the
 * refresh period, so a hung connection never overlaps the next read and the
 * offline mention appears instead of a frozen « mis à jour il y a ».
 */
export const READ_TIMEOUT_MS = 20_000;

/**
 * How often the screen tries again once the server answered « unknown link »:
 * slower than the ordinary refresh, since a revoked link does not come back —
 * but a 404 can also be a restart or a proxy hiccup, so it is not the end.
 */
export const DEAD_LINK_RETRY_MS = 5 * 60_000;

/** Consecutive 404s before the screen concludes the link is unknown or revoked. */
export const DEAD_LINK_AFTER = 3;

/** How long a page of tiles stays on screen before the next one. */
export const ROTATION_MS = 15_000;

/**
 * A stand at the moment on screen: every shift under way — two can overlap on
 * one stand (10–14 and 12–16), and both hold people — and the next one to
 * start.
 */
export interface StandMoment {
  stand: MuralStand;
  current: MuralShift[];
  next: MuralShift | null;
}

/** A group of tiles under one emplacement heading, on one page. */
export interface GroupeEmplacement {
  emplacementNom: string | null;
  stands: StandMoment[];
}

/**
 * Minutes since the epoch of a local ISO date-time (`2026-07-08T13:30:00`),
 * read as a wall-clock value with no time zone: the server sends local times,
 * and comparing two of them must not depend on where the browser thinks it is.
 */
export function minutesOf(iso: string): number {
  const [date, time = '00:00'] = iso.split('T');
  const [y, m, d] = date.split('-').map(Number);
  const [h, mi] = time.split(':').map(Number);
  return Date.UTC(y, m - 1, d, h, mi) / 60_000;
}

/**
 * The server's moment `elapsedMs` after it was read, as a local ISO
 * date-time — so a shift ends on screen at the right minute between two reads.
 */
export function advance(now: string, elapsedMs: number): string {
  const [date, time = '00:00:00'] = now.split('T');
  const [y, m, d] = date.split('-').map(Number);
  const [h, mi, s = 0] = time.split(':').map(Number);
  const moment = new Date(Date.UTC(y, m - 1, d, h, mi, Math.floor(s)) + Math.max(0, elapsedMs));
  return moment.toISOString().slice(0, 19);
}

/**
 * The shifts under way at `now` — started, not ended; a shift crossing
 * midnight carries its end on the next day, so it stays under way after 0:00 —
 * and the earliest one starting after `now`.
 */
export function momentOf(stand: MuralStand, now: string): StandMoment {
  const at = minutesOf(now);
  const shifts = [...stand.vacations].sort(
    (a, b) => minutesOf(a.start) - minutesOf(b.start) || minutesOf(a.end) - minutesOf(b.end),
  );
  const current = shifts.filter(
    (shift) => minutesOf(shift.start) <= at && minutesOf(shift.end) > at,
  );
  const next = shifts.find((shift) => minutesOf(shift.start) > at) ?? null;
  return { stand, current, next };
}

/** A key telling two shifts of one stand apart: its window. */
export function shiftKey(shift: MuralShift): string {
  return `${shift.start}/${shift.end}`;
}

/** `HH:mm` of a local ISO date-time. */
export function heureOf(iso: string): string {
  return iso.slice(11, 16);
}

/** How many tiles fit a screen of that size: large tiles, readable at three metres. */
export function tilesPerPage(largeur: number, hauteur: number): number {
  const colonnes = Math.max(1, Math.floor(largeur / 520));
  // Header and bottom band take about 30 % of the height.
  const lignes = Math.max(1, Math.floor((hauteur * 0.7) / 300));
  return colonnes * lignes;
}

/** The items cut into pages of `parPage`; always at least one page, even empty. */
export function paginate<T>(items: readonly T[], parPage: number): T[][] {
  const taille = Math.max(1, Math.floor(parPage));
  const pages: T[][] = [];
  for (let debut = 0; debut < items.length; debut += taille) {
    pages.push(items.slice(debut, debut + taille));
  }
  return pages.length > 0 ? pages : [[]];
}

/** The page after `index`, back to the first after the last. */
export function nextPage(index: number, count: number): number {
  return count <= 1 ? 0 : (index + 1) % count;
}

/** `1/3` — shown only when there is more than one page. */
export function pageLabel(index: number, count: number): string | null {
  return count > 1 ? `${index + 1}/${count}` : null;
}

/**
 * Consecutive tiles of the same emplacement under one heading. The server
 * already orders the stands by emplacement, so a page never splits a group it
 * could have kept, and a group cut by a page break is headed on both pages.
 */
export function groupByEmplacement(moments: readonly StandMoment[]): GroupeEmplacement[] {
  const groupes: GroupeEmplacement[] = [];
  for (const moment of moments) {
    const nom = moment.stand.emplacementNom;
    const dernier = groupes.at(-1);
    if (dernier && dernier.emplacementNom === nom) {
      dernier.stands.push(moment);
    } else {
      groupes.push({ emplacementNom: nom, stands: [moment] });
    }
  }
  return groupes;
}

/** Whole minutes since the last successful read, for « hors ligne depuis … ». */
export function minutesSince(sinceMs: number, nowMs: number): number {
  return Math.max(0, Math.floor((nowMs - sinceMs) / 60_000));
}

/** Whole seconds since the last successful read, for « mis à jour il y a … ». */
export function secondsSince(sinceMs: number, nowMs: number): number {
  return Math.max(0, Math.floor((nowMs - sinceMs) / 1000));
}
