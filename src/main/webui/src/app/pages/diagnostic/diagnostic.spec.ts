import { describe, expect, it } from 'vitest';
import { readOnglet } from './diagnostic';

describe('readOnglet', () => {
  it('names one of the four tabs, the problems otherwise', () => {
    expect(readOnglet('besoin')).toBe('besoin');
    expect(readOnglet('fragilite')).toBe('fragilite');
    expect(readOnglet('former')).toBe('former');
    // The bench left for the Siège panel: an old address never lands on a blank tab.
    expect(readOnglet('banc')).toBe('problemes');
    expect(readOnglet('problemes')).toBe('problemes');
    expect(readOnglet('staffing')).toBe('problemes');
    expect(readOnglet(null)).toBe('problemes');
  });
});
