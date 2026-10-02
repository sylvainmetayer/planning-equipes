import { describe, expect, it } from 'vitest';
import type { ImportCsvDoublon, ImportCsvLigne } from '../../core/models';
import {
  CHAMPS_IMPORT,
  classeAction,
  duplicateBadge,
  ficheLinkable,
  iconeAction,
  isFlagged,
  flaggedRows,
  libelleColonne,
  mappingNommeQuelquun,
  mappingVide,
  mappingVideOuNul,
  withColonne,
} from './import-animateurs';

describe('withColonne', () => {
  it('assigns a column to a field', () => {
    const mapping = withColonne(mappingVide(), 'prenom', 2);

    expect(mapping.prenom).toBe(2);
    expect(mapping.nom).toBeNull();
  });

  it('moves a column instead of letting it feed two fields at once', () => {
    const depart = withColonne(mappingVide(), 'prenom', 2);

    const mapping = withColonne(depart, 'nom', 2);

    expect(mapping.nom).toBe(2);
    expect(mapping.prenom).toBeNull();
  });

  it('clears a field without touching the others', () => {
    const depart = withColonne(withColonne(mappingVide(), 'prenom', 0), 'nom', 1);

    const mapping = withColonne(depart, 'prenom', null);

    expect(mapping.prenom).toBeNull();
    expect(mapping.nom).toBe(1);
  });

  it('leaves the mapping it was given untouched', () => {
    const depart = mappingVide();

    withColonne(depart, 'prenom', 3);

    expect(depart.prenom).toBeNull();
  });
});

describe('mapping predicates', () => {
  it('sees an all-null mapping as empty', () => {
    expect(mappingVideOuNul(mappingVide())).toBe(true);
    expect(mappingVideOuNul(null)).toBe(true);
    expect(mappingVideOuNul(withColonne(mappingVide(), 'email', 0))).toBe(false);
  });

  it('requires a first name, a last name or an e-mail for a row to name somebody', () => {
    expect(mappingNommeQuelquun(null)).toBe(false);
    expect(mappingNommeQuelquun(withColonne(mappingVide(), 'dateNaissance', 0))).toBe(false);
    expect(mappingNommeQuelquun(withColonne(mappingVide(), 'nom', 0))).toBe(true);
    expect(mappingNommeQuelquun(withColonne(mappingVide(), 'email', 0))).toBe(true);
  });

  it('offers the nine fields of an animateur fiche, never an id', () => {
    expect(CHAMPS_IMPORT).toHaveLength(9);
    expect(new Set(CHAMPS_IMPORT).size).toBe(9);
    expect(CHAMPS_IMPORT as readonly string[]).not.toContain('id');
  });
});

describe('libelleColonne', () => {
  it('names a column by its header and its position', () => {
    expect(libelleColonne(['Prénom', 'Nom'], 1)).toBe('Nom (2)');
  });

  it('falls back to the position when the header cell is empty', () => {
    expect(libelleColonne(['Prénom', '  '], 1)).toContain('2');
  });

  it('distinguishes two columns carrying the same header', () => {
    const colonnes = ['Nom', 'Nom'];

    expect(libelleColonne(colonnes, 0)).not.toBe(libelleColonne(colonnes, 1));
  });
});

describe('row presentation', () => {
  it('gives each outcome its own class and icon', () => {
    expect(classeAction('CREATED')).toBe('import-ligne-creation');
    expect(classeAction('UPDATED')).toBe('import-ligne-maj');
    expect(classeAction('REJECTED')).toBe('import-ligne-rejet');
    expect(
      new Set((['CREATED', 'UPDATED', 'REJECTED'] as const).map((action) => iconeAction(action)))
        .size,
    ).toBe(3);
  });
});

function row(line: number, doublonDe: ImportCsvDoublon[] = []): ImportCsvLigne {
  return {
    line,
    label: `Ligne ${line}`,
    animateurId: null,
    action: 'CREATED',
    reasons: [],
    warnings: [],
    joursIndisponibles: [],
    doublonDe,
  };
}

const ON_ROW_3: ImportCsvDoublon = { kind: 'ROW', line: 3, animateurId: null };
const NAMESAKE: ImportCsvDoublon = { kind: 'NAMESAKE', line: null, animateurId: 'A1' };

describe('probable duplicates', () => {
  it('narrows the rows to the flagged ones', () => {
    const rows = [row(2, [ON_ROW_3]), row(3, [{ ...ON_ROW_3, line: 2 }]), row(4)];

    expect(flaggedRows(rows).map((ligne) => ligne.line)).toEqual([2, 3]);
    expect(isFlagged(rows[2])).toBe(false);
  });

  it('badges a duplicate first, then a namesake, then a new address', () => {
    const newAddress: ImportCsvDoublon = { kind: 'NEW_ADDRESS', line: null, animateurId: 'A1' };

    expect(duplicateBadge(row(2, [NAMESAKE]))).toBe('homonyme');
    expect(duplicateBadge(row(2, [NAMESAKE, ON_ROW_3]))).toBe('doublon');
    expect(duplicateBadge(row(2, [newAddress]))).toBe('adresse');
    expect(duplicateBadge(row(2, [newAddress, ON_ROW_3]))).toBe('doublon');
    expect(duplicateBadge(row(2))).toBeNull();
  });

  it('stops linking to a fiche a full replacement has deleted', () => {
    const replaced: ImportCsvDoublon = { kind: 'REPLACED', line: null, animateurId: 'A9' };

    expect(ficheLinkable(replaced, false)).toBe(true);
    expect(ficheLinkable(replaced, true)).toBe(false);
    expect(ficheLinkable(NAMESAKE, true)).toBe(true);
    expect(ficheLinkable(ON_ROW_3, false)).toBe(false);
  });
});
