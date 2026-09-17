import { describe, expect, it } from 'vitest';
import {
  AccesGrille,
  hasLignePrecedente,
  applyColonne,
  copyLignePrecedente,
  ligneSourceColonne,
} from './grille-saisie';

/** A plain grid of strings: row id → column id → value, `null` for an empty cell. */
type Grille = ReadonlyMap<string, ReadonlyMap<string, string | null>>;

const acces: AccesGrille<Grille, string | null> = {
  read: (cellules, ligneId, colonneId) => cellules.get(ligneId)?.get(colonneId) ?? null,
  write: (cellules, ligneId, colonneId, valeur) => {
    const copie = new Map(cellules);
    const ligne = new Map(copie.get(ligneId) ?? []);
    ligne.set(colonneId, valeur);
    copie.set(ligneId, ligne);
    return copie;
  },
};

function grille(lignes: Record<string, Record<string, string | null>>): Grille {
  return new Map(
    Object.entries(lignes).map(([ligneId, cellules]) => [
      ligneId,
      new Map(Object.entries(cellules)),
    ]),
  );
}

const read = (cellules: Grille, ligneId: string, colonneId: string) =>
  acces.read(cellules, ligneId, colonneId);

const LIGNES = ['a', 'b', 'c'];
const COLONNES = ['x', 'y'];

describe('hasLignePrecedente', () => {
  it("dit non sur la première ligne affichée, et sur une ligne qui n'est pas affichée", () => {
    expect(hasLignePrecedente('a', LIGNES)).toBe(false);
    expect(hasLignePrecedente('b', LIGNES)).toBe(true);
    expect(hasLignePrecedente('z', LIGNES)).toBe(false);
  });
});

describe('copyLignePrecedente', () => {
  it('recopie la ligne du dessus, écrasant ce que la ligne courante disait', () => {
    const before = grille({ a: { x: '1', y: '2' }, b: { x: '9', y: null }, c: {} });
    const { cellules, changees } = copyLignePrecedente(before, 'b', LIGNES, COLONNES, acces);
    expect(read(cellules, 'b', 'x')).toBe('1');
    expect(read(cellules, 'b', 'y')).toBe('2');
    expect(changees).toBe(2);
    // Neither the source nor the other rows move, and neither does the original map.
    expect(read(cellules, 'a', 'x')).toBe('1');
    expect(read(cellules, 'c', 'x')).toBeNull();
    expect(read(before, 'b', 'x')).toBe('9');
  });

  it("ne touche que les colonnes affichées : une colonne filtrée n'est pas recopiée", () => {
    const before = grille({ a: { x: '1', y: '2' }, b: { x: '9', y: '8' } });
    const { cellules } = copyLignePrecedente(before, 'b', LIGNES, ['x'], acces);
    expect(read(cellules, 'b', 'x')).toBe('1');
    expect(read(cellules, 'b', 'y')).toBe('8');
  });

  it('prend la ligne du dessus à l’écran, pas celle du référentiel', () => {
    const before = grille({ a: { x: '1' }, b: { x: '2' }, c: { x: '3' } });
    // The filter dropped « b »: above « c », the eye sees « a ».
    const { cellules } = copyLignePrecedente(before, 'c', ['a', 'c'], ['x'], acces);
    expect(read(cellules, 'c', 'x')).toBe('1');
  });

  it('ne fait rien sur la première ligne, ni sur une ligne hors écran', () => {
    const before = grille({ a: { x: '1' }, b: { x: '2' } });
    expect(copyLignePrecedente(before, 'a', LIGNES, COLONNES, acces)).toEqual({
      cellules: before,
      changees: 0,
    });
    expect(copyLignePrecedente(before, 'z', LIGNES, COLONNES, acces).changees).toBe(0);
  });

  it('ne compte pas une case qui disait déjà la même chose', () => {
    const before = grille({ a: { x: '1', y: '2' }, b: { x: '1', y: '9' } });
    expect(copyLignePrecedente(before, 'b', LIGNES, COLONNES, acces).changees).toBe(1);
  });

  it('saute une case source qui ne dit rien à recopier', () => {
    const partiel: AccesGrille<Grille, string | null> = {
      ...acces,
      read: (cellules, ligneId, colonneId) =>
        colonneId === 'y' && ligneId === 'a' ? undefined : acces.read(cellules, ligneId, colonneId),
    };
    const before = grille({ a: { x: '1', y: '2' }, b: { x: '9', y: '8' } });
    const { cellules, changees } = copyLignePrecedente(before, 'b', LIGNES, COLONNES, partiel);
    expect(read(cellules, 'b', 'x')).toBe('1');
    expect(read(cellules, 'b', 'y')).toBe('8');
    expect(changees).toBe(1);
  });
});

describe('applyColonne', () => {
  it('pose la valeur sur toutes les lignes affichées, et sur elles seules', () => {
    const before = grille({ a: { x: '1', y: '2' }, b: { x: '9', y: '8' }, c: { x: '7', y: '6' } });
    const { cellules, changees } = applyColonne(before, 'x', '1', ['a', 'b'], acces);
    expect(read(cellules, 'a', 'x')).toBe('1');
    expect(read(cellules, 'b', 'x')).toBe('1');
    expect(read(cellules, 'c', 'x')).toBe('7');
    // The other columns of those rows are left exactly as they were.
    expect(read(cellules, 'b', 'y')).toBe('8');
    expect(changees).toBe(1);
  });

  it('propage aussi une case vide : effacer une colonne est une décision comme une autre', () => {
    const before = grille({ a: { x: '1' }, b: { x: '2' } });
    const { cellules, changees } = applyColonne(before, 'x', null, LIGNES, acces);
    expect(read(cellules, 'a', 'x')).toBeNull();
    expect(read(cellules, 'b', 'x')).toBeNull();
    expect(changees).toBe(2);
  });

  it('ne change rien quand toutes les lignes disent déjà la valeur', () => {
    const before = grille({ a: { x: '1' }, b: { x: '1' } });
    expect(applyColonne(before, 'x', '1', ['a', 'b'], acces).changees).toBe(0);
  });
});

describe('ligneSourceColonne', () => {
  it('prend la case active quand elle est dans cette colonne', () => {
    expect(ligneSourceColonne('x', { ligneId: 'b', colonneId: 'x' }, LIGNES)).toBe('b');
  });

  it('retombe sur la première ligne affichée quand la case active est ailleurs', () => {
    expect(ligneSourceColonne('x', { ligneId: 'b', colonneId: 'y' }, LIGNES)).toBe('a');
    expect(ligneSourceColonne('x', null, LIGNES)).toBe('a');
    // An active cell the filter took off the screen does not count.
    expect(ligneSourceColonne('x', { ligneId: 'z', colonneId: 'x' }, LIGNES)).toBe('a');
  });

  it("ne désigne rien quand la grille n'affiche aucune ligne", () => {
    expect(ligneSourceColonne('x', null, [])).toBeUndefined();
  });
});
