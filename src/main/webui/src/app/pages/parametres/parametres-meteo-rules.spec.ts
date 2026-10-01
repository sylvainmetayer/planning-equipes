import { describe, expect, it } from 'vitest';
import { borne, phenomenonLabel } from './parametres-meteo-rules';

describe('the weather alert block', () => {
  it('clamps a typed number to what the server accepts, a blank being no value', () => {
    expect(borne('20', 1, 14)).toBe(14);
    expect(borne('0', 1, 14)).toBe(1);
    expect(borne('5.6', 1, 14)).toBe(6);
    expect(borne('  ', 1, 14)).toBeNull();
    expect(borne('abc', 1, 14)).toBeNull();
  });

  it('says a phenomenon in words, an unknown code as is', () => {
    expect(phenomenonLabel('chaleur')).toBe('chaleur');
    expect(phenomenonLabel('orage')).toBe('orage');
    expect(phenomenonLabel('grele')).toBe('grele');
  });
});
