import { describe, expect, it } from 'vitest';
import { readOngletDebug } from './debug';

describe('readOngletDebug', () => {
  it('names one of the two tabs, the resolution one otherwise', () => {
    expect(readOngletDebug('resolution')).toBe('resolution');
    expect(readOngletDebug('verifications')).toBe('verifications');
    expect(readOngletDebug('validateur-yaml')).toBe('resolution');
    expect(readOngletDebug('')).toBe('resolution');
    expect(readOngletDebug(null)).toBe('resolution');
  });

  /** The data and YAML tabs moved to Fichiers: the route's guard sends them there, the page never reads them. */
  it('no longer knows the two tabs Fichiers took', () => {
    expect(readOngletDebug('donnees')).toBe('resolution');
    expect(readOngletDebug('yaml')).toBe('resolution');
  });
});
