// What the import screen has to decide, kept out of the component so it is
// unit-tested without rendering: the fields it offers, how a column is named
// when the file gave it no header, and how a mapping is edited one field at a
// time.

import {
  AnimateurCsvMapping,
  ImportCsvAction,
  ImportCsvDoublon,
  ImportCsvLigne,
} from '../../core/models';

/** The `Animateur` fields a column may feed, in the order the screen lists them. */
export type ChampImport = keyof AnimateurCsvMapping;

export const CHAMPS_IMPORT: readonly ChampImport[] = [
  'prenom',
  'nom',
  'dateNaissance',
  'email',
  'manager',
  'competences',
  'souhaits',
  'joursIndisponibles',
  'telephone',
];

/** Nothing mapped — what a file whose headers say nothing recognisable starts from. */
export function mappingVide(): AnimateurCsvMapping {
  return {
    prenom: null,
    nom: null,
    dateNaissance: null,
    email: null,
    manager: null,
    competences: null,
    souhaits: null,
    joursIndisponibles: null,
    telephone: null,
  };
}

/**
 * Sets one field's column, and clears whatever other field was reading that
 * same column.
 *
 * One column feeding two fields is never what the operator meant, and letting
 * it happen would silently write a birth date into a last name. Picking a
 * column already in use therefore *moves* it.
 */
export function withColonne(
  mapping: AnimateurCsvMapping,
  champ: ChampImport,
  colonne: number | null,
): AnimateurCsvMapping {
  const suivant: AnimateurCsvMapping = { ...mapping };
  if (colonne !== null) {
    for (const autre of CHAMPS_IMPORT) {
      if (autre !== champ && suivant[autre] === colonne) {
        suivant[autre] = null;
      }
    }
  }
  suivant[champ] = colonne;
  return suivant;
}

/** True when no column at all feeds an animateur. */
export function mappingVideOuNul(mapping: AnimateurCsvMapping | null): boolean {
  return mapping === null || CHAMPS_IMPORT.every((champ) => mapping[champ] === null);
}

/**
 * A row can only name somebody through one of these three — an id is never
 * read, the same number naming somebody else in another edition. Without any
 * of them, the server refuses the file, so the button is disabled first.
 */
export function mappingNommeQuelquun(mapping: AnimateurCsvMapping | null): boolean {
  return (
    mapping !== null && (mapping.prenom !== null || mapping.nom !== null || mapping.email !== null)
  );
}

/**
 * How a column is named in the mapping list: its header, or its position when
 * the file left the header cell empty. Duplicated headers are disambiguated
 * the same way, which is why the mapping is by index in the first place.
 */
export function libelleColonne(colonnes: readonly string[], index: number): string {
  const entete = colonnes[index]?.trim() ?? '';
  const position = index + 1;
  return entete === ''
    ? $localize`:@@importCsv.colonne.sansEntete:Colonne ${position}:position: (sans en-tête)`
    : `${entete} (${position})`;
}

/** The CSS class carrying a row's outcome — the palette stays in the stylesheet. */
export function classeAction(action: ImportCsvAction): string {
  switch (action) {
    case 'CREATED':
      return 'import-ligne-creation';
    case 'UPDATED':
      return 'import-ligne-maj';
    default:
      return 'import-ligne-rejet';
  }
}

/** The Material icon of a row's outcome. */
export function iconeAction(action: ImportCsvAction): string {
  switch (action) {
    case 'CREATED':
      return 'person_add';
    case 'UPDATED':
      return 'edit';
    default:
      return 'block';
  }
}

/** True when the server flagged the row as a probable duplicate or namesake. */
export function isFlagged(ligne: ImportCsvLigne): boolean {
  return ligne.doublonDe.length > 0;
}

/**
 * The rows the report table shows: every one, or only the flagged ones. The
 * counter and the confirmation keep reading the whole report — the filter is
 * a way to read it, not a way to import less.
 */
export function visibleRows(
  rows: readonly ImportCsvLigne[],
  flaggedOnly: boolean,
): readonly ImportCsvLigne[] {
  return flaggedOnly ? rows.filter(isFlagged) : rows;
}

/** What the badge of a flagged row says. */
export type DuplicateBadge = 'doublon' | 'homonyme' | 'adresse';

/**
 * The badge of a flagged row, `null` when nothing is flagged. A duplicate
 * (another row, another fiche) wins; a namesake is the opposite suspicion —
 * probably *not* the same person, about to be rewritten as if it were — and a
 * new address the mildest one: a fiche reached by its name whose address the
 * row replaces.
 */
export function duplicateBadge(ligne: ImportCsvLigne): DuplicateBadge | null {
  const kinds = new Set(ligne.doublonDe.map((doublon) => doublon.kind));
  if (kinds.size === 0) {
    return null;
  }
  if (kinds.has('ROW') || kinds.has('FICHE') || kinds.has('REPLACED')) {
    return 'doublon';
  }
  return kinds.has('NAMESAKE') ? 'homonyme' : 'adresse';
}

/**
 * Whether a reference to a fiche can still be followed: once a full
 * replacement is applied, the fiche it named is gone.
 */
export function ficheLinkable(doublon: ImportCsvDoublon, applied: boolean): boolean {
  return doublon.animateurId !== null && !(applied && doublon.kind === 'REPLACED');
}
