// Pure view logic of the Diagnostic's Tension tab: the margin after a solve
// crossed with the fragility of the same plan. The grade of each cell is the
// server's (`TensionAnalyzer`), decided on named rules; this only lays the
// cells out, names the grade and words the reasons.
//
// A cell's colour is its margin's — red below zero, green above, the scale of
// the margin grids — and its grade is a mark on top of it: a « +68 » painted
// in the error colour because one seat of it is irreplaceable read as a
// shortage of sixty-eight, which it is not.

import {
  CelluleTension,
  GraviteTension,
  JourTension,
  MotifTension,
  RapportTension,
} from '../../core/models';
import {
  cellsByColumn,
  ColonneMarge,
  libelleTranche,
  NiveauMarge,
  niveauMarge,
  normalizeColumns,
  signe,
} from './marge';

/** The CSS modifier of a grade, and of the two cells that carry none. */
export type NiveauTension = 'critique' | 'elevee' | 'surveillee' | 'calme' | 'passee' | 'vide';

export interface CelluleTensionAffichee {
  cle: string;
  niveau: NiveauTension;
  /** The fill of the cell: its margin's sign on the margin grids' scale, `vide` on a hole or a past cell. */
  marge: NiveauMarge;
  /** The signed margin, as on the other two readings; empty on a hole. */
  label: string;
  /** Fragile seats of the cell, printed as « 2 ⚠ »; 0 prints nothing. */
  fragiles: number;
  /** One sentence for the tooltip and the screen reader: grade included. */
  description: string;
  cellule: CelluleTension | null;
}

export interface LigneTension {
  cle: string;
  label: string;
  cellules: CelluleTensionAffichee[];
}

export interface TableTension {
  colonnes: ColonneMarge[];
  lignes: LigneTension[];
}

const ORDRE_GRAVITE: readonly GraviteTension[] = ['CRITIQUE', 'ELEVEE', 'SURVEILLEE', 'CALME'];

export function niveauTension(cellule: CelluleTension): NiveauTension {
  if (cellule.passee || !cellule.gravite) {
    return 'passee';
  }
  return cellule.gravite.toLowerCase() as NiveauTension;
}

export function libelleGravite(gravite: GraviteTension | null | undefined): string {
  switch (gravite) {
    case 'CRITIQUE':
      return $localize`:@@tension.gravite.critique:Critique`;
    case 'ELEVEE':
      return $localize`:@@tension.gravite.elevee:Élevée`;
    case 'SURVEILLEE':
      return $localize`:@@tension.gravite.surveillee:Surveillée`;
    case 'CALME':
      return $localize`:@@tension.gravite.calme:Calme`;
    default:
      return $localize`:@@tension.gravite.passee:Tranche passée`;
  }
}

/** Why the cell sits at its grade, one sentence per rule that fired. */
export function libelleMotif(motif: MotifTension, cellule: CelluleTension): string {
  switch (motif) {
    case 'SIEGES_VIDES_NON_COUVRABLES':
      return $localize`:@@tension.motif.videsNonCouvrables:${cellule.siegesVides}:count: siège(s) vide(s), et personne de libre pour les tenir (marge ${signe(cellule.marge)}:marge:)`;
    case 'SIEGE_IRREMPLACABLE':
      return $localize`:@@tension.motif.irremplacable:${cellule.siegesIrremplacables}:count: siège(s) que personne d'autre ne pourrait reprendre`;
    case 'STAND_SANS_SPECIALISTE':
      return $localize`:@@tension.motif.sansSpecialiste:${cellule.competencesRaresSansSpecialiste}:count: stand(s) que personne n'est compétent pour tenir`;
    case 'MARGE_NULLE_AVEC_SIEGES_VIDES':
      return $localize`:@@tension.motif.margeNulle:${cellule.siegesVides}:count: siège(s) vide(s) et personne à revendre`;
    case 'FRAGILES_AU_DELA_DE_LA_MARGE':
      return $localize`:@@tension.motif.fragilesAuDela:${cellule.siegesFragiles}:count: siège(s) fragile(s), plus que la marge (${signe(cellule.marge)}:marge:)`;
    case 'SIEGES_FRAGILES':
      return $localize`:@@tension.motif.fragiles:${cellule.siegesFragiles}:count: siège(s) fragile(s), la marge suffit`;
    case 'SPECIALISTE_UNIQUE_SANS_RENFORT':
      return $localize`:@@tension.motif.specialisteUnique:Un stand repose sur un seul spécialiste, sans polyvalent en renfort`;
  }
}

/** True when `a` should be shown rather than `b` in a shared column: the worse grade, then the tighter margin. */
function worseTension(a: CelluleTension, b: CelluleTension): boolean {
  const rang = rangGravite(a.passee ? null : a.gravite) - rangGravite(b.passee ? null : b.gravite);
  return rang < 0 || (rang === 0 && a.marge < b.marge);
}

/**
 * The grid: one row per day, one column per start hour of the grid's
 * timeslots, as on the margin (see `normalizeColumns`). A day with no cell on
 * a column gets an empty one, so the columns stay aligned.
 */
export function buildTableTension(rapport: RapportTension | null): TableTension {
  if (!rapport) {
    return { colonnes: [], lignes: [] };
  }
  const colonnes = normalizeColumns(rapport.tranches);
  const lignes = rapport.jours.map((jour) => {
    const bySlice = cellsByColumn(jour.cellules, worseTension);
    const jourLabel = libelleJourTension(jour);
    return {
      cle: jour.date ?? String(jour.jour),
      label: jourLabel,
      cellules: colonnes.map((colonne) =>
        buildCellule(bySlice.get(colonne.cle) ?? null, colonne, jourLabel),
      ),
    };
  });
  return { colonnes, lignes };
}

export function libelleJourTension(jour: JourTension): string {
  return jour.date ? `J${jour.jour} · ${jour.date}` : `J${jour.jour}`;
}

function buildCellule(
  cellule: CelluleTension | null,
  colonne: ColonneMarge,
  jourLabel: string,
): CelluleTensionAffichee {
  if (!cellule) {
    return {
      cle: colonne.cle,
      niveau: 'vide',
      marge: 'vide',
      label: '',
      fragiles: 0,
      description: $localize`:@@marge.cell.tooltipNone:${jourLabel}:jour: — ${colonne.label}:tranche: : aucun siège`,
      cellule: null,
    };
  }
  const gravite = libelleGravite(cellule.passee ? null : cellule.gravite);
  const tranche = libelleTranche(cellule.debut, cellule.fin);
  const raisons = cellule.passee
    ? ''
    : cellule.motifs.map((motif) => ` ${libelleMotif(motif, cellule)}.`).join('');
  return {
    cle: colonne.cle,
    niveau: niveauTension(cellule),
    marge: cellule.passee ? 'vide' : niveauMarge(cellule.marge),
    label: signe(cellule.marge),
    fragiles: cellule.passee ? 0 : cellule.siegesFragiles,
    description:
      $localize`:@@tension.cell.description:${jourLabel}:jour: — ${tranche}:tranche: : ${gravite}:gravite:, marge ${signe(cellule.marge)}:marge:, ${cellule.siegesFragiles}:fragiles: siège(s) fragile(s)` +
      raisons,
    cellule,
  };
}

/** One line per day: its worst cell still ahead, worst grade first, then the tightest margin. */
export interface SyntheseTension {
  cle: string;
  jourLabel: string;
  trancheLabel: string;
  niveau: NiveauTension;
  graviteLabel: string;
  marge: number;
  cellule: CelluleTension;
}

export function buildSyntheseTension(rapport: RapportTension | null): SyntheseTension[] {
  if (!rapport) {
    return [];
  }
  return rapport.jours
    .filter((jour): jour is JourTension & { pireCellule: CelluleTension } => !!jour.pireCellule)
    .map((jour) => ({
      cle: jour.date ?? String(jour.jour),
      jourLabel: libelleJourTension(jour),
      trancheLabel: libelleTranche(jour.pireCellule.debut, jour.pireCellule.fin),
      niveau: niveauTension(jour.pireCellule),
      graviteLabel: libelleGravite(jour.pireCellule.gravite),
      marge: jour.pireCellule.marge,
      cellule: jour.pireCellule,
    }))
    .sort(
      (gauche, droite) =>
        rangGravite(gauche.cellule.gravite) - rangGravite(droite.cellule.gravite) ||
        gauche.marge - droite.marge ||
        gauche.cle.localeCompare(droite.cle),
    );
}

function rangGravite(gravite: GraviteTension | null | undefined): number {
  const rang = gravite ? ORDRE_GRAVITE.indexOf(gravite) : -1;
  return rang === -1 ? ORDRE_GRAVITE.length : rang;
}
