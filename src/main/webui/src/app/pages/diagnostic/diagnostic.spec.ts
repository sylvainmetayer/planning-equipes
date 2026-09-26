import { describe, expect, it } from 'vitest';
import { readOnglet } from './diagnostic';

describe('readOnglet', () => {
  it('names one of the four tabs, the problems otherwise', () => {
    expect(readOnglet('besoin')).toBe('besoin');
    expect(readOnglet('tension')).toBe('tension');
    expect(readOnglet('fragilite')).toBe('fragilite');
    // The bench left for the Siège panel, « À former » for the foot of Besoin:
    // the routes redirect both, and an old address never lands on a blank tab.
    expect(readOnglet('banc')).toBe('problemes');
    expect(readOnglet('former')).toBe('problemes');
    expect(readOnglet('problemes')).toBe('problemes');
    expect(readOnglet('staffing')).toBe('problemes');
    expect(readOnglet(null)).toBe('problemes');
  });
});
