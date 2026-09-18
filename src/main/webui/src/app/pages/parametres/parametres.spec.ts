import { describe, expect, it } from 'vitest';
import { readOngletParametres } from './parametres';

describe('readOngletParametres', () => {
  it('names one of the four tabs, the legal one otherwise', () => {
    expect(readOngletParametres('legaux')).toBe('legaux');
    expect(readOngletParametres('edition')).toBe('edition');
    expect(readOngletParametres('emails')).toBe('emails');
    expect(readOngletParametres('globaux')).toBe('globaux');
    expect(readOngletParametres('notifications')).toBe('legaux');
    expect(readOngletParametres('')).toBe('legaux');
    expect(readOngletParametres(null)).toBe('legaux');
  });
});
