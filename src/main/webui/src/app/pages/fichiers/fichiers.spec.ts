import { describe, expect, it } from 'vitest';
import { ONGLETS_FICHIERS, readOngletFichiers } from './fichiers';

describe('readOngletFichiers', () => {
  it('opens on the Importer tab, where an edition starts', () => {
    expect(readOngletFichiers(null)).toBe('importer');
    expect(readOngletFichiers('')).toBe('importer');
    expect(readOngletFichiers('csv')).toBe('importer');
  });

  it('reads the three tabs, in the order of the cycle', () => {
    expect(ONGLETS_FICHIERS).toEqual(['importer', 'exporter', 'archive']);
    for (const onglet of ONGLETS_FICHIERS) {
      expect(readOngletFichiers(onglet)).toBe(onglet);
    }
  });
});
