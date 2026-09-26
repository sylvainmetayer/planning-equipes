import { describe, expect, it } from 'vitest';
import { NO_SORT } from './view-query-params';
import { compareNatural, compareSortValues, sortRows } from './table-sort';

interface Ligne {
  id: string;
  n: number | null;
}

const lignes: Ligne[] = [
  { id: 'T10', n: 1 },
  { id: 'T2', n: null },
  { id: 'T1', n: 3 },
];

describe('table-sort', () => {
  it('orders identifiers naturally: T1, T2, T10', () => {
    expect(['T10', 'T2', 'T1'].sort(compareNatural)).toEqual(['T1', 'T2', 'T10']);
    expect(
      sortRows(
        lignes,
        NO_SORT,
        () => undefined,
        (ligne) => ligne.id,
      ).map((l) => l.id),
    ).toEqual(['T1', 'T2', 'T10']);
  });

  it('sorts a column both ways, an empty cell last either way', () => {
    const valeur = (ligne: Ligne) => ligne.n;
    expect(
      sortRows(lignes, { active: 'n', direction: 'asc' }, valeur, (ligne) => ligne.id).map(
        (l) => l.id,
      ),
    ).toEqual(['T10', 'T1', 'T2']);
    expect(
      sortRows(lignes, { active: 'n', direction: 'desc' }, valeur, (ligne) => ligne.id).map(
        (l) => l.id,
      ),
    ).toEqual(['T1', 'T10', 'T2']);
  });

  it('puts « oui » before « non », and compares texts naturally', () => {
    expect(compareSortValues(true, false)).toBeLessThan(0);
    expect(compareSortValues('Stand 9', 'Stand 10')).toBeLessThan(0);
    expect(compareSortValues('Élodie', 'Zoé')).toBeLessThan(0);
  });
});
