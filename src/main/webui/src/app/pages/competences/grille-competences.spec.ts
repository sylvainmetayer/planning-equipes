import { describe, expect, it } from 'vitest';
import { Animateur } from '../../core/models';
import {
  cellsFrom,
  filterAnimateurs,
  isCellModified,
  keepLocalRows,
  levelAt,
  levelForKey,
  modifiedAnimateurs,
  moveFrom,
  nextLevel,
  readTypologiesParam,
  saisie,
  writeCell,
} from './grille-competences';

const TYPOLOGIES = ['jeux', 'ateliers', 'ninja'];

function animateur(id: string, competences: Animateur['competences'], nom = 'Martin'): Animateur {
  return {
    id,
    prenom: 'Alice',
    nom,
    dateNaissance: '1990-01-01',
    manager: false,
    competences,
    souhaits: [],
    joursIndisponibles: [],
    modifieLe: `2026-09-06T10:00:00Z#${id}`,
  };
}

const ROSTER: Animateur[] = [
  animateur('A1', { jeux: 'REFERENT', ateliers: 'DEBUTANT' }),
  animateur('B2', {}, 'Lefèvre'),
];

describe('cellsFrom', () => {
  it('gives every animateur a cell per typologie of the referential, null when nothing is stored', () => {
    const cells = cellsFrom(ROSTER, TYPOLOGIES);
    expect(levelAt(cells, { animateurId: 'A1', typologieId: 'jeux' })).toBe('REFERENT');
    expect(levelAt(cells, { animateurId: 'A1', typologieId: 'ninja' })).toBeNull();
    expect(Array.from(cells.get('B2')!.keys())).toEqual(TYPOLOGIES);
    expect(levelAt(cells, { animateurId: 'B2', typologieId: 'jeux' })).toBeNull();
  });
});

describe('writeCell and the diff against the reference', () => {
  it('never mutates the map it was given', () => {
    const reference = cellsFrom(ROSTER, TYPOLOGIES);
    const cells = writeCell(reference, { animateurId: 'B2', typologieId: 'jeux' }, 'AUTONOME');
    expect(levelAt(reference, { animateurId: 'B2', typologieId: 'jeux' })).toBeNull();
    expect(levelAt(cells, { animateurId: 'B2', typologieId: 'jeux' })).toBe('AUTONOME');
    expect(cells.get('A1')).toBe(reference.get('A1'));
  });

  it('names only the animateurs whose cells changed, and forgets one typed back to its value', () => {
    const reference = cellsFrom(ROSTER, TYPOLOGIES);
    let cells = writeCell(reference, { animateurId: 'B2', typologieId: 'jeux' }, 'AUTONOME');
    cells = writeCell(cells, { animateurId: 'A1', typologieId: 'jeux' }, null);
    expect(modifiedAnimateurs(cells, reference)).toEqual(['A1', 'B2']);
    expect(isCellModified(cells, reference, { animateurId: 'A1', typologieId: 'jeux' })).toBe(true);
    expect(isCellModified(cells, reference, { animateurId: 'A1', typologieId: 'ateliers' })).toBe(
      false,
    );

    cells = writeCell(cells, { animateurId: 'A1', typologieId: 'jeux' }, 'REFERENT');
    expect(modifiedAnimateurs(cells, reference)).toEqual(['B2']);
  });
});

describe('saisie', () => {
  it('sends the whole map of each named animateur, the empty cells left out, with the stamp read', () => {
    const reference = cellsFrom(ROSTER, TYPOLOGIES);
    const cells = writeCell(reference, { animateurId: 'A1', typologieId: 'ateliers' }, null);
    const stamps = new Map(ROSTER.map((each) => [each.id, each.modifieLe ?? null]));

    expect(saisie(cells, ['A1'], stamps)).toEqual([
      {
        animateurId: 'A1',
        modifieLe: '2026-09-06T10:00:00Z#A1',
        competences: { jeux: 'REFERENT' },
      },
    ]);
    // No stamp known: no precondition, which is how a knowing overwrite says it.
    expect(saisie(cells, ['B2'])).toEqual([
      { animateurId: 'B2', modifieLe: null, competences: {} },
    ]);
  });
});

describe('keys and clicks', () => {
  it('reads 0 to 3, Backspace and Delete, and nothing else', () => {
    expect(levelForKey('0')).toBeNull();
    expect(levelForKey('Backspace')).toBeNull();
    expect(levelForKey('Delete')).toBeNull();
    expect(levelForKey('1')).toBe('DEBUTANT');
    expect(levelForKey('2')).toBe('AUTONOME');
    expect(levelForKey('3')).toBe('REFERENT');
    expect(levelForKey('4')).toBeUndefined();
    expect(levelForKey('a')).toBeUndefined();
    expect(levelForKey('ArrowDown')).toBeUndefined();
  });

  it('cycles empty → Débutant → Autonome → Référent → empty', () => {
    expect(nextLevel(null)).toBe('DEBUTANT');
    expect(nextLevel('DEBUTANT')).toBe('AUTONOME');
    expect(nextLevel('AUTONOME')).toBe('REFERENT');
    expect(nextLevel('REFERENT')).toBeNull();
  });
});

describe('moveFrom', () => {
  const ids = ['A1', 'B2', 'C3'];
  const from = { animateurId: 'B2', typologieId: 'ateliers' };

  it('walks the grid with the arrows, Enter going down like a spreadsheet', () => {
    expect(moveFrom('ArrowDown', from, ids, TYPOLOGIES)).toEqual({
      animateurId: 'C3',
      typologieId: 'ateliers',
    });
    expect(moveFrom('Enter', from, ids, TYPOLOGIES)).toEqual({
      animateurId: 'C3',
      typologieId: 'ateliers',
    });
    expect(moveFrom('ArrowUp', from, ids, TYPOLOGIES)).toEqual({
      animateurId: 'A1',
      typologieId: 'ateliers',
    });
    expect(moveFrom('ArrowRight', from, ids, TYPOLOGIES)).toEqual({
      animateurId: 'B2',
      typologieId: 'ninja',
    });
    expect(moveFrom('ArrowLeft', from, ids, TYPOLOGIES)).toEqual({
      animateurId: 'B2',
      typologieId: 'jeux',
    });
    expect(moveFrom('Home', from, ids, TYPOLOGIES)).toEqual({
      animateurId: 'B2',
      typologieId: 'jeux',
    });
    expect(moveFrom('End', from, ids, TYPOLOGIES)).toEqual({
      animateurId: 'B2',
      typologieId: 'ninja',
    });
  });

  it('stops at the edges, and answers null for a key it does not handle or a cell it does not show', () => {
    expect(
      moveFrom('ArrowDown', { animateurId: 'C3', typologieId: 'jeux' }, ids, TYPOLOGIES),
    ).toEqual({ animateurId: 'C3', typologieId: 'jeux' });
    expect(
      moveFrom('ArrowLeft', { animateurId: 'A1', typologieId: 'jeux' }, ids, TYPOLOGIES),
    ).toEqual({ animateurId: 'A1', typologieId: 'jeux' });
    expect(moveFrom('Tab', from, ids, TYPOLOGIES)).toBeNull();
    expect(
      moveFrom('ArrowDown', { animateurId: 'Z9', typologieId: 'jeux' }, ids, TYPOLOGIES),
    ).toBeNull();
    // A column the filter hid is not walked into.
    expect(moveFrom('ArrowRight', from, ids, ['ateliers'])).toEqual(from);
  });
});

describe('filters and their URL form', () => {
  it('matches the name, the first name and the id, accents aside', () => {
    expect(filterAnimateurs(ROSTER, 'lefevre').map((each) => each.id)).toEqual(['B2']);
    expect(filterAnimateurs(ROSTER, 'ALICE').map((each) => each.id)).toEqual(['A1', 'B2']);
    expect(filterAnimateurs(ROSTER, 'a1').map((each) => each.id)).toEqual(['A1']);
    expect(filterAnimateurs(ROSTER, '')).toHaveLength(2);
  });

  it('keeps only the typologies the referential has, in referential order, and reads an empty param as every column', () => {
    expect(readTypologiesParam('ninja, jeux,inconnue', TYPOLOGIES)).toEqual(['jeux', 'ninja']);
    expect(readTypologiesParam(null, TYPOLOGIES)).toEqual([]);
    expect(readTypologiesParam('', TYPOLOGIES)).toEqual([]);
  });
});

describe('keepLocalRows', () => {
  it('takes the reloaded roster, except for the rows the user keeps as typed', () => {
    const before = cellsFrom(ROSTER, TYPOLOGIES);
    const typed = writeCell(before, { animateurId: 'B2', typologieId: 'jeux' }, 'REFERENT');
    const reloaded = cellsFrom(
      [animateur('A1', { jeux: 'AUTONOME' }), animateur('B2', { ateliers: 'DEBUTANT' }, 'Lefèvre')],
      TYPOLOGIES,
    );

    const merged = keepLocalRows(reloaded, typed, new Set(['B2']));
    expect(levelAt(merged, { animateurId: 'A1', typologieId: 'jeux' })).toBe('AUTONOME');
    expect(levelAt(merged, { animateurId: 'B2', typologieId: 'jeux' })).toBe('REFERENT');
    expect(levelAt(merged, { animateurId: 'B2', typologieId: 'ateliers' })).toBeNull();
    expect(keepLocalRows(reloaded, typed, new Set())).toBe(reloaded);
  });
});
