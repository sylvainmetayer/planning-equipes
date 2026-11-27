import { describe, expect, it } from 'vitest';
import { IMPORT_CARDS, readImportCard } from './imports';

describe('readImportCard', () => {
  it('opens on the scenario, the workbook of a whole edition', () => {
    expect(readImportCard(null)).toBe('scenario');
    expect(readImportCard('')).toBe('scenario');
    expect(readImportCard('inconnu')).toBe('scenario');
  });

  it('reads every card it declares', () => {
    for (const card of IMPORT_CARDS) {
      expect(readImportCard(card)).toBe(card);
    }
  });

  // The cards withdrawn in 4.0: a bookmark or a link written against 3.x
  // (`?cible=stands`, `?cible=grille-stands`…) lands on the Importer tab, on the YAML.
  it.each(['typologies', 'emplacements', 'stands', 'creneaux', 'journees-types', 'grille-stands'])(
    'sends the withdrawn card %s to the scenario card',
    (card) => {
      expect(readImportCard(card)).toBe('scenario');
    },
  );

  it('lists the cards in the order they are offered', () => {
    expect(IMPORT_CARDS).toEqual(['scenario', 'animateurs', 'exemples', 'verifier']);
  });
});
