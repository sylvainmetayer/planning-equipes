import { describe, expect, it } from 'vitest';
import { readOngletEspace } from './espace-onglets';

describe('readOngletEspace', () => {
  it('names one of the three tabs, the day otherwise', () => {
    expect(readOngletEspace('jour')).toBe('jour');
    expect(readOngletEspace('apercu')).toBe('apercu');
    expect(readOngletEspace('coequipiers')).toBe('coequipiers');
    expect(readOngletEspace('planning')).toBe('jour');
    expect(readOngletEspace('')).toBe('jour');
    expect(readOngletEspace(null)).toBe('jour');
  });
});
