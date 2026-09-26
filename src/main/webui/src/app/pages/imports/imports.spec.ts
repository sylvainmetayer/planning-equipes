import { describe, expect, it } from 'vitest';
import { IMPORT_CARDS, readImportCard } from './imports';

describe('readImportCard', () => {
  it('opens on the typologies, where an edition starts', () => {
    expect(readImportCard(null)).toBe('typologies');
    expect(readImportCard('')).toBe('typologies');
    expect(readImportCard('inconnu')).toBe('typologies');
  });

  it('reads every card it declares', () => {
    for (const card of IMPORT_CARDS) {
      expect(readImportCard(card)).toBe(card);
    }
  });

  /**
   * The order is the order the data is entered — the dates before the
   * animateurs, whose off days would otherwise have nothing to land on; the
   * matrix needs the rest, and the scenario cards come last because they
   * replace rather than fill.
   */
  it('lists the cards in the order an edition fills up', () => {
    expect(IMPORT_CARDS).toEqual([
      'typologies',
      'emplacements',
      'stands',
      'creneaux',
      'journees-types',
      'animateurs',
      'grille-stands',
      'scenario',
      'exemples',
      'verifier',
    ]);
  });
});
