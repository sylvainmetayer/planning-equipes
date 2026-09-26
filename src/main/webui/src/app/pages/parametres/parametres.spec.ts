import { describe, expect, it } from 'vitest';
import { readOngletParametres } from './parametres';

describe('readOngletParametres', () => {
  it('names one of the three tabs, the edition one otherwise', () => {
    expect(readOngletParametres('edition')).toBe('edition');
    expect(readOngletParametres('mural')).toBe('mural');
    expect(readOngletParametres('instance')).toBe('instance');
    expect(readOngletParametres('notifications')).toBe('edition');
    expect(readOngletParametres('')).toBe('edition');
    expect(readOngletParametres(null)).toBe('edition');
  });

  /** « Globaux » became « Instance »: a bookmark of the old name lands on the same cards. */
  it('reads the former name of the instance tab', () => {
    expect(readOngletParametres('globaux')).toBe('instance');
  });
});
