import { describe, expect, it } from 'vitest';
import { readOnglet } from './diagnostic';

describe('readOnglet', () => {
  it('names one of the five tabs, the problems otherwise', () => {
    expect(readOnglet('besoin')).toBe('besoin');
    expect(readOnglet('fragilite')).toBe('fragilite');
    expect(readOnglet('former')).toBe('former');
    expect(readOnglet('banc')).toBe('banc');
    expect(readOnglet('problemes')).toBe('problemes');
    expect(readOnglet('staffing')).toBe('problemes');
    expect(readOnglet(null)).toBe('problemes');
  });
});
