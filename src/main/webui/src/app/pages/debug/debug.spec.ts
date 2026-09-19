import { describe, expect, it } from 'vitest';
import { readOngletDebug } from './debug';

describe('readOngletDebug', () => {
  it('names one of the four tabs, the resolution one otherwise', () => {
    expect(readOngletDebug('resolution')).toBe('resolution');
    expect(readOngletDebug('verifications')).toBe('verifications');
    expect(readOngletDebug('donnees')).toBe('donnees');
    expect(readOngletDebug('yaml')).toBe('yaml');
    expect(readOngletDebug('validateur-yaml')).toBe('resolution');
    expect(readOngletDebug('')).toBe('resolution');
    expect(readOngletDebug(null)).toBe('resolution');
  });
});
