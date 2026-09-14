import { describe, expect, it } from 'vitest';
import { ONGLETS_IMPORTS, readOngletImports } from './imports';

describe('readOngletImports', () => {
  it('opens on the typologies, where an edition starts', () => {
    expect(readOngletImports(null)).toBe('typologies');
    expect(readOngletImports('')).toBe('typologies');
    expect(readOngletImports('inconnu')).toBe('typologies');
  });

  it('reads every tab it declares', () => {
    for (const onglet of ONGLETS_IMPORTS) {
      expect(readOngletImports(onglet)).toBe(onglet);
    }
  });

  /**
   * The order is the order the data is entered — the dates before the
   * animateurs, whose off days would otherwise have nothing to land on; the
   * matrix needs the rest, and the scenario comes last because it replaces
   * rather than fills.
   */
  it('lists the tabs in the order an edition fills up', () => {
    expect(ONGLETS_IMPORTS).toEqual([
      'typologies',
      'emplacements',
      'stands',
      'creneaux',
      'journees-types',
      'animateurs',
      'grille-stands',
      'scenario',
    ]);
  });
});
