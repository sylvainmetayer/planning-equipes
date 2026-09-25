import { describe, expect, it } from 'vitest';
import {
  TYPOLOGIE_COLOR_COUNT,
  TYPOLOGIE_COLOR_NONE,
  typologieColorClass,
  typologieColorIndex,
  typologieLabel,
  typologieLabels,
  typologiePrincipale,
} from './typologie-colors';

describe('typologieColorIndex', () => {
  it('always lands inside the palette', () => {
    ['STRATEGIE', 'AMBIANCE', 'ENFANT', 'x', '', 'très-long-identifiant-de-typologie'].forEach(
      (id) => {
        const index = typologieColorIndex(id);
        expect(index).toBeGreaterThanOrEqual(0);
        expect(index).toBeLessThan(TYPOLOGIE_COLOR_COUNT);
      },
    );
  });

  it('gives the same typologie the same colour every time, and different ids different buckets', () => {
    expect(typologieColorIndex('STRATEGIE')).toBe(typologieColorIndex('STRATEGIE'));
    const buckets = new Set(
      ['STRATEGIE', 'AMBIANCE', 'ENFANT', 'EXPERT'].map((id) => typologieColorIndex(id)),
    );
    expect(buckets.size).toBeGreaterThan(1);
  });
});

describe('typologieColorClass', () => {
  it('falls back on the neutral class when the stand has no typologie', () => {
    expect(typologieColorClass(null)).toBe(TYPOLOGIE_COLOR_NONE);
    expect(typologieColorClass(undefined)).toBe(TYPOLOGIE_COLOR_NONE);
    expect(typologieColorClass('')).toBe(TYPOLOGIE_COLOR_NONE);
  });

  it('names the palette class of the typologie', () => {
    expect(typologieColorClass('STRATEGIE')).toBe(
      `typologie-color-${typologieColorIndex('STRATEGIE')}`,
    );
  });
});

describe('typologiePrincipale', () => {
  it("orders by code unit, the same way the server does, whatever the reader's locale", () => {
    // `localeCompare` puts « apéro » before « Zoo »; a plain String sort, which
    // is what the server runs, does the opposite. The two must agree, or the
    // espace animateur and the admin views colour the same stand differently.
    expect(typologiePrincipale(['Zoo', 'apéro'])).toBe('Zoo');
  });

  it('picks the lowest id so the colour never depends on set iteration order', () => {
    expect(typologiePrincipale(['STRATEGIE', 'AMBIANCE'])).toBe('AMBIANCE');
    expect(typologiePrincipale(['AMBIANCE', 'STRATEGIE'])).toBe('AMBIANCE');
  });

  it('returns null for a stand proposing nothing', () => {
    expect(typologiePrincipale([])).toBeNull();
  });
});

describe('typologieLabel', () => {
  it('falls back on the raw id when the referential does not know it', () => {
    const labels = typologieLabels([{ id: 'STRATEGIE', label: 'Stratégie' }]);
    expect(typologieLabel(labels, 'STRATEGIE')).toBe('Stratégie');
    expect(typologieLabel(labels, 'INCONNUE')).toBe('INCONNUE');
  });

  it('falls back on the id when the referential has an empty label', () => {
    expect(typologieLabel(typologieLabels([{ id: 'STRATEGIE', label: '' }]), 'STRATEGIE')).toBe(
      'STRATEGIE',
    );
  });
});
