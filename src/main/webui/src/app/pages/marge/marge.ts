// Pure view logic of the « Marge disponible » screen, kept out of the
// component so it is unit-tested without rendering anything. Nothing here
// computes a margin: the server already did, from the seats and the roster
// (issue #499). This only lays the cells out in a grid, colours them and words
// them.

import { CelluleMarge, JourMarge, ModeMarge, RapportMarge } from '../../core/models';

/**
 * The three readings of the page: the margin before and after a solve, and the
 * tension map that crosses the second with the fragility of the same plan.
 */
export type MarginView = ModeMarge | 'TENSION';

/** The `mode` query param of each reading; the default one leaves the URL bare. */
export const MARGIN_VIEW_PARAMS: Readonly<Record<MarginView, string | null>> = {
  AVANT: null,
  APRES: 'apres',
  TENSION: 'tension',
};

/**
 * Reads the `mode` query param. Anything but the values this page knows is the
 * margin before a solve: an unknown mode would otherwise show the « après »
 * grid under the « avant » toggle.
 */
export function readMarginView(param: string | null): MarginView {
  if (param === 'apres') {
    return 'APRES';
  }
  return param === 'tension' ? 'TENSION' : 'AVANT';
}

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

/**
 * The grid: one row per event day, one column per timeslot of the grid, in the
 * order the server sent them — it is the one that knows which timeslots the
 * edition really holds. A day with no cell on a column gets an empty one, so
 * every row has the same length and the columns stay aligned.
 */
export function buildTable(rapport: RapportMarge | null): TableMarge {
  if (!rapport) {
    return { colonnes: [], lignes: [] };
  }
  const colonnes = rapport.tranches.map((tranche) => ({
    cle: libelleTranche(tranche.debut, tranche.fin),
    label: libelleTranche(tranche.debut, tranche.fin),
  }));
  const lignes = rapport.jours.map((jour) => {
    const indexedCells = new Map(
      jour.cellules.map((cellule) => [libelleTranche(cellule.debut, cellule.fin), cellule]),
    );
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
    tooltip: tooltip(cellule, colonne, jourLabel, mode),
    creneauId: cellule.creneauId,
    date: cellule.date,
  };
}

function tooltip(
  cellule: CelluleMarge,
  colonne: ColonneMarge,
  jourLabel: string,
  mode: ModeMarge,
): string {
  const chiffres =
    mode === 'APRES'
      ? $localize`:@@marge.cell.tooltipApres:${cellule.disponibles}:disponibles: libre(s) pour ${cellule.besoin}:besoin: siège(s) vide(s) sur ${cellule.sieges}:sieges:`
      : $localize`:@@marge.cell.tooltipAvant:${cellule.disponibles}:disponibles: disponible(s) pour ${cellule.besoin}:besoin: siège(s) à pourvoir`;
  const marge = signe(cellule.marge);
  return $localize`:@@marge.cell.tooltip:${jourLabel}:jour: — ${colonne.label}:tranche: : marge ${marge}:marge: (${chiffres}:detail:)`;
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

/** Where a cell leads: the bench on a plan already solved, the openings before. */
export interface LienCellule {
  route: string;
  queryParams: Record<string, string | number>;
}

/**
 * A cell answers « qui pourrait tenir ce moment-là » after a solve, and
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
    return creneauId === null
      ? null
      : { route: '/diagnostic', queryParams: { onglet: 'banc', creneau: creneauId } };
  }
  return date === null ? null : { route: '/ouvertures', queryParams: { vue: 'journee', date } };
}
