import { describe, expect, it } from 'vitest';
import { readOngletParametres } from './parametres';

describe('readOngletParametres', () => {
  it('names one of the five tabs, the legal one otherwise', () => {
    expect(readOngletParametres('legaux')).toBe('legaux');
    expect(readOngletParametres('edition')).toBe('edition');
    expect(readOngletParametres('emails')).toBe('emails');
    expect(readOngletParametres('mural')).toBe('mural');
    expect(readOngletParametres('instance')).toBe('instance');
    expect(readOngletParametres('notifications')).toBe('legaux');
    expect(readOngletParametres('')).toBe('legaux');
    expect(readOngletParametres(null)).toBe('legaux');
  });

  /** « Globaux » became « Instance »: a bookmark of the old name lands on the same cards. */
  it('reads the former name of the instance tab', () => {
    expect(readOngletParametres('globaux')).toBe('instance');
  });
});
