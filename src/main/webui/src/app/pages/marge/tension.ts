// Pure view logic of the « Tension » reading of the Marge screen. The grade of
// each cell is the server's (`TensionAnalyzer`), decided on named rules; this
// only lays the cells out, names the grade and words the reasons.

import {
  CelluleTension,
  GraviteTension,
  JourTension,
  MotifTension,
  RapportTension,
} from '../../core/models';
import { ColonneMarge, libelleTranche, signe } from './marge';

/** The CSS modifier of a grade, and of the two cells that carry none. */
export type NiveauTension = 'critique' | 'elevee' | 'surveillee' | 'calme' | 'passee' | 'vide';

export interface CelluleTensionAffichee {
  cle: string;
  niveau: NiveauTension;
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

/**
 * The grid: the same rows and columns as the margin, in the server's order. A
 * day with no cell on a column gets an empty one, so the columns stay aligned.
 */
export function buildTableTension(rapport: RapportTension | null): TableTension {
  if (!rapport) {
    return { colonnes: [], lignes: [] };
  }
  const colonnes = rapport.tranches.map((tranche) => ({
    cle: libelleTranche(tranche.debut, tranche.fin),
    label: libelleTranche(tranche.debut, tranche.fin),
  }));
  const lignes = rapport.jours.map((jour) => {
    const bySlice = new Map(
      jour.cellules.map((cellule) => [libelleTranche(cellule.debut, cellule.fin), cellule]),
    );
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
      label: '',
      fragiles: 0,
      description: $localize`:@@marge.cell.tooltipNone:${jourLabel}:jour: — ${colonne.label}:tranche: : aucun siège`,
      cellule: null,
    };
  }
  const gravite = libelleGravite(cellule.passee ? null : cellule.gravite);
  return {
    cle: colonne.cle,
    niveau: niveauTension(cellule),
    label: signe(cellule.marge),
    fragiles: cellule.passee ? 0 : cellule.siegesFragiles,
    description: $localize`:@@tension.cell.description:${jourLabel}:jour: — ${colonne.label}:tranche: : ${gravite}:gravite:, marge ${signe(cellule.marge)}:marge:, ${cellule.siegesFragiles}:fragiles: siège(s) fragile(s)`,
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
