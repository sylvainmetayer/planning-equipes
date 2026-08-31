import { describe, expect, it } from 'vitest';
import {
  CHAMPS_IMPORT,
  classeAction,
  iconeAction,
  libelleColonne,
  mappingNommeQuelquun,
  mappingVide,
  mappingVideOuNul,
  withColonne
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

  it('requires an id, a first name or a last name for a row to name somebody', () => {
    expect(mappingNommeQuelquun(null)).toBe(false);
    expect(mappingNommeQuelquun(withColonne(mappingVide(), 'dateNaissance', 0))).toBe(false);
    expect(mappingNommeQuelquun(withColonne(mappingVide(), 'nom', 0))).toBe(true);
    expect(mappingNommeQuelquun(withColonne(mappingVide(), 'id', 0))).toBe(true);
  });

  it('offers the nine fields of an animateur fiche', () => {
    expect(CHAMPS_IMPORT).toHaveLength(9);
    expect(new Set(CHAMPS_IMPORT).size).toBe(9);
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
    expect(new Set(['CREATED', 'UPDATED', 'REJECTED'].map((a) => iconeAction(a as never))).size).toBe(3);
  });
});
