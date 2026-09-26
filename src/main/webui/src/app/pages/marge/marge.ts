// Pure view logic of the margin grids — the « avant » column of the
// Diagnostic's Besoin tab, and the Tension tab's cells — kept out of the
// components so it is unit-tested without rendering anything. Nothing here
// computes a margin: the server already did, from the seats and the roster
// (issue #499). This only lays the cells out in a grid, colours them and words
// them.

import { CelluleMarge, JourMarge, ModeMarge, RapportMarge } from '../../core/models';
import { compareCodeUnits } from '../../core/string-order';

/**
 * The divergent scale. Five steps and not three: a cell at −1 and a cell at −6
 * are two very different mornings, and a scale that flattens them says
 * « quelque part, ça coince » where the screen exists to say where.
 * `vide` is the absence of a cell — no seat that day at that hour — which is
 * neither a shortage nor a comfort.
 */
export type NiveauMarge = 'deficitFort' | 'deficit' | 'neutre' | 'surplus' | 'surplusFort' | 'vide';

/** Past this many people either way, the cell takes the darker step of its side. */
export const SEUIL_FORT = 3;

/** One column: a timeslot of the grid, as `09:00-12:00`. */
export interface ColonneMarge {
  cle: string;
  label: string;
}

export interface CelluleAffichee {
  cle: string;
  niveau: NiveauMarge;
  /** What the cell prints: the signed margin, or nothing when there is no cell. */
  label: string;
  tooltip: string;
  /** The timeslot a click opens, `null` on an empty cell. */
  creneauId: number | null;
  date: string | null;
}

export interface LigneMarge {
  cle: string;
  /** `J3 · 2026-07-16`, the row header. */
  label: string;
  cellules: CelluleAffichee[];
}

export interface TableMarge {
  colonnes: ColonneMarge[];
  lignes: LigneMarge[];
}

/** One line of the synthesis: the worst cell of one day. */
export interface SyntheseMarge {
  cle: string;
  jourLabel: string;
  trancheLabel: string;
  marge: number;
  niveau: NiveauMarge;
  creneauId: number | null;
  date: string;
}

/** Hours arrive as `HH:mm:ss` from the API; the seconds are always zero and never read. */
export function heure(valeur: string): string {
  return valeur.length > 5 ? valeur.slice(0, 5) : valeur;
}

export function libelleTranche(debut: string, fin: string): string {
  return `${heure(debut)}-${heure(fin)}`;
}

export function niveauMarge(marge: number): NiveauMarge {
  if (marge <= -SEUIL_FORT) {
    return 'deficitFort';
  }
  if (marge < 0) {
    return 'deficit';
  }
  if (marge === 0) {
    return 'neutre';
  }
  return marge >= SEUIL_FORT ? 'surplusFort' : 'surplus';
}

/** `+2`, `0`, `-3`: the sign is the reading, so a positive one is written out. */
export function signe(marge: number): string {
  return marge > 0 ? `+${marge}` : String(marge);
}

/** The column a timeslot falls in: its start hour, whatever its end. */
export function columnKey(debut: string): string {
  return heure(debut);
}

/**
 * The columns of the grid, one per start hour of the timeslots, earliest
 * first. The server sends one per distinct pair of hours, and an edition
 * whose evening ends at 22:00 on weekdays and at midnight on Saturdays got two
 * columns, « 18:00-00:00 » and « 18:00-22:00 », each with a hole every other
 * row: the same evening, read on two columns. Keyed on the start, they are
 * one, and its label says every end it holds — « 18:00-22:00/00:00 ».
 */
export function normalizeColumns(
  tranches: readonly { debut: string; fin: string }[],
): ColonneMarge[] {
  const fins = new Map<string, Set<string>>();
  for (const tranche of tranches) {
    const key = columnKey(tranche.debut);
    const ends = fins.get(key) ?? new Set<string>();
    ends.add(heure(tranche.fin));
    fins.set(key, ends);
  }
  // Midnight as an end is the end of the day, after every other hour.
  const endOrder = (fin: string): string => (fin === '00:00' ? '24:00' : fin);
  return [...fins.entries()]
    .sort(([a], [b]) => compareCodeUnits(a, b))
    .map(([key, ends]) => ({
      cle: key,
      label: `${key}-${[...ends].sort((a, b) => compareCodeUnits(endOrder(a), endOrder(b))).join('/')}`,
    }));
}

/**
 * The cell of each column of a day. Two timeslots of one day starting at the
 * same hour — two relay shifts — share a column: the tighter one is kept,
 * since that is the one the reader must see.
 */
export function cellsByColumn<T extends { debut: string; marge: number }>(
  cellules: readonly T[],
  worse: (a: T, b: T) => boolean = (a, b) => a.marge < b.marge,
): Map<string, T> {
  const index = new Map<string, T>();
  for (const cellule of cellules) {
    const key = columnKey(cellule.debut);
    const current = index.get(key);
    if (!current || worse(cellule, current)) {
      index.set(key, cellule);
    }
  }
  return index;
}

/**
 * The grid: one row per event day, one column per start hour of the grid's
 * timeslots (see {@link normalizeColumns}). A day with no cell on a column
 * gets an empty one, so every row has the same length and the columns stay
 * aligned.
 */
export function buildTable(rapport: RapportMarge | null): TableMarge {
  if (!rapport) {
    return { colonnes: [], lignes: [] };
  }
  const colonnes = normalizeColumns(rapport.tranches);
  const lignes = rapport.jours.map((jour) => {
    const indexedCells = cellsByColumn(jour.cellules);
    return {
      cle: jour.date,
      label: libelleJour(jour),
      cellules: colonnes.map((colonne) =>
        buildCellule(
          indexedCells.get(colonne.cle) ?? null,
          colonne,
          libelleJour(jour),
          rapport.mode,
        ),
      ),
    };
  });
  return { colonnes, lignes };
}

/** `J3 · 2026-07-16` — the day number first, since that is how the plan names it. */
export function libelleJour(jour: JourMarge): string {
  return jour.date ? `J${jour.jour} · ${jour.date}` : `J${jour.jour}`;
}

function buildCellule(
  cellule: CelluleMarge | null,
  colonne: ColonneMarge,
  jourLabel: string,
  mode: ModeMarge,
): CelluleAffichee {
  if (!cellule) {
    return {
      cle: colonne.cle,
      niveau: 'vide',
      label: '',
      tooltip: $localize`:@@marge.cell.tooltipNone:${jourLabel}:jour: — ${colonne.label}:tranche: : aucun siège`,
      creneauId: null,
      date: null,
    };
  }
  return {
    cle: colonne.cle,
    niveau: niveauMarge(cellule.marge),
    label: signe(cellule.marge),
    tooltip: tooltip(cellule, jourLabel, mode),
    creneauId: cellule.creneauId,
    date: cellule.date,
  };
}

/** The cell's own hours, not its column's: a column can hold several ends. */
function tooltip(cellule: CelluleMarge, jourLabel: string, mode: ModeMarge): string {
  const chiffres =
    mode === 'APRES'
      ? $localize`:@@marge.cell.tooltipApres:${cellule.disponibles}:disponibles: libre(s) pour ${cellule.besoin}:besoin: siège(s) vide(s) sur ${cellule.sieges}:sieges:`
      : $localize`:@@marge.cell.tooltipAvant:${cellule.disponibles}:disponibles: disponible(s) pour ${cellule.besoin}:besoin: siège(s) à pourvoir`;
  const marge = signe(cellule.marge);
  const tranche = libelleTranche(cellule.debut, cellule.fin);
  return $localize`:@@marge.cell.tooltip:${jourLabel}:jour: — ${tranche}:tranche: : marge ${marge}:marge: (${chiffres}:detail:)`;
}

/** One line per day, worst cell first in the day's own reading — the server picked it. */
export function buildSynthese(rapport: RapportMarge | null): SyntheseMarge[] {
  if (!rapport) {
    return [];
  }
  return rapport.jours
    .filter((jour): jour is JourMarge & { pireCellule: CelluleMarge } => jour.pireCellule !== null)
    .map((jour) => ({
      cle: jour.date,
      jourLabel: libelleJour(jour),
      trancheLabel: libelleTranche(jour.pireCellule.debut, jour.pireCellule.fin),
      marge: jour.pireCellule.marge,
      niveau: niveauMarge(jour.pireCellule.marge),
      creneauId: jour.pireCellule.creneauId,
      date: jour.pireCellule.date,
    }));
}

/** Where a cell leads: the Siège panel of the timeslot on a plan already solved, the openings before. */
export interface LienCellule {
  route: string;
  queryParams: Record<string, string | number>;
}

/**
 * A cell answers « qui pourrait tenir ce moment-là » after a solve — the
 * Journée opens the Siège panel on a free seat of the timeslot — and
 * « qu'ouvre-t-on à ce moment-là » before one: the bench cannot say anything
 * about a plan that does not exist yet, and the openings are what one closes to
 * move the margin before a solve.
 */
export function lienCellule(
  mode: ModeMarge,
  creneauId: number | null,
  date: string | null,
): LienCellule | null {
  if (mode === 'APRES') {
    return creneauId === null ? null : { route: '/journee', queryParams: { creneau: creneauId } };
  }
  return date === null ? null : { route: '/ouvertures', queryParams: { du: date, au: date } };
}
