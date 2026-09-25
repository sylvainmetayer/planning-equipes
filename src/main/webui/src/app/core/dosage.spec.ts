import { describe, expect, it } from 'vitest';
import { dosageDifferences, dosageKey, dosageLines, dosageSummary, dosageToken } from './dosage';
import { Dosage } from './models';

const NONE: Dosage = {
  weights: {},
  instanceWeights: { stabiliteDuPlanPublie: 5 },
  disabled: [],
  enabled: [],
};
const RETUNED: Dosage = {
  weights: { souhaitsIncompatibles: 5, appreciationIncompatible: 3 },
  instanceWeights: { stabiliteDuPlanPublie: 5 },
  disabled: ['equilibrerCharge'],
  enabled: [],
};

describe('dosage', () => {
  it('says default, a count of rules, or unknown for a row older than the figure', () => {
    expect(dosageSummary(NONE)).toBe('défaut');
    expect(dosageSummary(RETUNED)).toBe('3 règle(s) repondérée(s)');
    expect(dosageSummary(null)).toBe('inconnu');
  });

  it('keys equal weightings alike, whatever the order they came in', () => {
    const reordered: Dosage = {
      ...RETUNED,
      weights: { appreciationIncompatible: 3, souhaitsIncompatibles: 5 },
    };
    expect(dosageKey(reordered)).toBe(dosageKey(RETUNED));
    expect(dosageToken(reordered)).toBe(dosageToken(RETUNED));
    expect(dosageKey(NONE)).not.toBe(dosageKey(RETUNED));
  });

  it('never matches an unknown dosage, not even another unknown', () => {
    expect(dosageKey(null)).toBeNull();
    expect(dosageToken(undefined)).toBeNull();
  });

  it('tells the instance changing apart from the edition retuning', () => {
    const instanceMoved: Dosage = { ...NONE, instanceWeights: { stabiliteDuPlanPublie: 8 } };
    expect(dosageKey(instanceMoved)).not.toBe(dosageKey(NONE));
    expect(dosageSummary(instanceMoved)).toBe('défaut');
  });

  it('details every rule it moves, with the default it moved from', () => {
    expect(dosageLines(RETUNED)).toEqual([
      'appreciationIncompatible : 3 (défaut 1)',
      'souhaitsIncompatibles : 5 (défaut 1)',
      'equilibrerCharge : désactivée',
    ]);
  });

  it('lists what two dosages disagree on, rule by rule', () => {
    expect(dosageDifferences(NONE, RETUNED)).toEqual([
      { name: 'appreciationIncompatible', base: '1', variante: '3' },
      { name: 'equilibrerCharge', base: '1', variante: '1, désactivée' },
      { name: 'souhaitsIncompatibles', base: '1', variante: '5' },
    ]);
    expect(dosageDifferences(RETUNED, RETUNED)).toEqual([]);
  });
});
