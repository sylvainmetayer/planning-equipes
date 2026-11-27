import { describe, expect, it } from 'vitest';
import { readOnglet } from './diagnostic';

describe('readOnglet', () => {
  it('names one of the four tabs, the problems otherwise', () => {
    expect(readOnglet('besoin')).toBe('besoin');
    expect(readOnglet('risques')).toBe('risques');
    expect(readOnglet('conformite')).toBe('conformite');
    // The bench left for the Siège panel; Fragilité, « À former » and Tension
    // are sections of Risques: the routes redirect them, and an old address
    // never lands on a blank tab.
    expect(readOnglet('banc')).toBe('problemes');
    expect(readOnglet('former')).toBe('problemes');
    expect(readOnglet('tension')).toBe('problemes');
    expect(readOnglet('fragilite')).toBe('problemes');
    expect(readOnglet('problemes')).toBe('problemes');
    expect(readOnglet('staffing')).toBe('problemes');
    expect(readOnglet(null)).toBe('problemes');
  });
});
