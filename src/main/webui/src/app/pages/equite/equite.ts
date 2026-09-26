// The pure half of the Équité screen: which columns the table has, what a row
// is worth in each, how far it sits from the column's median, and how the rows
// sort and filter. No Angular beyond `$localize`, so every rule here is tested
// without rendering.

import { correspondAuFiltre } from '../../core/text-filter';
import { ColonneSolveur, LigneEquite, RapportEquite, SyntheseColonne } from '../../core/models';
import { SortState } from '../../core/view-query-params';

/** The first column: the person, linking to their fiche. */
export const COLONNE_ANIMATEUR = 'animateur';

/** The numeric columns before the ISO weeks, and after them — in table order. */
export const COLUMNS_BEFORE_WEEKS = ['heuresTotal'] as const;
export const COLUMNS_AFTER_WEEKS = [
  'heuresSoiree',
  'heuresWeekEnd',
  'heuresJourFerie',
  'postes',
  'postesPenibles',
  'standsDistincts',
  'typologiesDistinctes',
  'emplacementsDistinctsParJourMax',
  'tauxSouhaits',
  'tauxAppreciation',
  'joursTravailles',
  'joursRepos',
  'plusLongueSerie',
] as const;

export type ColonneFixe =
  (typeof COLUMNS_BEFORE_WEEKS)[number] | (typeof COLUMNS_AFTER_WEEKS)[number];

/** How a column's number is written: hours, a plain count, or a ratio shown as a percentage. */
export type FormatColonne = 'heures' | 'nombre' | 'taux';

const FORMATS: Record<ColonneFixe, FormatColonne> = {
  heuresTotal: 'heures',
  heuresSoiree: 'heures',
  heuresWeekEnd: 'heures',
  heuresJourFerie: 'heures',
  postes: 'nombre',
  postesPenibles: 'nombre',
  standsDistincts: 'nombre',
  typologiesDistinctes: 'nombre',
  emplacementsDistinctsParJourMax: 'nombre',
  tauxSouhaits: 'taux',
  tauxAppreciation: 'taux',
  joursTravailles: 'nombre',
  joursRepos: 'nombre',
  plusLongueSerie: 'nombre',
};

/** Below this, a deviation from the median is noise, not a colour. */
const NEGLIGIBLE_GAP = 0.005;

/** The table's columns: the person, the total, one per ISO week of the report, then the rest. */
export function colonnes(rapport: RapportEquite | null): string[] {
  return [
    COLONNE_ANIMATEUR,
    ...COLUMNS_BEFORE_WEEKS,
    ...(rapport?.semaines ?? []),
    ...COLUMNS_AFTER_WEEKS,
  ];
}

/** True for an ISO week column (`AAAA-Wss`), whose value lives in `heuresParSemaine`. */
export function isWeek(colonne: string): boolean {
  return /^\d{4}-W\d{2}$/.test(colonne);
}

export function formatColonne(colonne: string): FormatColonne {
  return isWeek(colonne) ? 'heures' : (FORMATS[colonne as ColonneFixe] ?? 'nombre');
}

/** The row's value in a column; a week the animateur did not work is zero. */
export function valeurColonne(ligne: LigneEquite, colonne: string): number {
  if (isWeek(colonne)) {
    return ligne.heuresParSemaine[colonne] ?? 0;
  }
  const valeur = ligne[colonne as ColonneFixe];
  return typeof valeur === 'number' ? valeur : 0;
}

/**
 * The row's distance to the column's median, computed here rather than sent by
 * the server: the median is already in the report, and the colour is the
 * screen's business. `null` when the report carries no synthesis for the column.
 */
export function medianGap(valeur: number, synthese: SyntheseColonne | undefined): number | null {
  return synthese ? valeur - synthese.mediane : null;
}

/** The class a deviation is painted with: above the median, below it, or nothing worth a colour. */
export function gapClass(gap: number | null): string {
  if (gap === null || Math.abs(gap) < NEGLIGIBLE_GAP) {
    return '';
  }
  return gap > 0 ? 'equite-ecart-positif' : 'equite-ecart-negatif';
}

/** The solver rule measuring a column, if any. */
export function columnConstraint(
  rapport: RapportEquite | null,
  colonne: string,
): ColonneSolveur | undefined {
  return rapport?.colonnesSolveur.find((entree) => entree.colonne === colonne);
}

/** Rows in the requested order; source order while no sort is applied. */
export function sortRows(lignes: LigneEquite[], sort: SortState): LigneEquite[] {
  const { active, direction } = sort;
  if (!active || !direction) {
    return lignes;
  }
  const factor = direction === 'asc' ? 1 : -1;
  return [...lignes].sort((a, b) => factor * compareByColumn(a, b, active));
}

/** Rows matching the quick filter on the name or the id, accent- and case-insensitive. */
export function filterRows(lignes: LigneEquite[], recherche: string): LigneEquite[] {
  if (!recherche.trim()) {
    return lignes;
  }
  return lignes.filter((ligne) => correspondAuFiltre(recherche, [ligne.nom, ligne.animateurId]));
}

function compareByColumn(a: LigneEquite, b: LigneEquite, colonne: string): number {
  if (colonne === COLONNE_ANIMATEUR) {
    return a.nom.localeCompare(b.nom);
  }
  return valeurColonne(a, colonne) - valeurColonne(b, colonne);
}

/** The column's title, as the header shows it; an ISO week is its own name. */
export function libelleColonne(colonne: string): string {
  switch (colonne) {
    case COLONNE_ANIMATEUR:
      return $localize`:@@equite.column.animateur:Animateur`;
    case 'heuresTotal':
      return $localize`:@@equite.column.heuresTotal:Heures`;
    case 'heuresSoiree':
      return $localize`:@@equite.column.heuresSoiree:Soirée`;
    case 'heuresWeekEnd':
      return $localize`:@@equite.column.heuresWeekEnd:Week-end`;
    case 'heuresJourFerie':
      return $localize`:@@equite.column.heuresJourFerie:Jours fériés`;
    case 'postes':
      return $localize`:@@equite.column.postes:Postes`;
    case 'postesPenibles':
      return $localize`:@@equite.column.postesPenibles:Postes pénibles`;
    case 'standsDistincts':
      return $localize`:@@equite.column.standsDistincts:Stands`;
    case 'typologiesDistinctes':
      return $localize`:@@equite.column.typologiesDistinctes:Typologies`;
    case 'emplacementsDistinctsParJourMax':
      return $localize`:@@equite.column.emplacementsDistinctsParJourMax:Emplacements / jour (max)`;
    case 'tauxSouhaits':
      return $localize`:@@equite.column.tauxSouhaits:Souhaits satisfaits`;
    case 'tauxAppreciation':
      return $localize`:@@equite.column.tauxAppreciation:Appréciation satisfaite`;
    case 'joursTravailles':
      return $localize`:@@equite.column.joursTravailles:Jours travaillés`;
    case 'joursRepos':
      return $localize`:@@equite.column.joursRepos:Jours de repos`;
    case 'plusLongueSerie':
      return $localize`:@@equite.column.plusLongueSerie:Plus longue série`;
    default:
      return colonne;
  }
}

/** What the header's tooltip says about the solver: measured by which rule, and whether it is on. */
export function libelleSolveur(contrainte: ColonneSolveur | undefined): string {
  if (!contrainte) {
    return $localize`:@@equite.solveur.nonMesuree:Colonne non prise en compte par le solveur`;
  }
  return contrainte.active
    ? $localize`:@@equite.solveur.mesuree:Colonne prise en compte par le solveur (${contrainte.contrainte}:contrainte:)`
    : $localize`:@@equite.solveur.desactivee:Colonne mesurée par le solveur, mais la règle ${contrainte.contrainte}:contrainte: est désactivée`;
}

/** `HH:MM:SS` from the server, shown as `HH:MM`. */
export function heureCourte(heure: string | null | undefined): string {
  return heure ? heure.slice(0, 5) : '';
}

/* --------------------------- The « fiche » view ---------------------------- */

/**
 * One indicator of a single animateur's fiche: what the table shows in a
 * column, read the other way round — a line per indicator for one person,
 * instead of a line per person across twenty-odd columns.
 */
export interface IndicateurFiche {
  colonne: string;
  libelle: string;
  valeur: number;
  /** Distance to the column's median, `null` when the report has no synthesis for it. */
  ecart: number | null;
  synthese: SyntheseColonne | undefined;
}

/** Every column of the report for one row, the person's own column left out. */
export function indicateursFiche(
  rapport: RapportEquite | null,
  ligne: LigneEquite | null,
): IndicateurFiche[] {
  if (!rapport || !ligne) {
    return [];
  }
  return colonnes(rapport)
    .filter((colonne) => colonne !== COLONNE_ANIMATEUR)
    .map((colonne) => {
      const valeur = valeurColonne(ligne, colonne);
      const synthese = rapport.syntheses[colonne];
      return {
        colonne,
        libelle: libelleColonne(colonne),
        valeur,
        ecart: medianGap(valeur, synthese),
        synthese,
      };
    });
}
